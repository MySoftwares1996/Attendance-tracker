package com.attendance.tracker.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.attendance.tracker.update.UpdateManager
import com.attendance.tracker.update.UpdateState
import kotlinx.coroutines.launch

/** Banner shown on the Today screen while an update is available / installing. */
@Composable
fun UpdateBanner() {
    val st by UpdateManager.state.collectAsStateWithLifecycle()
    when (st) {
        is UpdateState.Available, is UpdateState.Downloading, is UpdateState.Installing ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier.fillMaxWidth()
            ) { Column(Modifier.padding(16.dp)) { UpdateControls() } }
        else -> Unit
    }
}

/** Version info + check/install controls (used on Setup and in the banner). */
@Composable
fun UpdateControls() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st by UpdateManager.state.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Installed version ${UpdateManager.currentVersionName} (build ${UpdateManager.currentVersionCode})")
        when (val s = st) {
            is UpdateState.Available -> {
                Text("Update available: v${s.info.versionName}", style = MaterialTheme.typography.titleMedium)
                if (s.info.notes.isNotBlank()) Text(s.info.notes, style = MaterialTheme.typography.bodySmall)
                Button(
                    onClick = {
                        if (!UpdateManager.canInstall(ctx)) {
                            Toast.makeText(ctx, "Allow \"Install unknown apps\" for Attendance Tracker, then tap Install again",
                                Toast.LENGTH_LONG).show()
                            UpdateManager.openInstallPermissionSettings(ctx)
                        } else {
                            scope.launch { UpdateManager.downloadAndInstall(ctx, s.info) }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Install update") }
            }
            is UpdateState.Downloading -> {
                Text("Downloading v${s.info.versionName}… ${s.percent}%")
                LinearProgressIndicator(progress = { s.percent / 100f }, modifier = Modifier.fillMaxWidth())
            }
            is UpdateState.Installing -> Text("Installing v${s.info.versionName}… confirm on the system dialog if asked.")
            is UpdateState.Checking -> Text("Checking for updates…")
            is UpdateState.UpToDate -> Text("You have the latest version.")
            is UpdateState.Failed -> Text(s.message, color = MaterialTheme.colorScheme.error)
            UpdateState.Idle -> Unit
        }
        if (st !is UpdateState.Downloading && st !is UpdateState.Installing && st !is UpdateState.Available) {
            OutlinedButton(onClick = { scope.launch { UpdateManager.check() } }, modifier = Modifier.fillMaxWidth()) {
                Text("Check for updates")
            }
        }
    }
}
