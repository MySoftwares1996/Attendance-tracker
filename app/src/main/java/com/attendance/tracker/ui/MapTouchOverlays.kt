package com.attendance.tracker.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.view.MotionEvent
import com.attendance.tracker.core.LatLng
import com.attendance.tracker.core.Simplify
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polygon
import kotlin.math.hypot

/**
 * Draw mode: the map stops moving and you trace the office outline with one finger.
 * On release the stroke is simplified to a few clean corners.
 */
class FreehandOverlay(private val density: Float, private val onDone: (List<LatLng>) -> Unit) : Overlay() {
    var active = false
    private val pts = mutableListOf<PointF>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 5f * density
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = 0xFF5B6CFF.toInt()
    }

    override fun draw(c: Canvas, map: MapView, shadow: Boolean) {
        if (shadow || pts.size < 2) return
        val p = Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) } }
        c.drawPath(p, paint)
    }

    override fun onTouchEvent(e: MotionEvent, map: MapView): Boolean {
        if (!active) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pts.clear(); pts += PointF(e.x, e.y) }
            MotionEvent.ACTION_MOVE -> { for (i in 0 until e.historySize) pts += PointF(e.getHistoricalX(i), e.getHistoricalY(i)); pts += PointF(e.x, e.y) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val simple = Simplify.outline(pts.map { Simplify.P(it.x.toDouble(), it.y.toDouble()) }, 14.0 * density, maxCorners = 10)
                val proj = map.projection
                val geo = simple.map { val g = proj.fromPixels(it.x.toInt(), it.y.toInt()); LatLng(g.latitude, g.longitude) }
                pts.clear()
                if (geo.size >= 3) onDone(geo)
            }
        }
        map.invalidate()
        return true // consume: the map doesn't pan while drawing
    }
}

/**
 * Lets you drag a corner straight away (no press-and-hold). Touches that don't start on a
 * corner fall through, so the map still pans and zooms normally.
 */
class CornerDragOverlay(private val density: Float, private val onMoved: (Int, LatLng) -> Unit) : Overlay() {
    var active = false
    var markers: List<Marker> = emptyList()
    var polygon: Polygon? = null
    private var dragging = -1

    override fun onTouchEvent(e: MotionEvent, map: MapView): Boolean {
        if (!active) return false
        val proj = map.projection
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = -1
                var best = 32f * density
                markers.forEachIndexed { i, m ->
                    val p = proj.toPixels(m.position, null)
                    val d = hypot((p.x - e.x).toDouble(), (p.y - e.y).toDouble()).toFloat()
                    if (d < best) { best = d; dragging = i }
                }
                return dragging >= 0
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging < 0) return false
                if (e.pointerCount > 1) { dragging = -1; return false }
                val g = proj.fromPixels(e.x.toInt(), e.y.toInt()) as GeoPoint
                markers[dragging].position = g
                polygon?.let { poly -> poly.points = markers.map { it.position } + markers.first().position }
                map.invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging < 0) return false
                val g = markers[dragging].position
                val i = dragging
                dragging = -1
                onMoved(i, LatLng(g.latitude, g.longitude))
                return true
            }
        }
        return false
    }
}
