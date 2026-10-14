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
import kotlinx.coroutines.cancel
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

    /** Roda a tarefa agora (pelo botão "Rodar agora"); o alarme usa o [TaskRunService]. */
    fun runNow(context: Context, id: String, onDone: (() -> Unit)? = null) {
        scope.launch {
            try {
                execute(context.applicationContext, id)
            } finally {
                onDone?.invoke()
            }
        }
    }

    /** Tarefa que fala ao acordar ("me acorde", "me avise", "fale", "bom dia"): fala sempre e avisa com som de alarme. */
    private val wakeWords = Regex("""(?i)\b(acord|despert|bom dia)""")
    private val speakWords = Regex("""(?i)\b(acord|despert|fal[ae]|diga|me avis|leia|bom dia|me lembr)""")

    /**
     * Faz a tarefa de verdade (0.31, teste de 14/10: "me acordou mas não disse o resumo"; "Resumo de e-mails" virou outra
     * tarefa em vez de buscar os e-mails). Pedido de resumo/agenda/e-mail/clima → o motor busca os dados pelas ferramentas e
     * monta o texto; o resto → conversa com a IA, sem poder criar ou apagar tarefas no meio.
     */
    suspend fun execute(context: Context, id: String) {
        val app = JarvisApp.from(context)
        val task = app.tasks.get(id) ?: return
        app.events.info("tarefas", "rodando \"${task.name}\"")
        val parts = com.joctaeng.jarvis.mind.intent.Briefing.wants(task.instruction + " " + task.name)
        val answer = runCatching {
            withTimeoutOrNull(4 * 60_000L) {
                if (parts != null) app.conversation.runBriefing(task.name, parts)
                else app.conversation.runScheduled(task.name, task.instruction)
            }
        }.onFailure { app.events.warn("tarefas", "\"${task.name}\" quebrou", it) }.getOrNull()
        val ok = !answer.isNullOrBlank()
        val text = answer?.takeIf { it.isNotBlank() } ?: "Não consegui fazer a tarefa \"${task.name}\" desta vez (sem resposta a tempo)."
        app.tasks.recordRun(task.id, ok, text)
        app.events.info("tarefas", "\"${task.name}\": ${if (ok) "feita" else "falhou"}")
        val said = task.instruction + " " + task.name
        val wake = wakeWords.containsMatchIn(said)
        notify(context, task.name, text, wake)
        if (task.speak || speakWords.containsMatchIn(said)) {
            runCatching {
                app.voice.speak(text)
                withTimeoutOrNull(3 * 60_000L) { app.voice.awaitIdle() }
            }
        }
        app.tasks.get(task.id)?.let { schedule(context, it) }
    }

    private const val WAKE_CHANNEL = "euno_despertar"

    private fun notify(context: Context, title: String, text: String, wake: Boolean) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val channel = if (wake) {
            nm.createNotificationChannel(
                NotificationChannel(WAKE_CHANNEL, "Despertar do Euno", NotificationManager.IMPORTANCE_HIGH).apply {
                    setSound(
                        android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM),
                        android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM).build(),
                    )
                },
            )
            WAKE_CHANNEL
        } else {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Tarefas programadas", NotificationManager.IMPORTANCE_DEFAULT))
            CHANNEL
        }
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_jarvis)
            .setContentTitle(title)
            .setContentText(text.take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(1_500)))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(if (wake) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching { nm.notify(title.hashCode(), n) }
    }

    internal fun idFrom(intent: Intent): String? = intent.getStringExtra(EXTRA_ID)
}

/**
 * O alarme da tarefa chegou. Antes o receptor segurava o alarme (goAsync) até a IA responder, minutos depois, e o Android
 * registrava ANR (relatório da 0.30.0, três vezes). Agora entrega a tarefa a um serviço curto em primeiro plano e termina na hora.
 */
class TaskAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = TaskScheduler.idFrom(intent) ?: return
        val app = context.applicationContext
        val started = runCatching { app.startForegroundService(TaskRunService.intent(app, id)) }
        if (started.isFailure) {
            // Alarme aproximado (sem "Alarmes e lembretes"): o Android pode recusar o serviço. Roda aqui, com teto de 50 s.
            JarvisApp.from(app).events.warn("tarefas", "serviço recusado (${started.exceptionOrNull()?.javaClass?.simpleName}); rodando no receptor")
            val pending = goAsync()
            CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                try {
                    withTimeoutOrNull(50_000L) { TaskScheduler.execute(app, id) }
                } finally {
                    runCatching { pending.finish() }
                }
            }
        }
    }
}

/** Serviço curto em primeiro plano que roda uma tarefa (o processo fica vivo enquanto a IA responde e a voz fala). */
class TaskRunService : android.app.Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var running = 0

    override fun onBind(intent: Intent?): android.os.IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(RUN_CHANNEL, "Euno trabalhando", NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(this, RUN_CHANNEL)
            .setSmallIcon(R.drawable.ic_jarvis)
            .setContentTitle("Euno está fazendo uma tarefa programada")
            .setOngoing(true)
            .build()
        runCatching {
            androidx.core.app.ServiceCompat.startForeground(this, 4209, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        }.onFailure {
            JarvisApp.from(this).events.warn("tarefas", "não consegui ficar em primeiro plano: ${it.message}")
        }
        val id = intent?.getStringExtra(EXTRA) ?: run { stopSelf(startId); return START_NOT_STICKY }
        running++
        scope.launch {
            try {
                TaskScheduler.execute(applicationContext, id)
            } finally {
                launch(Dispatchers.Main) {
                    running--
                    if (running <= 0) stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA = "task_id"
        private const val RUN_CHANNEL = "euno_trabalhando"
        fun intent(context: Context, id: String): Intent = Intent(context, TaskRunService::class.java).putExtra(EXTRA, id)
    }
}

/** Celular ligado ou app atualizado: os alarmes do Android se perdem; marca tudo de novo. */
class TaskBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runCatching { TaskScheduler.rescheduleAll(context.applicationContext) }
    }
}
