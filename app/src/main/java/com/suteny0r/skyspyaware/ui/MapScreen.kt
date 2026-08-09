package com.suteny0r.skyspyaware.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.drawable.toDrawable
import com.suteny0r.skyspyaware.Drone
import com.suteny0r.skyspyaware.LocationController
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

private const val INITIAL_ZOOM = 14.0

// ESRI World Imagery satellite tiles - free, no API key required.
private val ESRI_SAT = XYTileSource(
    "ESRI_World_Imagery",
    0, 19, 256, ".jpg",
    arrayOf(
        "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"
    )
)

private object IconCache {
    fun drone(altitude: Int): Bitmap =
        circle(altitudeColor(altitude), 24, 10)

    fun pilot(): Bitmap =
        circle(0xCC00BCD4.toInt(), 20, 8)

    private fun altitudeColor(alt: Int): Int = when {
        alt < 0 -> 0xFFCE93D8.toInt()          // underground/negative
        alt < 50 -> 0xFF00C853.toInt()         // green
        alt < 150 -> 0xFFFFEB3B.toInt()        // yellow
        alt < 400 -> 0xFFFF9800.toInt()        // orange
        else -> 0xFFF44336.toInt()             // red
    }

    private fun circle(color: Int, size: Int, radius: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
        }
        canvas.drawCircle(size / 2f, size / 2f, radius.toFloat(), p)
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        canvas.drawCircle(size / 2f, size / 2f, radius.toFloat() - 1.5f, border)
        return bmp
    }
}

/**
 * Creates the map once (hoisted at app level) so camera position and zoom
 * survive tab switches. Only recreated if the context changes.
 */
@Composable
fun rememberSkyMapView(): MapView {
    val context = LocalContext.current
    return remember(context) {
        MapView(context).apply {
            setTileSource(ESRI_SAT)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(INITIAL_ZOOM)
        }
    }
}

@Composable
fun MapScreen(
    mapView: MapView,
    drones: List<Drone>,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val droneMarkers = remember { LinkedHashMap<String, Marker>() }
    val pilotMarkers = remember { LinkedHashMap<String, Marker>() }
    val lines = remember { LinkedHashMap<String, Polyline>() }
    val centered = remember { mutableStateOf(false) }
    val myLocation by LocationController.location.collectAsState()

    // Center on the device location once, at ~5km range.
    LaunchedEffect(myLocation) {
        val loc = myLocation
        if (loc != null && !centered.value) {
            mapView.controller.setCenter(loc)
            mapView.controller.setZoom(INITIAL_ZOOM)
            centered.value = true
        }
    }

    Box(modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize()) { _ ->
            val keys = drones.map { it.key }
            (droneMarkers.keys - keys).forEach { key ->
                droneMarkers.remove(key)?.let { mapView.overlays.remove(it) }
                pilotMarkers.remove(key)?.let { mapView.overlays.remove(it) }
                lines.remove(key)?.let { mapView.overlays.remove(it) }
            }

            for (d in drones) {
                val dm = droneMarkers.getOrPut(d.key) {
                    Marker(mapView).apply {
                        icon = IconCache.drone(d.droneAltitude)
                            .toDrawable(context.resources)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        mapView.overlays.add(this)
                    }
                }
                dm.position = GeoPoint(d.droneLat, d.droneLon)
                dm.title = d.basicId.ifBlank { d.mac }
                dm.snippet = "alt ${d.droneAltitude}m  RSSI ${d.rssi}  MAC ${d.mac}"

                if (d.pilotLat != 0.0 || d.pilotLon != 0.0) {
                    val pm = pilotMarkers.getOrPut(d.key) {
                        Marker(mapView).apply {
                            icon = IconCache.pilot().toDrawable(context.resources)
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            title = "Pilot"
                            mapView.overlays.add(this)
                        }
                    }
                    pm.position = GeoPoint(d.pilotLat, d.pilotLon)
                    val line = lines.getOrPut(d.key) {
                        Polyline(mapView).apply {
                            paint.color = 0xCC00BCD4.toInt()
                            paint.strokeWidth = 4f
                            mapView.overlays.add(this)
                        }
                    }
                    line.setPoints(
                        listOf(
                            GeoPoint(d.droneLat, d.droneLon),
                            GeoPoint(d.pilotLat, d.pilotLon)
                        )
                    )
                } else {
                    pilotMarkers.remove(d.key)?.let { mapView.overlays.remove(it) }
                    lines.remove(d.key)?.let { mapView.overlays.remove(it) }
                }
            }
            mapView.invalidate()
        }

        FloatingActionButton(
            onClick = {
                LocationController.refresh(context)
                LocationController.location.value?.let {
                    mapView.controller.setCenter(it)
                }
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            Icon(Icons.Filled.MyLocation, contentDescription = "Center on my location")
        }
    }
}
