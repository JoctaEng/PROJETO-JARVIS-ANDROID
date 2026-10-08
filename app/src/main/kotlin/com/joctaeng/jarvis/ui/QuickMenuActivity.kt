package com.joctaeng.jarvis.ui

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.joctaeng.jarvis.JarvisApp
import com.joctaeng.jarvis.overlay.OverlayService

/** Menu do toque longo no personagem: abrir Meu Euno ou fechar o Euno por completo (não só recolher). */
class QuickMenuActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = JarvisApp.from(this)
        setContent {
            JarvisTheme {
                AlertDialog(
                    onDismissRequest = ::finish,
                    title = { Text(app.settings.displayName) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick = {
                                packageManager.getLaunchIntentForPackage(packageName)?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                finish()
                            }, modifier = Modifier.fillMaxWidth()) { Text("Abrir Meu Euno") }
                            Button(
                                onClick = { closeEverything(this@QuickMenuActivity) },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Fechar o Euno por completo") }
                            Text(
                                "Para de ouvir e de falar, tira o personagem da tela e encerra o app. Para voltar, abra o Euno pelo ícone.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    },
                    confirmButton = {},
                    dismissButton = { TextButton(onClick = ::finish) { Text("Cancelar") } },
                )
            }
        }
    }

    companion object {
        fun intent(context: Context): Intent =
            Intent(context, QuickMenuActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

        /** Encerra tudo: voz, conversa, escuta do chamado, personagem (serviço) e todas as janelas; por fim o processo. */
        fun closeEverything(context: Context) {
            val app = JarvisApp.from(context)
            app.events.info("app", "fechar por completo pedido pelo usuário (toque longo)")
            runCatching { app.conversation.cancel() }
            runCatching { app.voice.stop() }
            OverlayService.stop(context)
            runCatching {
                context.getSystemService(ActivityManager::class.java).appTasks.forEach { it.finishAndRemoveTask() }
            }
            // Dá tempo do serviço sair do primeiro plano e do registro gravar; então encerra o processo.
            Handler(Looper.getMainLooper()).postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 700)
        }
    }
}
