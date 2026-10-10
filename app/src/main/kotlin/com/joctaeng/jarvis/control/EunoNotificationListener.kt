package com.joctaeng.jarvis.control

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.joctaeng.jarvis.JarvisApp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lê as notificações que chegam (só depois de o usuário ligar "Acesso a notificações" para o Euno). Guarda as últimas
 * na memória do app — nada vai para disco nem para a nuvem sozinho — e nunca responde a ninguém por conta própria.
 * O cérebro só vê quando o usuário pergunta ("o que chegou?"), pela ferramenta notificacoes_ler.
 */
class EunoNotificationListener : NotificationListenerService() {

    data class Item(val key: String, val appLabel: String, val title: String, val text: String, val postedAt: Long) {
        fun line(): String {
            val time = SimpleDateFormat("HH:mm", Locale.forLanguageTag("pt-BR")).format(Date(postedAt))
            return "- $time · $appLabel · ${title.take(60)}: ${text.take(220)}"
        }
    }

    override fun onListenerConnected() {
        connected = true
        runCatching { activeNotifications?.forEach { add(it) } }
        JarvisApp.from(this).events.info("notificacoes", "acesso às notificações conectado")
    }

    override fun onListenerDisconnected() {
        connected = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        add(sbn)
    }

    private fun add(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName || sbn.isOngoing) return
        val extras = sbn.notification?.extras ?: return
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }
            .getOrDefault(sbn.packageName)
        synchronized(buffer) {
            // Notificação atualizada (mesma chave) substitui a anterior: sem repetidas.
            buffer.removeAll { it.key == sbn.key }
            buffer.add(0, Item(sbn.key, label, title, text, sbn.postTime))
            while (buffer.size > MAX) buffer.removeAt(buffer.lastIndex)
        }
    }

    companion object {
        private const val MAX = 60
        private val buffer = mutableListOf<Item>()

        @Volatile var connected = false
            private set

        /** Mais novas primeiro. */
        fun recent(): List<Item> = synchronized(buffer) { buffer.toList() }

        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
            val me = ComponentName(context, EunoNotificationListener::class.java).flattenToString()
            return flat.split(':').any { it == me }
        }
    }
}
