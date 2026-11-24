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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.attendance.tracker.AttendanceController
import com.attendance.tracker.update.UpdateManager
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AttendanceTheme {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                Box(Modifier.fillMaxSize().background(Soft.colors.background)) {
                    AnimatedContent(
                        targetState = tab,
                        transitionSpec = {
                            (fadeIn(tween(320, easing = FastOutSlowInEasing)) +
                                slideInVertically(tween(320, easing = FastOutSlowInEasing)) { it / 24 }) togetherWith
                                fadeOut(tween(180))
                        },
                        label = "tabs",
                        modifier = Modifier.fillMaxSize().statusBarsPadding(),
                    ) { t ->
                        when (t) {
                            0 -> DashboardScreen(onOpenSetup = { tab = 2 })
                            1 -> HistoryScreen()
                            else -> SettingsScreen()
                        }
                    }
                    SoftNavBar(tab, { tab = it }, Modifier.align(Alignment.BottomCenter))
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AttendanceController.refresh()
        lifecycleScope.launch { UpdateManager.checkIfDue(applicationContext) }
    }
}

/** Space reserved at the bottom of every screen so content clears the floating bar. */
val NavBarClearance = 110.dp

@Composable
private fun SoftNavBar(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier) {
    val c = Soft.colors
    Row(
        modifier
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 14.dp)
            .fillMaxWidth()
            .height(68.dp)
            .shadow(24.dp, Soft.pillShape, ambientColor = c.shadow, spotColor = c.shadow)
            .clip(Soft.pillShape)
            .background(c.card)
            .padding(8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavItem(Icons.Filled.Home, "Today", selected == 0, Modifier.weight(1f)) { onSelect(0) }
        NavItem(Icons.Filled.DateRange, "History", selected == 1, Modifier.weight(1f)) { onSelect(1) }
        NavItem(Icons.Filled.Settings, "Setup", selected == 2, Modifier.weight(1f)) { onSelect(2) }
    }
}

@Composable
private fun NavItem(icon: ImageVector, label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
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
