package com.attendance.tracker.service

import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.attendance.tracker.AttendanceController
import com.attendance.tracker.R
import com.attendance.tracker.core.WorkStatus
import com.attendance.tracker.core.WorkedTime
import java.time.LocalDate

/**
 * Quick Settings tile: shows "In office · 6h 40m" (net) and starts / ends lunch with one tap,
 * without opening the app.
 */
class AttendanceTile : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        render()
    }

    override fun onClick() {
        super.onClick()
        val st = AttendanceController.state.value
        if (st.lunch.active) AttendanceController.endLunch()
        else if (st.status == WorkStatus.IN_OFFICE || st.status == WorkStatus.OVERTIME) AttendanceController.startLunch()
        render()
    }

    private fun render() {
        val tile = qsTile ?: return
        val st = AttendanceController.state.value
        val now = System.currentTimeMillis()
        val today = LocalDate.now()
        val net = if (st.today.punches.isEmpty()) 0L else runCatching { AttendanceController.netWorkedSec(st.today.timeline, now, today) }.getOrDefault(0L)
        val h = net / 3600; val m = (net % 3600) / 60
        tile.icon = Icon.createWithResource(this, R.drawable.ic_notification)
        tile.label = when {
            st.lunch.active -> "On lunch"
            st.status == WorkStatus.IN_OFFICE -> "In office"
            st.status == WorkStatus.OVERTIME -> "Overtime"
            st.status == WorkStatus.OUTSIDE_OFFICE -> "Outside"
            else -> st.status.label
        }
        val sub = "${h}h ${"%02d".format(m)}m net" + when {
            st.lunch.active -> " · tap: back"
            st.status == WorkStatus.IN_OFFICE || st.status == WorkStatus.OVERTIME -> " · tap: lunch"
            else -> ""
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = sub else tile.label = "${tile.label} · $sub"
        tile.state = when {
            st.lunch.active -> Tile.STATE_ACTIVE
            st.status == WorkStatus.IN_OFFICE || st.status == WorkStatus.OVERTIME -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.updateTile()
    }
}
