package com.suteny0r.skyspyaware.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.drawable.toDrawable
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import com.suteny0r.skyspyaware.SatelliteAnalyzer
import com.suteny0r.skyspyaware.SkySpyViewModel
import com.suteny0r.skyspyaware.TrailPoint
import com.suteny0r.skyspyaware.YoloDetector
import kotlinx.coroutines.launch
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline

private const val SCAN_BOX_TITLE = "scan_box"
private const val SCAN_BOX_LABEL_PREFIX = "scan_label:"

/**
 * Full-history map for one drone. Loads every position fix from the database
 * and lets the user scrub a time slider across the entire span of its flights.
 */
@Composable
fun DroneFlightsScreen(vm: SkySpyViewModel, droneKey: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var trail by remember { mutableStateOf<List<TrailPoint>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var firstTs by remember { mutableStateOf(0L) }
    var maxMinutes by remember { mutableStateOf(1f) }
    var sliderMinutes by remember { mutableStateOf(0f) }
    var scanning by remember { mutableStateOf(false) }
    var scanCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var scanBoxes by remember { mutableStateOf<List<SatelliteAnalyzer.ScanBox>>(emptyList()) }

    LaunchedEffect(droneKey) {
        val t = vm.loadDroneFlights(droneKey)
        trail = t
        if (t.isNotEmpty()) {
            firstTs = t.first().ts
            val last = t.last().ts
            maxMinutes = ((last - firstTs) / 60_000.0).toFloat().coerceAtLeast(1f)
            sliderMinutes = maxMinutes
        }
        loaded = true
    }

    val mapView = remember {
        MapView(context).apply {
            setTileSource(ESRI_SAT)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        }
    }

    val cutoffTs = firstTs + (sliderMinutes * 60_000).toLong()

    /** Renders a small solid label chip with white text. */
    fun labelIcon(text: String, color: Int): Bitmap {
        val density = context.resources.displayMetrics.density
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = android.graphics.Color.WHITE
            textSize = 12f * density
            isFakeBoldText = true
        }
        val w = (textPaint.measureText(text) + 8f * density).toInt()
        val h = (textPaint.textSize + 6f * density).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(color)
        c.drawText(text, 4f * density, h - 3f * density, textPaint)
        return bmp
    }

    // Identity boxes from the latest scan, drawn as geo-anchored polygons
    // with a class/confidence label marker at the top-left corner.
    fun drawScanBoxes() {
        // Keep the existing trail overlays; drop any prior scan boxes.
        mapView.overlays.toList().forEach { ov ->
            if (ov is Polygon && ov.title == SCAN_BOX_TITLE) mapView.overlays.remove(ov)
            if (ov is Marker && ov.title?.startsWith(SCAN_BOX_LABEL_PREFIX) == true) {
                mapView.overlays.remove(ov)
            }
        }
        if (scanBoxes.isEmpty()) return
        val density = context.resources.displayMetrics.density
        for (b in scanBoxes) {
            val color = b.color
            val poly = Polygon(mapView).apply {
                title = SCAN_BOX_TITLE
                points = if (b.corners.size == 4) {
                    b.corners.map { GeoPoint(it.first, it.second) }
                } else {
                    listOf(
                        GeoPoint(b.latMin, b.lonMin),
                        GeoPoint(b.latMax, b.lonMin),
                        GeoPoint(b.latMax, b.lonMax),
                        GeoPoint(b.latMin, b.lonMax)
                    )
                }
                fillPaint.color = color and 0x40FFFFFF.toInt()
                outlinePaint.color = color
                outlinePaint.strokeWidth = 3f * density
            }
            mapView.overlays.add(poly)
            val label = "${b.label} ${(b.conf * 100).toInt()}%"
            val icon = labelIcon(label, color).toDrawable(context.resources)
            val m = Marker(mapView).apply {
                title = SCAN_BOX_LABEL_PREFIX + label
                setAnchor(Marker.ANCHOR_LEFT, Marker.ANCHOR_BOTTOM)
                position = GeoPoint(b.latMax, b.lonMin)
                this.icon = icon
                setInfoWindow(null)
            }
            mapView.overlays.add(m)
        }
        mapView.invalidate()
    }

    // Fit the camera once to the full flight footprint; never move it again
    // as the slider trims the visible trail.
    LaunchedEffect(loaded, trail) {
        if (!loaded || trail.isEmpty()) return@LaunchedEffect
        val allPts = trail.map { GeoPoint(it.lat, it.lon) }
        if (allPts.size > 1) {
            mapView.zoomToBoundingBox(BoundingBox.fromGeoPoints(allPts), false, 80)
        } else if (allPts.size == 1) {
            mapView.controller.setZoom(15.0)
            mapView.controller.setCenter(allPts[0])
        }
        mapView.invalidate()
    }

    LaunchedEffect(trail, cutoffTs, loaded) {
        if (!loaded || trail.isEmpty()) return@LaunchedEffect
        val visible = trail.filter { it.ts <= cutoffTs }
        val pts = visible.map { GeoPoint(it.lat, it.lon) }
        mapView.overlays.clear()
        if (pts.isNotEmpty()) {
            val casing = Polyline(mapView).apply {
                paint.color = 0xE6000000.toInt()
                paint.strokeWidth = 6f
            }
            casing.setPoints(pts)
            mapView.overlays.add(casing)
            val core = Polyline(mapView).apply {
                paint.color = 0xFFFFFFFF.toInt()
                paint.strokeWidth = 4f
            }
            core.setPoints(pts)
            mapView.overlays.add(core)
            val m = Marker(mapView).apply {
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                setInfoWindow(null)
            }
            m.position = pts.last()
            mapView.overlays.add(m)
        }
        drawScanBoxes()
        mapView.invalidate()
    }

    // Draw the results of a fresh scan immediately. drawScanBoxes() is
    // idempotent (it first removes any prior scan boxes), so this also
    // refreshes on repeat scans and after trail redraws cleared them.
    LaunchedEffect(scanBoxes) {
        drawScanBoxes()
    }

    DisposableEffect(Unit) {
        onDispose { mapView.onDetach() }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        Surface(
            modifier = Modifier.fillMaxWidth(),
            tonalElevation = 3.dp,
            shadowElevation = 2.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back"
                    )
                    Text("Back")
                }
                Text(
                    droneKey,
                    modifier = Modifier.padding(start = 8.dp),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1
                )
            }
        }

        if (loaded && trail.isEmpty()) {
            Surface(
                modifier = Modifier.align(Alignment.Center),
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 3.dp
            ) {
                Text(
                    "No position-tracked flights found",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        if (loaded && trail.isNotEmpty()) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 3.dp,
                shadowElevation = 2.dp
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Button(
                        onClick = {
                            if (!scanning && trail.isNotEmpty()) {
                                scanning = true
                                scope.launch {
                                    val tight = flightBounds(trail, marginM = 150.0)
                                    val wide = flightBounds(trail, marginM = 700.0)
                                    val scan =
                                        SatelliteAnalyzer.scanCombined(context, wide, tight)
                                    scanCounts = scan.counts
                                    scanBoxes = scan.boxes
                                    // Fresh object counts feed the role
                                    // heuristic; recompute statistics so the
                                    // drone's role assessment is recalculated.
                                    vm.applySatelliteScan(droneKey, scan.counts)
                                    scanning = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (scanning) "Scanning area..." else "Scan area for objects")
                    }
                    if (scanCounts.isNotEmpty()) {
                        Text(
                            scanCounts.entries
                                .sortedByDescending { it.value }
                                .joinToString("  •  ") { "${it.value} ${it.key}" },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        "History: ${formatDurationMs((sliderMinutes * 60_000).toLong())} of " +
                            "${formatDurationMs((maxMinutes * 60_000).toLong())}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = sliderMinutes,
                        onValueChange = { sliderMinutes = it },
                        valueRange = 0f..maxMinutes
                    )
                }
            }
        }
    }
}

private fun formatDurationMs(ms: Long): String {
    val totalMin = ms / 60_000
    val h = totalMin / 60
    val m = totalMin % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

/**
 * Bounding box of the full flight trail, padded by [marginM] meters on each
 * side so the scan covers objects just outside the exact flight path without
 * blowing up into the fixed ~2.2 km block the scanner uses for a single point.
 */
private fun flightBounds(
    trail: List<TrailPoint>,
    marginM: Double
): SatelliteAnalyzer.GeoBounds {
    var latMin = Double.MAX_VALUE
    var latMax = -Double.MAX_VALUE
    var lonMin = Double.MAX_VALUE
    var lonMax = -Double.MAX_VALUE
    for (p in trail) {
        if (p.lat < latMin) latMin = p.lat
        if (p.lat > latMax) latMax = p.lat
        if (p.lon < lonMin) lonMin = p.lon
        if (p.lon > lonMax) lonMax = p.lon
    }
    val latPerM = 1.0 / 111_320.0
    val lonPerM = 1.0 / (111_320.0 * Math.cos(Math.toRadians((latMin + latMax) / 2.0)))
    val padLat = (latMax - latMin) * 0.15 + latPerM * marginM
    val padLon = (lonMax - lonMin) * 0.15 + lonPerM * marginM
    return SatelliteAnalyzer.GeoBounds(
        latMin = latMin - padLat,
        lonMin = lonMin - padLon,
        latMax = latMax + padLat,
        lonMax = lonMax + padLon
    )
}
