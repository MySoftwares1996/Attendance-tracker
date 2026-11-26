package com.attendance.tracker.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.attendance.tracker.R
import com.attendance.tracker.update.UpdateManager
import com.attendance.tracker.update.UpdateState
import kotlinx.coroutines.launch

/** Soft banner on the Today screen while an update is available / installing. */
@Composable
fun UpdateBanner() {
    val st by UpdateManager.state.collectAsStateWithLifecycle()
    val visible = st is UpdateState.Available || st is UpdateState.Downloading || st is UpdateState.Installing
    AnimatedVisibility(visible, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        SoftCard(color = Soft.colors.accentSoft) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBubble(painterResource(R.drawable.ic_system_update), Soft.colors.card, Soft.colors.accent)
                Spacer(Modifier.width(14.dp))
                Text("New version ready", style = MaterialTheme.typography.titleMedium, color = Soft.colors.textPrimary)
            }
            Spacer(Modifier.height(12.dp))
            UpdateControls(showVersion = false)
        }
    }
}

/** Version info + check/install controls. */
@Composable
fun UpdateControls(showVersion: Boolean = true) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = Soft.colors
    val st by UpdateManager.state.collectAsStateWithLifecycle()
    Column {
        if (showVersion) {
            InfoLine("Installed", "v${UpdateManager.currentVersionName}")
            val installedNotes = UpdateManager.notesFor(androidx.compose.ui.platform.LocalContext.current, UpdateManager.currentVersionCode)
            if (installedNotes.isNotBlank()) {
                var showNotes by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                MiniAction(if (showNotes) "Hide what's new" else "What's new in this version") { showNotes = !showNotes }
                if (showNotes) Text(installedNotes, Modifier.padding(vertical = 6.dp), style = MaterialTheme.typography.bodySmall, color = c.textPrimary)
            }
        }
        AnimatedContent(st, transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(150)) }, label = "upd",
            contentKey = { it::class }) { s ->
            Column {
                when (s) {
                    is UpdateState.Available -> {
                        InfoLine("Available", "v${s.info.versionName}", c.accent)
                        if (s.info.notes.isNotBlank()) {
                            Text(s.info.notes, Modifier.padding(vertical = 6.dp), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                        }
                        Spacer(Modifier.height(8.dp))
                        SoftButton("Install update", {
                            if (!UpdateManager.canInstall(ctx)) {
                                Toast.makeText(ctx, "Allow “Install unknown apps”, then tap Install again", Toast.LENGTH_LONG).show()
                                UpdateManager.openInstallPermissionSettings(ctx)
                            } else scope.launch { UpdateManager.downloadAndInstall(ctx, s.info) }
                        }, icon = painterResource(R.drawable.ic_system_update))
                    }
                    is UpdateState.Downloading -> {
                        InfoLine("Downloading v${s.info.versionName}", "${s.percent}%", c.accent)
                        SoftBar(s.percent / 100f, c.accent, c.card, Modifier.padding(top = 6.dp))
                    }
                    is UpdateState.Installing -> Text(
                        "Installing… confirm on the system prompt if asked.",
                        style = MaterialTheme.typography.bodyMedium, color = c.textPrimary,
                    )
                    is UpdateState.Checking -> InfoLine("Status", "Checking…", c.textSecondary)
                    is UpdateState.UpToDate -> InfoLine("Status", "Up to date ✓", c.success)
                    is UpdateState.Failed -> Text(
                        s.message, Modifier.padding(vertical = 6.dp),
                        style = MaterialTheme.typography.bodySmall, color = c.danger,
                    )
                    UpdateState.Idle -> Unit
                }
                if (showVersion && s !is UpdateState.Downloading && s !is UpdateState.Installing && s !is UpdateState.Available) {
                    Spacer(Modifier.height(10.dp))
                    SoftGhostButton("Check for updates", { scope.launch { UpdateManager.check() } },
                        icon = rememberVectorPainter(Icons.Filled.Refresh))
                }
            }
        }
    }
}
