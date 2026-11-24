package com.attendance.tracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.unit.dp
import com.attendance.tracker.core.LatLng
import com.attendance.tracker.core.MapLinkParser
import com.attendance.tracker.service.LinkResult
import com.attendance.tracker.service.MapLinkResolver
import kotlinx.coroutines.launch

/** Paste a Google Maps link (or coordinates) and get the exact point. */
@Composable
fun MapLinkDialog(title: String, initialText: String? = null, onFound: (LatLng) -> Unit, onDismiss: () -> Unit) {
    val c = Soft.colors
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(initialText ?: "") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Prefill from the clipboard if it looks like a map link.
    LaunchedEffect(Unit) {
        if (text.isBlank()) {
            val clip = clipboard.getText()?.text.orEmpty()
            if (clip.contains("maps", true) || clip.contains("goo.gl") || MapLinkParser.parse(clip) != null) text = clip.trim()
        }
    }

    fun go() {
        if (busy || text.isBlank()) return
        busy = true; error = null
        scope.launch {
            when (val r = MapLinkResolver.resolve(text)) {
                is LinkResult.Found -> onFound(r.point)
                is LinkResult.Failed -> error = r.message
            }
            busy = false
        }
    }
    // A link shared from Google Maps: resolve immediately.
    LaunchedEffect(initialText) { if (!initialText.isNullOrBlank()) go() }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "In Google Maps, open the place (or long-press to drop a pin) → Share → Copy link, then paste it here.",
                    style = MaterialTheme.typography.bodySmall, color = c.textSecondary,
                )
                OutlinedTextField(
                    value = text, onValueChange = { text = it; error = null },
                    placeholder = { Text("https://maps.app.goo.gl/…") },
                    minLines = 2, maxLines = 4,
                    shape = RoundedCornerShape(18.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = c.accent, unfocusedBorderColor = Color.Transparent,
                        focusedContainerColor = c.card, unfocusedContainerColor = c.cardMuted, cursorColor = c.accent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (busy) Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = c.accent)
                    Spacer(Modifier.width(10.dp))
                    Text("Finding location…", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                }
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.danger) }
            }
        },
        confirmButton = { TextButton(onClick = ::go, enabled = !busy && text.isNotBlank()) { Text("Go") } },
        dismissButton = {
            Row {
                TextButton(onClick = { clipboard.getText()?.text?.let { text = it.trim(); error = null } }, enabled = !busy) { Text("Paste") }
                TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") }
            }
        },
        shape = RoundedCornerShape(28.dp),
        containerColor = c.card,
    )
}
