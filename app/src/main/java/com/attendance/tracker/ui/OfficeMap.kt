package com.attendance.tracker.ui

import android.graphics.Color as AColor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.attendance.tracker.R
import com.attendance.tracker.core.Gate
import com.attendance.tracker.core.LatLng
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

/** Esri World Imagery (satellite) — easier to see walls and gates than a street map. */
private val SatelliteTiles = object : OnlineTileSourceBase(
    "EsriWorldImagery", 0, 19, 256, "",
    arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"),
    "Imagery © Esri",
) {
    override fun getTileURLString(pMapTileIndex: Long): String =
        baseUrl + "${MapTileIndex.getZoom(pMapTileIndex)}/${MapTileIndex.getY(pMapTileIndex)}/${MapTileIndex.getX(pMapTileIndex)}"
}

private fun LatLng.gp() = GeoPoint(lat, lng)

/**
 * Interactive map used in Setup.
 *  - Tap the map → [onTap] (adds a corner or a gate)
 *  - Press & hold a corner, then drag → [onCornerMoved]
 */
@Composable
fun OfficeMap(
    modifier: Modifier,
    corners: List<LatLng>,
    gates: List<Gate>,
    editableCorners: Boolean,
    onTap: (LatLng) -> Unit,
    onCornerMoved: (Int, LatLng) -> Unit = { _, _ -> },
) {
    val ctx = LocalContext.current
    val c = Soft.colors
    var satellite by remember { mutableStateOf(true) }
    var recenter by remember { mutableIntStateOf(0) }
    val tap by rememberUpdatedState(onTap)
    val moved by rememberUpdatedState(onCornerMoved)

    val map = remember {
        MapView(ctx).apply {
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            setTilesScaledToDpi(true)
            minZoomLevel = 3.0
            maxZoomLevel = 20.0
            isHorizontalMapRepetitionEnabled = false
            isVerticalMapRepetitionEnabled = false
            controller.setZoom(4.5)
            controller.setCenter(GeoPoint(20.59, 78.96))
        }
    }
    val myLoc = remember { MyLocationNewOverlay(GpsMyLocationProvider(ctx), map) }
    val events = remember {
        MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean { tap(LatLng(p.latitude, p.longitude)); return true }
            override fun longPressHelper(p: GeoPoint): Boolean = false
        })
    }
    val copyright = remember { CopyrightOverlay(ctx) }

    // Map lifecycle
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> { map.onResume(); runCatching { myLoc.enableMyLocation() } }
                Lifecycle.Event.ON_PAUSE -> { map.onPause(); myLoc.disableMyLocation() }
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(obs)
        map.onResume(); runCatching { myLoc.enableMyLocation() }
        onDispose {
            owner.lifecycle.removeObserver(obs)
            myLoc.disableMyLocation()
            map.onPause()
            map.onDetach()
        }
    }

    LaunchedEffect(satellite) { map.setTileSource(if (satellite) SatelliteTiles else TileSourceFactory.MAPNIK) }

    // Initial camera: fit the drawn area / gates, else go to where you are.
    LaunchedEffect(Unit) {
        val pts = (corners + gates.map { LatLng(it.lat, it.lng) }).map { it.gp() }
        if (pts.size >= 2) {
            map.addOnFirstLayoutListener { _, _, _, _, _ ->
                runCatching { map.zoomToBoundingBox(BoundingBox.fromGeoPoints(pts).increaseByScale(1.6f), false) }
            }
        } else if (pts.size == 1) {
            map.controller.setZoom(18.5); map.controller.setCenter(pts[0])
        } else {
            recenter++
        }
    }
    LaunchedEffect(recenter) {
        if (recenter == 0) return@LaunchedEffect
        val here = myLoc.myLocation
        if (here != null) map.controller.animateTo(here, 18.5, 700L)
        else myLoc.runOnFirstFix { map.post { myLoc.myLocation?.let { map.controller.animateTo(it, 18.5, 700L) } } }
    }

    Box(modifier.shadow(18.dp, Soft.cardShape, ambientColor = c.shadow, spotColor = c.shadow).clip(Soft.cardShape)) {
        AndroidView(
            factory = { map },
            modifier = Modifier.fillMaxSize(),
            update = { m ->
                m.overlays.clear()
                m.overlays.add(events)
                if (corners.size >= 2) {
                    val poly = Polygon(m).apply {
                        points = corners.map { it.gp() } + corners.first().gp()
                        fillPaint.color = AColor.argb(70, 91, 108, 255)
                        outlinePaint.color = AColor.rgb(91, 108, 255)
                        outlinePaint.strokeWidth = 7f
                        setInfoWindow(null)
                        setOnClickListener { _, _, _ -> false }
                    }
                    m.overlays.add(poly)
                }
                val cornerIcon = ContextCompat.getDrawable(ctx, R.drawable.map_corner)
                corners.forEachIndexed { i, p ->
                    m.overlays.add(Marker(m).apply {
                        position = p.gp()
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        icon = cornerIcon
                        setInfoWindow(null)
                        isDraggable = editableCorners
                        setOnMarkerClickListener { _, _ -> true }
                        setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
                            override fun onMarkerDrag(marker: Marker) {}
                            override fun onMarkerDragStart(marker: Marker) {}
                            override fun onMarkerDragEnd(marker: Marker) {
                                moved(i, LatLng(marker.position.latitude, marker.position.longitude))
                            }
                        })
                    })
                }
                val gateIcon = ContextCompat.getDrawable(ctx, R.drawable.map_gate)
                gates.forEach { g ->
                    m.overlays.add(Marker(m).apply {
                        position = GeoPoint(g.lat, g.lng)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        icon = gateIcon
                        title = g.name
                        setOnMarkerClickListener { mk, _ -> mk.showInfoWindow(); true }
                    })
                }
                m.overlays.add(myLoc)
                m.overlays.add(copyright)
                m.invalidate()
            },
        )

        // Floating controls
        Column(
            Modifier.align(Alignment.TopEnd).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.End,
        ) {
            Text(
                if (satellite) "Map" else "Satellite",
                Modifier.shadow(8.dp, Soft.pillShape).clip(Soft.pillShape).background(c.card)
                    .pressScale { satellite = !satellite }.padding(horizontal = 14.dp, vertical = 9.dp),
                style = MaterialTheme.typography.labelMedium, color = c.accent,
            )
            Box(
                Modifier.size(44.dp).shadow(8.dp, CircleShape).clip(CircleShape).background(c.card).pressScale { recenter++ },
                contentAlignment = Alignment.Center,
            ) { Icon(rememberVectorPainter(Icons.Filled.LocationOn), "My location", tint = c.accent, modifier = Modifier.size(22.dp)) }
        }
    }
}
