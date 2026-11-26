package com.attendance.tracker.ui

import android.app.Activity
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.attendance.tracker.AttendanceController

/** Fingerprint / screen-lock gate for Salary and Backup. Unlocked for 5 minutes at a time. */
object AppLock {
    private var unlockedUntil by mutableLongStateOf(0L)

    val locked: Boolean get() = AttendanceController.settings.appLock && System.currentTimeMillis() > unlockedUntil

    fun prompt(activity: Activity, onDone: (Boolean) -> Unit = {}) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) { unlockedUntil = System.currentTimeMillis() + 5 * 60_000; onDone(true); return }
        val b = BiometricPrompt.Builder(activity).setTitle("Unlock Attendance").setSubtitle("Salary and backup are protected")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            b.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION") b.setDeviceCredentialAllowed(true)
        } else {
            b.setNegativeButton("Cancel", activity.mainExecutor) { _, _ -> onDone(false) }
        }
        runCatching {
            b.build().authenticate(CancellationSignal(), activity.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    unlockedUntil = System.currentTimeMillis() + 5 * 60_000; onDone(true)
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { onDone(false) }
            })
        }.onFailure {
            // No fingerprint or screen lock set up on the phone: don't lock the user out.
            unlockedUntil = System.currentTimeMillis() + 5 * 60_000; onDone(true)
        }
    }
}

/** Shown instead of a protected screen until unlocked. */
@Composable
fun LockedCard(what: String) {
    val activity = LocalContext.current as? Activity
    val c = Soft.colors
    SoftCard {
        Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🔒", style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(8.dp))
            Text("$what is locked", style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
            Text("Use your fingerprint or screen lock to open it.", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            Spacer(Modifier.height(12.dp))
            SoftButton("Unlock", { activity?.let { AppLock.prompt(it) } })
        }
    }
}
