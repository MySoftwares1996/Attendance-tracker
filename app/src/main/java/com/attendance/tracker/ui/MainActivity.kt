package com.attendance.tracker.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.attendance.tracker.AttendanceController
import com.attendance.tracker.update.UpdateManager
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(tab == 0, { tab = 0 },
                                { Icon(Icons.Filled.Home, null) }, label = { Text("Today") })
                            NavigationBarItem(tab == 1, { tab = 1 },
                                { Icon(Icons.Filled.List, null) }, label = { Text("History") })
                            NavigationBarItem(tab == 2, { tab = 2 },
                                { Icon(Icons.Filled.Settings, null) }, label = { Text("Setup") })
                        }
                    }
                ) { padding ->
                    val m = Modifier.padding(padding)
                    when (tab) {
                        0 -> DashboardScreen(m, onOpenSetup = { tab = 2 })
                        1 -> HistoryScreen(m)
                        else -> SettingsScreen(m)
                    }
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
