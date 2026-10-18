/*
 * ArchiveTune (2026)
 * Source Logs Dialog for inspecting live source resolution responses & debugging.
 */

package moe.rukamori.archivetune.ui.component

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.utils.SourceDebugLogger

@Composable
fun SourceLogsDialog(
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var logsText by remember { mutableStateOf(SourceDebugLogger.readLogs(context)) }

    DefaultDialog(
        onDismiss = onDismiss,
        buttons = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = {
                        SourceDebugLogger.clearLogs(context)
                        logsText = SourceDebugLogger.readLogs(context)
                        Toast.makeText(context, "Logs cleared", Toast.LENGTH_SHORT).show()
                    },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Icon(painterResource(R.drawable.delete), null)
                    Spacer(Modifier.width(4.dp))
                    Text("Clear")
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Source Debug Logs", logsText))
                        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                    },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Icon(painterResource(R.drawable.copy), null)
                    Spacer(Modifier.width(4.dp))
                    Text("Copy")
                }
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = onDismiss,
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text("Close")
                }
            }
        },
    ) {
        Column(modifier = Modifier.padding(top = 4.dp)) {
            Text(
                text = "Source Resolution Logs",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Text(
                text = "Path: ${SourceDebugLogger.getLogFilePath(context)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            val verticalScroll = rememberScrollState()
            val horizontalScroll = rememberScrollState()

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp)
                    .verticalScroll(verticalScroll)
                    .horizontalScroll(horizontalScroll),
            ) {
                Text(
                    text = logsText,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
