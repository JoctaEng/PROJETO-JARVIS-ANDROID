package com.joctaeng.jarvis.tasks

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.joctaeng.jarvis.JarvisApp
import com.joctaeng.jarvis.R
import com.joctaeng.jarvis.mind.memory.ScheduledTask
import com.joctaeng.jarvis.mind.memory.TaskSchedule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.ZoneId

/**
 * Tarefas programadas (v0.28, plano "pedido 65d"): o alarme do Android acorda o Euno no horário, ele faz o pedido
 * guardado como se o usuário tivesse falado e avisa o resultado por notificação (e fala, se a tarefa pedir).
 * Ações de risco continuam passando pela confirmação: nada é enviado, pago ou apagado sozinho.
 */
object TaskScheduler {
    const val CHANNEL = "euno_tarefas"
    private const val EXTRA_ID = "task_id"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun pending(context: Context, id: String, flags: Int): PendingIntent? =
        PendingIntent.getBroadcast(
            context, id.hashCode(),
            Intent(context, TaskAlarmReceiver::class.java).setAction("com.joctaeng.euno.TAREFA").putExtra(EXTRA_ID, id),
            flags or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Marca (ou desmarca) o próximo horário de cada tarefa. Chamado ao abrir o app, ao ligar o celular e a cada mudança. */
    fun rescheduleAll(context: Context) {
        val app = JarvisApp.from(context)
        app.tasks.all().forEach { schedule(context, it) }
    }

    fun schedule(context: Context, task: ScheduledTask) {
        val am = context.getSystemService(AlarmManager::class.java)
        cancel(context, task.id)
        val next = TaskSchedule.next(task, Instant.now(), ZoneId.systemDefault()) ?: return
        val pi = pending(context, task.id, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        // Exato quando o Android permite ("Alarmes e lembretes"); senão, aproximado (pode atrasar alguns minutos no modo soneca).
        if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.toEpochMilli(), pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.toEpochMilli(), pi)
    }

    fun cancel(context: Context, id: String) {
        val pi = pending(context, id, PendingIntent.FLAG_NO_CREATE) ?: return
        context.getSystemService(AlarmManager::class.java).cancel(pi)
        pi.cancel()
    }

    /** Roda a tarefa agora (pelo alarme ou pelo botão "Rodar agora"). */
    fun runNow(context: Context, id: String, onDone: (() -> Unit)? = null) {
        val app = JarvisApp.from(context)
        scope.launch {
            val task = app.tasks.get(id) ?: return@launch
            app.events.info("tarefas", "rodando \"${task.name}\"")
            val answer = withTimeoutOrNull(5 * 60_000L) { app.conversation.runScheduled(task.name, task.instruction) }
            val ok = !answer.isNullOrBlank()
            val text = answer?.takeIf { it.isNotBlank() } ?: "não terminou a tempo (o cérebro não respondeu em 5 minutos)"
            app.tasks.recordRun(task.id, ok, text)
            app.events.info("tarefas", "\"${task.name}\": ${if (ok) "feita" else "falhou"}")
            notify(context, task.name, text)
            if (ok && task.speak) runCatching { app.voice.speak(text) }
            app.tasks.get(task.id)?.let { schedule(context, it) }
            onDone?.invoke()
        }
    }

    private fun notify(context: Context, title: String, text: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Tarefas programadas", NotificationManager.IMPORTANCE_DEFAULT))
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_jarvis)
            .setContentTitle("Tarefa: $title")
            .setContentText(text.take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(1_500)))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(title.hashCode(), n) }
    }

    internal fun idFrom(intent: Intent): String? = intent.getStringExtra(EXTRA_ID)
}

/** O alarme da tarefa chegou: roda e marca a próxima. */
class TaskAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = TaskScheduler.idFrom(intent) ?: return
        val pending = goAsync()
        TaskScheduler.runNow(context.applicationContext, id) { runCatching { pending.finish() } }
    }
}

/** Celular ligado ou app atualizado: os alarmes do Android se perdem; marca tudo de novo. */
class TaskBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runCatching { TaskScheduler.rescheduleAll(context.applicationContext) }
    }
}
