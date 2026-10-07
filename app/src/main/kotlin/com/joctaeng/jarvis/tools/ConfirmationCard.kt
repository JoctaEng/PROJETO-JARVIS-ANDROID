package com.joctaeng.jarvis.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** "Euno quer fazer isto" — [Cancelar] [Confirmar]. Ações críticas aparecem em vermelho. */
@Composable
fun ConfirmationCard(request: PendingConfirmation, modifier: Modifier = Modifier) {
    val colors = if (request.critical) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    } else {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    }
    Card(modifier.fillMaxWidth(), colors = colors) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (request.critical) "Ação importante — confirme:" else "Posso fazer isto?", style = MaterialTheme.typography.labelLarge)
            Text(request.title, style = MaterialTheme.typography.titleSmall)
            if (request.details.isNotBlank()) Text(request.details, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { request.respond(false) }) { Text("Cancelar") }
                Button(
                    onClick = { request.respond(true) },
                    colors = if (request.critical) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors(),
                ) { Text("Confirmar") }
            }
        }
    }
}
