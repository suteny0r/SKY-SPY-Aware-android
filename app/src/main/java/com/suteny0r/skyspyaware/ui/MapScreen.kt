package com.suteny0r.skyspyaware.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.drawable.toDrawable
import com.suteny0r.skyspyaware.Drone
import com.suteny0r.skyspyaware.HISTORY_SCALE_ALL
import com.suteny0r.skyspyaware.LocationController
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

private const val INITIAL_ZOOM = 14.0
private const val STALE_MS = 30_000L

/** Snapshot of the map camera, saved across tab switches. */
data class MapCamera(val lat: Double, val lon: Double, val zoom: Double)

// ESRI World Imagery satellite tiles - free, no API key required.
// osmdroid's XYTileSource appends /z/x/y, but ESRI expects /z/y/x, so we
// build the URL ourselves.
class EsriSatelliteTileSource : OnlineTileSourceBase(
    "ESRI_World_Imagery",
    0, 19, 256,
    ".jpg",
    arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile")
) {
    override fun getTileURLString(pMapTileIndex: Long): String =
        getBaseUrl() + "/" +
            MapTileIndex.getZoom(pMapTileIndex) + "/" +
            MapTileIndex.getY(pMapTileIndex) + "/" +
            MapTileIndex.getX(pMapTileIndex) + mImageFilenameEnding
}

val ESRI_SAT = EsriSatelliteTileSource()

/** Selectable base map styles. */
val MAP_STYLES: List<ITileSource> = listOf(
    ESRI_SAT,
    TileSourceFactory.MAPNIK
)
val MAP_STYLE_NAMES = listOf("Satellite", "Street")

private fun altitudeBand(alt: Int): String = when {
    alt < 0 -> "u"
    alt < 50 -> "g"
    alt < 150 -> "y"
    alt < 400 -> "o"
    else -> "r"
}

private fun altitudeColor(alt: Int): Int = when {
    alt < 0 -> 0xFFCE93D8.toInt()          // underground/negative
    alt < 50 -> 0xFF00C853.toInt()         // green
    alt < 150 -> 0xFFFFEB3B.toInt()        // yellow
    alt < 400 -> 0xFFFF9800.toInt()        // orange
    else -> 0xFFF44336.toInt()             // red
}

/**
 * Larger, higher-contrast map icons. Drone: colored altitude badge with a
 * quadcopter glyph. Pilot: cyan badge with a person glyph. Both are sized in
 * dp so they render consistently across screen densities. Stale drones (no
 * update in [STALE_MS]) render dimmed.
 */
private object IconCache {
    private val bitmaps = HashMap<String, Bitmap>()

    fun drone(ctx: Context, altitude: Int, stale: Boolean): Bitmap {
        val key = (if (stale) "s:" else "") + altitudeBand(altitude) + ":" +
            ctx.resources.displayMetrics.density
        return bitmaps.getOrPut(key) {
            drawBadge(ctx, altitudeColor(altitude), stale, 38f) { c, s, p ->
                drawDroneGlyph(c, s, p)
            }
        }
    }

    fun pilot(ctx: Context, stale: Boolean): Bitmap {
        val key = (if (stale) "ps:" else "p:") + ctx.resources.displayMetrics.density
        return bitmaps.getOrPut(key) {
            drawBadge(ctx, 0xFF00BCD4.toInt(), stale, 32f) { c, s, p ->
                drawPilotGlyph(c, s, p)
            }
        }
    }

    private fun drawBadge(
        ctx: Context, color: Int, stale: Boolean, sizeDp: Float,
        glyph: (Canvas, Float, Paint) -> Unit
    ): Bitmap {
        val density = ctx.resources.displayMetrics.density
        val px = (sizeDp * density).toInt().coerceAtLeast(24)
        val s = px.toFloat()
        val c = s / 2f
        val r = c - 2f
        val alpha = if (stale) 96 else 255

        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = 0x66000000.toInt()
        }
        canvas.drawCircle(c + s * 0.03f, c + s * 0.04f, r, shadow)

