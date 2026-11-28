package com.attendance.tracker.ui

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.attendance.tracker.AttendanceController
import com.attendance.tracker.backup.BackupManager
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** First launch: start fresh, or bring everything back from a backup file. */
object Welcome {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("welcome", Context.MODE_PRIVATE)

    /** Shown only on a brand-new install: not set up yet and no records on the phone. */
    fun needed(ctx: Context): Boolean =
        !prefs(ctx).getBoolean("done", false) && !AttendanceController.settings.onboarded &&
            AttendanceController.settings.settings.value.office == null &&
            AttendanceController.db.counts()["events"] == 0

    fun done(ctx: Context) = prefs(ctx).edit().putBoolean("done", true).apply()
}

@Composable
fun WelcomeScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val c = Soft.colors
    var pending by remember { mutableStateOf<Uri?>(null) }
    var busy by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) pending = u }

    Column(
        Modifier.fillMaxSize().background(c.background).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Welcome 👋", style = MaterialTheme.typography.headlineMedium, color = c.textPrimary)
        Text("Automatic office attendance from your location and gates. How would you like to begin?",
            style = MaterialTheme.typography.bodyLarge, color = c.textSecondary)
        Spacer(Modifier.height(8.dp))

        SoftCard(onClick = { Welcome.done(ctx); onDone() }) {
            Text("✨  Start fresh", style = MaterialTheme.typography.titleLarge, color = c.textPrimary)
            Spacer(Modifier.height(4.dp))
            Text("New setup: allow location, draw your office and gates, set hours and lunch.",
                style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
        }

        SoftCard(onClick = { pick.launch(arrayOf("application/json", "application/octet-stream", "*/*")) }) {
            Text("♻️  Restore from backup", style = MaterialTheme.typography.titleLarge, color = c.textPrimary)
            Spacer(Modifier.height(4.dp))
            Text("Pick your backup file (e.g. attendance-backup.json on Google Drive). Your history, office map, gates, " +
                "salary, leave and all settings come back exactly as they were.",
                style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
        }

        if (busy) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally), color = c.accent)
        Text("You'll still be asked to allow location afterwards — Android never restores permissions from a backup.",
            style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
    }

    pending?.let { u ->
        val info = remember(u) { BackupManager.inspect(ctx, u) }
        val f = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a")
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(if (info.isSuccess) "Restore this backup?" else "Can't use this file") },
            text = {
                info.fold({ i ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        InfoLine("Made on", if (i.createdAt > 0) Instant.ofEpochMilli(i.createdAt).atZone(ZoneId.systemDefault()).format(f) else "—")
                        InfoLine("Records", "${i.events}")
                        InfoLine("Day marks", "${i.marks}")
                        InfoLine("Office map", if (i.hasOffice) "Yes · ${i.gates} gate${if (i.gates == 1) "" else "s"}" else "No")
                        InfoLine("Checksum", if (i.verified) "Verified ✓" else "Older format", if (i.verified) c.success else c.warning)
                    }
                }, { e -> Text(e.message ?: "This file can't be used.") })
            },
            confirmButton = {
                if (info.isSuccess) TextButton(onClick = {
                    busy = true
                    val err = BackupManager.restore(ctx, u)
                    busy = false; pending = null
                    if (err == null) {
                        Welcome.done(ctx)
                        Toast.makeText(ctx, "Restored ✓ everything verified", Toast.LENGTH_LONG).show()
                        onDone()
                    } else Toast.makeText(ctx, err, Toast.LENGTH_LONG).show()
                }) { Text("Restore") }
                else TextButton(onClick = { pending = null }) { Text("Choose another") }
            },
            dismissButton = { if (info.isSuccess) TextButton(onClick = { pending = null }) { Text("Cancel") } },
            shape = RoundedCornerShape(28.dp), containerColor = c.card,
        )
    }
}
