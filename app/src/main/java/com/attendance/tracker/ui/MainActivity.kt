package com.attendance.tracker.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.attendance.tracker.AttendanceController
import com.attendance.tracker.update.UpdateManager
import com.attendance.tracker.permissions.PermissionStatus
import com.attendance.tracker.permissions.PermissionsManager
import com.attendance.tracker.service.TrackingService
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleShare(intent)
        setContent {
            AttendanceTheme {
                val ctx = LocalContext.current
                var perm by remember { mutableStateOf<PermissionStatus?>(null) }
                var refresh by remember { mutableIntStateOf(0) }
                var onboarded by remember { mutableStateOf(AttendanceController.settings.onboarded) }
                LifecycleResumeEffect(Unit) {
                    refresh++
                    onPauseOrDispose { }
                }
                LaunchedEffect(refresh) {
                    val p = PermissionsManager.status(ctx)
                    perm = p
                    autoStartTracking(p)
                }
                val gate = perm?.let { !it.requiredOk || !onboarded } ?: true
                AnimatedContent(
                    targetState = gate,
                    transitionSpec = { fadeIn(tween(400)) togetherWith fadeOut(tween(250)) },
                    label = "gate",
                    modifier = Modifier.fillMaxSize().background(Soft.colors.background),
                ) { showGate ->
                    if (showGate) {
                        val p = perm
                        if (p != null) {
                            Box(Modifier.statusBarsPadding()) {
                                PermissionsScreen(
                                    status = p,
                                    onChanged = { refresh++ },
                                    onContinue = {
                                        AttendanceController.settings.onboarded = true
                                        onboarded = true
                                        refresh++
                                    },
                                )
                            }
                        }
                    } else {
                        MainContent()
                    }
                }
            }
        }
    }

    /** Starts tracking automatically once everything is allowed and the office is set up. */
    private fun autoStartTracking(p: PermissionStatus) {
        val s = AttendanceController.settings
        if (p.requiredOk && s.settings.value.office != null && !s.trackingEnabled && !s.userPaused) {
            runCatching { TrackingService.start(this) }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** Google Maps → Share → Attendance Tracker. */
    private fun handleShare(intent: android.content.Intent?) {
        if (intent?.action != android.content.Intent.ACTION_SEND) return
        val text = intent.getStringExtra(android.content.Intent.EXTRA_TEXT) ?: return
        UiChrome.sharedTarget = null
        UiChrome.sharedLink = text
    }

    override fun onResume() {
        super.onResume()
        AttendanceController.refresh()
        lifecycleScope.launch { UpdateManager.checkIfDue(applicationContext) }
    }
}

@Composable
private fun MainContent() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // A shared Google Maps location opens Setup.
    LaunchedEffect(UiChrome.sharedLink) { if (UiChrome.sharedLink != null) tab = 2 }
    LaunchedEffect(UiChrome.requestTab) { UiChrome.requestTab?.let { tab = it; UiChrome.requestTab = null } }
    Box(Modifier.fillMaxSize().background(Soft.colors.background)) {
        AnimatedContent(
            targetState = tab,
            transitionSpec = {
                (fadeIn(tween(320, easing = FastOutSlowInEasing)) +
                    slideInVertically(tween(320, easing = FastOutSlowInEasing)) { it / 24 }) togetherWith
                    fadeOut(tween(180))
            },
            label = "tabs",
            modifier = if (UiChrome.fullScreen) Modifier.fillMaxSize() else Modifier.fillMaxSize().statusBarsPadding(),
        ) { t ->
            when (t) {
                0 -> DashboardScreen(onOpenSetup = { tab = 2 })
                1 -> HistoryScreen()
                3 -> InsightsScreen(onOpenSetup = { page -> if (page == "salary") tab = 4 else { UiChrome.setupPage = page; tab = 2 } })
                4 -> SalaryScreen()
                else -> SettingsScreen()
            }
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = !UiChrome.fullScreen,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + androidx.compose.animation.slideOutVertically { it },
        ) {
            SoftNavBar(tab, { tab = it }, Modifier)
        }
    }
}

/** Lets a screen go full screen (hides the bottom tabs, draws under the status bar). */
object UiChrome {
    var fullScreen by mutableStateOf(false)
    /** Text shared into the app from Google Maps (Share → Attendance Tracker). */
    var sharedLink by mutableStateOf<String?>(null)
    /** Ask the app to switch to a bottom tab (0 Today, 1 History, 2 Setup, 3 Insights, 4 Salary). */
    var requestTab by mutableStateOf<Int?>(null)
    /** Setup page to open next ("check"), e.g. from the health notice on Today. */
    var setupPage by mutableStateOf<String?>(null)
    /** "office" or "gate" once the user picked what the shared location is for. */
    var sharedTarget by mutableStateOf<String?>(null)
}

/** Space reserved at the bottom of every screen so content clears the floating bar. */
val NavBarClearance = 110.dp

@Composable
private fun SoftNavBar(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier) {
    val c = Soft.colors
    Row(
        modifier
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 14.dp)
            .fillMaxWidth()
            .height(68.dp)
            .shadow(24.dp, Soft.pillShape, ambientColor = c.shadow, spotColor = c.shadow)
            .clip(Soft.pillShape)
            .background(c.card)
            .padding(8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavItem(rememberVectorPainter(Icons.Filled.Home), "Today", selected == 0, Modifier.weight(if (selected == 0) 2f else 1f)) { onSelect(0) }
        NavItem(rememberVectorPainter(Icons.Filled.DateRange), "History", selected == 1, Modifier.weight(if (selected == 1) 2f else 1f)) { onSelect(1) }
        NavItem(rememberVectorPainter(Icons.Filled.Star), "Insights", selected == 3, Modifier.weight(if (selected == 3) 2f else 1f)) { onSelect(3) }
        NavItem(androidx.compose.ui.res.painterResource(com.attendance.tracker.R.drawable.ic_rupee), "Salary", selected == 4, Modifier.weight(if (selected == 4) 2f else 1f)) { onSelect(4) }
        NavItem(rememberVectorPainter(Icons.Filled.Settings), "Setup", selected == 2, Modifier.weight(if (selected == 2) 2f else 1f)) { onSelect(2) }
    }
}

@Composable
private fun NavItem(icon: androidx.compose.ui.graphics.painter.Painter, label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Soft.colors
    val bg = animatedColor(if (selected) c.accentSoft else c.card, "navBg")
    val fg = animatedColor(if (selected) c.accent else c.textSecondary, "navFg")
    Row(
        modifier
            .height(52.dp)
            .clip(Soft.pillShape)
            .background(bg)
            .pressScale(onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, label, tint = fg, modifier = Modifier.size(22.dp))
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Text(label, color = fg, style = MaterialTheme.typography.labelLarge)
        }
    }
}