        val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            this.alpha = alpha
        }
        canvas.drawCircle(c, c, r, disc)

        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = if (stale) 2.5f * density else 3.5f * density
            this.alpha = alpha
        }
        canvas.drawCircle(c, c, r - ring.strokeWidth / 2f, ring)

        val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            this.alpha = alpha
        }
        glyph(canvas, s, glyphPaint)
        return bmp
    }

    /** Quadcopter viewed from above: hub, four arms, four rotors. */
    private fun drawDroneGlyph(canvas: Canvas, s: Float, p: Paint) {
        val c = s / 2f
        val u = s / 2f
        val arm = Paint(p).apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.11f * u
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawLine(c - 0.34f * u, c - 0.34f * u, c, c, arm)
        canvas.drawLine(c + 0.34f * u, c - 0.34f * u, c, c, arm)
        canvas.drawLine(c - 0.34f * u, c + 0.34f * u, c, c, arm)
        canvas.drawLine(c + 0.34f * u, c + 0.34f * u, c, c, arm)

        val rotor = Paint(p).apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.08f * u
        }
        val rr = 0.15f * u
        canvas.drawCircle(c - 0.34f * u, c - 0.34f * u, rr, rotor)
        canvas.drawCircle(c + 0.34f * u, c - 0.34f * u, rr, rotor)
        canvas.drawCircle(c - 0.34f * u, c + 0.34f * u, rr, rotor)
        canvas.drawCircle(c + 0.34f * u, c + 0.34f * u, rr, rotor)

        canvas.drawCircle(c, c, 0.11f * u, Paint(p).apply { style = Paint.Style.FILL })
    }

    /** Person silhouette: head + shoulders. */
    private fun drawPilotGlyph(canvas: Canvas, s: Float, p: Paint) {
        val c = s / 2f
        val u = s / 2f
        val fill = Paint(p).apply { style = Paint.Style.FILL }
        canvas.drawCircle(c, c - 0.22f * u, 0.15f * u, fill)
        val rect = RectF(
            c - 0.30f * u, c - 0.02f * u,
            c + 0.30f * u, c + 0.48f * u
        )
        canvas.drawArc(rect, 180f, 180f, true, fill)
    }
}

private fun historyLabel(mins: Int): String = when {
    mins <= 0 -> "live only"
    mins % (24 * 60) == 0 -> "${mins / (24 * 60)}d"
    mins < 60 -> "${mins}m"
    mins % 60 == 0 -> "${mins / 60}h"
    else -> "${mins / 60}h ${mins % 60}m"
}

/** Quadcopter glyph for the "center on activity" button. */
@Composable
private fun DroneGlyph(modifier: Modifier) {
    val color = LocalContentColor.current
    Canvas(modifier) {
        val c = size.minDimension / 2f
        val u = size.minDimension / 2f
        drawLine(
            color, Offset(c - 0.34f * u, c - 0.34f * u), Offset(c, c),
            strokeWidth = 0.11f * u, cap = StrokeCap.Round
        )
        drawLine(
            color, Offset(c + 0.34f * u, c - 0.34f * u), Offset(c, c),
            strokeWidth = 0.11f * u, cap = StrokeCap.Round
        )
        drawLine(
            color, Offset(c - 0.34f * u, c + 0.34f * u), Offset(c, c),
            strokeWidth = 0.11f * u, cap = StrokeCap.Round
        )
        drawLine(
            color, Offset(c + 0.34f * u, c + 0.34f * u), Offset(c, c),
            strokeWidth = 0.11f * u, cap = StrokeCap.Round
        )
        val rr = 0.15f * u
        val rotor = Stroke(width = 0.08f * u)
        drawCircle(color, radius = rr, center = Offset(c - 0.34f * u, c - 0.34f * u), style = rotor)
        drawCircle(color, radius = rr, center = Offset(c + 0.34f * u, c - 0.34f * u), style = rotor)
        drawCircle(color, radius = rr, center = Offset(c - 0.34f * u, c + 0.34f * u), style = rotor)
        drawCircle(color, radius = rr, center = Offset(c + 0.34f * u, c + 0.34f * u), style = rotor)
        drawCircle(color, radius = 0.11f * u, center = Offset(c, c), style = Fill)
    }
}

