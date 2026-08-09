package com.suteny0r.skyspyaware.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.drawable.toDrawable
import com.suteny0r.skyspyaware.Drone
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

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

@Composable
fun MapScreen(drones: List<Drone>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(12.0)
        }
    }

    val droneMarkers = remember { LinkedHashMap<String, Marker>() }
    val pilotMarkers = remember { LinkedHashMap<String, Marker>() }
    val lines = remember { LinkedHashMap<String, Polyline>() }

    AndroidView(factory = { mapView }, modifier = modifier) { _ ->
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
}