@Composable
fun MapScreen(
    savedCamera: MapCamera?,
    onCameraChange: (MapCamera) -> Unit,
    drones: List<Drone>,
    allDrones: List<Drone>,
    mapStyle: Int,
    onStyleChange: (Int) -> Unit,
    onDroneSelected: (String) -> Unit,
    focusKey: String?,
    focusTick: Int,
    historyMinutes: Int,
    onHistoryChange: (Int) -> Unit,
    historyScale: String,
    historyMaxMinutes: Int,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // Fresh MapView per tab visit - reusing a detached osmdroid MapView
    // crashes (overlay repository goes null). Camera is restored from
    // [savedCamera] instead.
    val mapView = remember(context) {
        MapView(context).apply {
            setTileSource(MAP_STYLES[mapStyle.coerceIn(0, MAP_STYLES.lastIndex)])
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(INITIAL_ZOOM)
        }
    }

    // Switch base map style when the selection changes.
    LaunchedEffect(mapStyle) {
        mapView.setTileSource(MAP_STYLES[mapStyle.coerceIn(0, MAP_STYLES.lastIndex)])
    }

    val droneMarkers = remember { LinkedHashMap<String, Marker>() }
    val pilotMarkers = remember { LinkedHashMap<String, Marker>() }
    val lines = remember { LinkedHashMap<String, Polyline>() }
    val trailLines = remember { LinkedHashMap<String, Polyline>() }
    val trailCores = remember { LinkedHashMap<String, Polyline>() }
    val droneIconKeys = remember { HashMap<String, String>() }
    val pilotIconKeys = remember { HashMap<String, Boolean>() }
    val myLocation by LocationController.location.collectAsState()
    var userMoved by remember { mutableStateOf(false) }

    fun saveCamera() {
        val c = mapView.mapCenter
        onCameraChange(MapCamera(c.latitude, c.longitude, mapView.zoomLevelDouble))
    }

    // Restore the saved camera once on attach.
    LaunchedEffect(Unit) {
        if (savedCamera != null) {
            mapView.controller.setZoom(savedCamera.zoom)
            mapView.controller.setCenter(GeoPoint(savedCamera.lat, savedCamera.lon))
        }
    }

    // Center on the device location when the map first shows, so a fresh
    // install never sits on the default (0,0) ocean view. Without a saved
    // camera or a requested drone: use any last-known position immediately,
    // request a fresh fix, then center on the fix when it arrives. Centering
    // happens once per map attach so later location updates never fight the
    // user's view.
    var centeredOnLocation by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (savedCamera == null && focusKey == null) {
            LocationController.refresh(context)
            LocationController.lastKnown(context)?.let { loc ->
                if (!centeredOnLocation) {
                    mapView.controller.setCenter(loc)
                    mapView.controller.setZoom(INITIAL_ZOOM)
                    centeredOnLocation = true
                }
            }
        }
    }
    LaunchedEffect(myLocation) {
        if (!centeredOnLocation && myLocation != null &&
            savedCamera == null && focusKey == null
        ) {
            mapView.controller.setCenter(myLocation!!)
            mapView.controller.setZoom(INITIAL_ZOOM)
            centeredOnLocation = true
        }
    }

    // Center on a drone when the user requests it (list tap / marker tap /
    // notification tap). Uses the full retained set so it centers on the last
    // known position even if the drone is outside the history window. Re-runs
    // when the retained set loads so a cold-start notification centers even if
    // the drone hasn't been replayed from the DB yet, but only centers once
    // per request so later updates never fight the user's view. Runs after the
    // camera-restore and my-location effects so it wins.
    var lastCenteredTick by remember { mutableStateOf(-1) }
    LaunchedEffect(focusTick, allDrones) {
        if (focusKey == null || lastCenteredTick == focusTick) return@LaunchedEffect
        allDrones.firstOrNull { it.key == focusKey }?.let {
            mapView.controller.setCenter(GeoPoint(it.droneLat, it.droneLon))
            userMoved = true
            lastCenteredTick = focusTick
        }
    }

    // Track camera changes and tear down the map properly when leaving the tab.
    DisposableEffect(Unit) {
        mapView.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent): Boolean {
                userMoved = true
                saveCamera()
                return false
            }

            override fun onZoom(event: ZoomEvent): Boolean {
                userMoved = true
                saveCamera()
                return false
            }
        })
        onDispose { mapView.onDetach() }
    }

    Box(modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize()) { _ ->
            val nowMs = System.currentTimeMillis()
            val keys = drones.map { it.key }
            (droneMarkers.keys - keys).forEach { key ->
                droneMarkers.remove(key)?.let { mapView.overlays.remove(it) }
                pilotMarkers.remove(key)?.let { mapView.overlays.remove(it) }
                lines.remove(key)?.let { mapView.overlays.remove(it) }
                trailLines.remove(key)?.let { mapView.overlays.remove(it) }
                trailCores.remove(key)?.let { mapView.overlays.remove(it) }
                droneIconKeys.remove(key)
                pilotIconKeys.remove(key)
            }

            for (d in drones) {
                val stale = nowMs - d.lastSeen > STALE_MS

                // Thick white track with a dark casing (two stacked polylines)
                // so it reads clearly on every base map and stays distinct
                // from the cyan drone-pilot connector lines. Stroke widths are
                // density-scaled because osmdroid draws in raw pixels.
                val density = context.resources.displayMetrics.density
                val casing = trailLines.getOrPut(d.key) {
                    Polyline(mapView).apply {
                        paint.color = 0xE6000000.toInt()
                        paint.strokeWidth = 5f * density
                        paint.strokeCap = Paint.Cap.ROUND
                        setOnClickListener { _, _, _ ->
                            onDroneSelected(d.key)
                            true
                        }
                        mapView.overlays.add(this)
                    }
                }
                val core = trailCores.getOrPut(d.key) {
                    Polyline(mapView).apply {
                        paint.color = 0xFFFFFFFF.toInt()
                        paint.strokeWidth = 3f * density
                        paint.strokeCap = Paint.Cap.ROUND
                        setOnClickListener { _, _, _ ->
                            onDroneSelected(d.key)
                            true
                        }
                        mapView.overlays.add(this)
                    }
                }
                val trailPts = d.trail.map { GeoPoint(it.lat, it.lon) }
                casing.setPoints(trailPts)
                core.setPoints(trailPts)

                val dm = droneMarkers.getOrPut(d.key) {
                    Marker(mapView).apply {
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        // Never show the default white info bubble on tap.
                        setInfoWindow(null)
                        setOnMarkerClickListener { _, _ ->
                            onDroneSelected(d.key)
                            true
                        }
                        mapView.overlays.add(this)
                    }
                }
                val iconKey = if (stale) "s" else altitudeBand(d.droneAltitude)
                if (droneIconKeys[d.key] != iconKey) {
                    droneIconKeys[d.key] = iconKey
                    dm.icon = IconCache.drone(context, d.droneAltitude, stale)
                        .toDrawable(context.resources)
                }
                dm.position = GeoPoint(d.droneLat, d.droneLon)
                dm.title = d.basicId.ifBlank { d.mac }
                dm.snippet = "alt ${d.droneAltitude}m  RSSI ${d.rssi}  MAC ${d.mac}"

                if (d.pilotLat != 0.0 || d.pilotLon != 0.0) {
                    val pm = pilotMarkers.getOrPut(d.key) {
                        Marker(mapView).apply {
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            title = "Pilot"
                            // Never show the default white info bubble on tap.
                            setInfoWindow(null)
                            setOnMarkerClickListener { _, _ ->
                                onDroneSelected(d.key)
                                true
                            }
                            mapView.overlays.add(this)
                        }
                    }
                    if (pilotIconKeys[d.key] != stale) {
                        pilotIconKeys[d.key] = stale
                        pm.icon = IconCache.pilot(context, stale)
                            .toDrawable(context.resources)
                    }
                    pm.position = GeoPoint(d.pilotLat, d.pilotLon)
                    val line = lines.getOrPut(d.key) {
                        Polyline(mapView).apply {
                            paint.color = 0xCC00BCD4.toInt()
                            paint.strokeWidth = 4f
                            setOnClickListener { _, _, _ ->
                                onDroneSelected(d.key)
                                true
                            }
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
                    pilotIconKeys.remove(d.key)
                }
            }
            mapView.invalidate()
        }

        SmallFloatingActionButton(
            onClick = {
                onStyleChange((mapStyle + 1) % MAP_STYLES.size)
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
        ) {
            Icon(Icons.Filled.Layers, contentDescription = "Change map style")
        }

        SmallFloatingActionButton(
            onClick = {
                LocationController.refresh(context) { geo ->
                    geo?.let {
                        mapView.controller.setCenter(it)
                        userMoved = true
                        saveCamera()
                    }
                }
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 72.dp, end = 16.dp)
        ) {
            Icon(Icons.Filled.MyLocation, contentDescription = "Center on my location")
        }

        // Center on the most recently detected activity (drone), using the
        // last known position even if the drone's heartbeat has stopped. Uses
        // the full retained set (allDrones), not just the history window.
        SmallFloatingActionButton(
            onClick = {
                allDrones.maxByOrNull { it.lastSeen }?.let {
                    mapView.controller.setCenter(GeoPoint(it.droneLat, it.droneLon))
                    userMoved = true
                    saveCamera()
                }
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 128.dp, end = 16.dp)
        ) {
            DroneGlyph(Modifier.size(24.dp))
        }

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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.History,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "  History: ${
                            if (historyScale == HISTORY_SCALE_ALL) "all"
                            else historyLabel(historyMinutes)
                        }",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (historyScale != HISTORY_SCALE_ALL) {
                    Slider(
                        value = historyMinutes.toFloat(),
                        onValueChange = {
                            onHistoryChange(it.toInt().coerceIn(0, historyMaxMinutes))
                        },
                        valueRange = 0f..historyMaxMinutes.toFloat()
                    )
                } else {
                    Text(
                        "Showing all retained history",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
