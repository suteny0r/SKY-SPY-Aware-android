package com.suteny0r.skyspyaware

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Fetches ESRI World Imagery tiles around a location and runs the on-device
 * satellite object detector over the stitched image. Returns per-class counts
 * plus the detected identity boxes with geographic corners.
 */
object SatelliteAnalyzer {

    private const val ZOOM = 16
    private const val GRID = 4
    private const val TILE = 256
    private const val MIN_ZOOM = 14
    private const val MAX_ZOOM = 19
    private const val TILE_URL =
        "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/%d/%d/%d"

    /** A detected object with geo-anchored corners. [corners] is the oriented
     *  polygon (4 points, lat to lon); [latMin]/[lonMin]/[latMax]/[lonMax]
     *  are its axis-aligned bounds for quick filtering. */
    data class ScanBox(
        val cls: Int,
        val conf: Float,
        val color: Int,
        val label: String,
        val latMin: Double,
        val lonMin: Double,
        val latMax: Double,
        val lonMax: Double,
        val corners: List<Pair<Double, Double>> = emptyList()
    )

    data class AreaScan(
        val counts: Map<String, Int>,
        val boxes: List<ScanBox> = emptyList(),
        /**
         * False when any imagery tile failed to download. An incomplete scan
         * ran the detector over partially black imagery: its counts must not
         * be cached as a definitive 24h result.
         */
        val complete: Boolean = true
    )

    /** Geographic rectangle (lat/lon) of an area of interest. */
    data class GeoBounds(
        val latMin: Double,
        val lonMin: Double,
        val latMax: Double,
        val lonMax: Double
    )

    /**
     * Bounding box of a set of points, padded by [marginM] meters on each
     * side, plus a small proportional pad so a zero-size trail still yields a
     * usable scan area.
     */
    fun boundsOfPoints(
        points: List<Pair<Double, Double>>,
        marginM: Double
    ): GeoBounds? {
        if (points.isEmpty()) return null
        var latMin = Double.MAX_VALUE
        var latMax = -Double.MAX_VALUE
        var lonMin = Double.MAX_VALUE
        var lonMax = -Double.MAX_VALUE
        for ((lat, lon) in points) {
            if (lat < latMin) latMin = lat
            if (lat > latMax) latMax = lat
            if (lon < lonMin) lonMin = lon
            if (lon > lonMax) lonMax = lon
        }
        val latPerM = 1.0 / 111_320.0
        val lonPerM = 1.0 / (111_320.0 * Math.cos(Math.toRadians((latMin + latMax) / 2.0)))
        val padLat = (latMax - latMin) * 0.15 + latPerM * marginM
        val padLon = (lonMax - lonMin) * 0.15 + lonPerM * marginM
        return GeoBounds(
            latMin = latMin - padLat,
            lonMin = lonMin - padLon,
            latMax = latMax + padLat,
            lonMax = lonMax + padLon
        )
    }

    /** Single-point fallback bounds: a [marginM] block around [lat]/[lon]. */
    fun boundsOfPoint(lat: Double, lon: Double, marginM: Double): GeoBounds {
        val latPerM = 1.0 / 111_320.0
        val lonPerM = 1.0 / (111_320.0 * Math.cos(Math.toRadians(lat)))
        return GeoBounds(
            latMin = lat - latPerM * marginM,
            lonMin = lon - lonPerM * marginM,
            latMax = lat + latPerM * marginM,
            lonMax = lon + lonPerM * marginM
        )
    }

    /** Scan the ~2.2 km block centered on [lat]/[lon] at the base zoom. */
    suspend fun scan(context: Context, lat: Double, lon: Double): AreaScan =
        scanInternal(context, lat, lon, ZOOM)

    /**
     * Scan an area that matches [bounds] instead of a fixed-size block. The
     * center is the bounds center and the zoom is chosen so the whole flight
     * footprint fits inside the scan grid, giving the flight boundaries the
     * full scan rather than the ~2.2 km fixed block used by [scan].
     */
    suspend fun scanBounds(context: Context, bounds: GeoBounds): AreaScan {
        val cLat = (bounds.latMin + bounds.latMax) / 2.0
        val cLon = (bounds.lonMin + bounds.lonMax) / 2.0
        val zoom = zoomForBounds(bounds)
        return scanInternal(context, cLat, cLon, zoom)
    }

    /**
     * Two-pass scan: [wideBounds] covers the broader area at a lower zoom
     * (catches large context: ships, tanks, structures) while [tightBounds] is
     * the flight path plus a small margin at a higher zoom (catches small
     * objects: cars, pools, boats that the wide pass cannot resolve). The two
     * box lists are merged with geo-overlap dedup so the same object detected
     * in both passes is not double-counted.
     */
    suspend fun scanCombined(
        context: Context,
        wideBounds: GeoBounds,
        tightBounds: GeoBounds
    ): AreaScan {
        val wide = scanBounds(context, wideBounds)
        val tight = scanBounds(context, tightBounds)
        val boxes = mergeBoxes(wide.boxes, tight.boxes)
        val counts = HashMap<String, Int>()
        for (b in boxes) counts.merge(b.label, 1, Int::plus)
        return AreaScan(counts, boxes, complete = wide.complete && tight.complete)
    }

    /** Dedup two box lists by class-aware geo overlap (IoU), keeping the
     *  higher-confidence box of each merged group. */
    private fun mergeBoxes(
        wide: List<ScanBox>,
        tight: List<ScanBox>
    ): List<ScanBox> {
        if (wide.isEmpty()) return tight
        if (tight.isEmpty()) return wide
        val merged = ArrayList<ScanBox>(wide.size + tight.size)
        // Seed with the tight-pass boxes (higher zoom = more precise), then
        // absorb any wide-pass box that is not already represented.
        for (t in tight) merged.add(t)
        for (w in wide) {
            var dup = false
            for (m in merged) {
                if (m.label == w.label && geoIoU(m, w) >= 0.3f) {
                    dup = true
                    break
                }
            }
            if (!dup) merged.add(w)
        }
        return merged
    }

    /** Intersection-over-union of two geographic boxes in lat/lon space. */
    private fun geoIoU(a: ScanBox, b: ScanBox): Float {
        val ix = minOf(a.lonMax, b.lonMax) - maxOf(a.lonMin, b.lonMin)
        val iy = minOf(a.latMax, b.latMax) - maxOf(a.latMin, b.latMin)
        if (ix <= 0.0 || iy <= 0.0) return 0f
        val inter = ix * iy
        val union = (a.lonMax - a.lonMin) * (a.latMax - a.latMin) +
            (b.lonMax - b.lonMin) * (b.latMax - b.latMin) - inter
        if (union <= 0.0) return 0f
        return (inter / union).toFloat()
    }

    private suspend fun scanInternal(
        context: Context,
        lat: Double,
        lon: Double,
        zoom: Int
    ): AreaScan = withContext(Dispatchers.IO) {
        YoloDetector.init(context)
        val grid = fetchGrid(lat, lon, zoom)
            ?: return@withContext AreaScan(emptyMap(), complete = false)
        val bmp = grid.bitmap
        val dets = YoloDetector.detect(bmp)
        val boxes = ArrayList<ScanBox>(dets.size)
        val gridW = bmp.width
        val gridH = bmp.height
        for (d in dets) {
            val corners = d.corners.map { (cx, cy) ->
                val (la, lo) = gridToGeo(lat, lon, cx * gridW, cy * gridH, zoom)
                la to lo
            }
            val latMin = corners.minOf { it.first }
            val latMax = corners.maxOf { it.first }
            val lonMin = corners.minOf { it.second }
            val lonMax = corners.maxOf { it.second }
            boxes.add(
                ScanBox(
                    d.cls,
                    d.conf,
                    YoloDetector.boxColor(d),
                    d.className,
                    latMin,
                    lonMin,
                    latMax,
                    lonMax,
                    corners
                )
            )
        }
        bmp.recycle()
        val counts = HashMap<String, Int>()
        for (d in dets) counts.merge(d.className, 1, Int::plus)
        AreaScan(counts, boxes, complete = grid.complete)
    }

    private class GridResult(val bitmap: Bitmap, val complete: Boolean)

    private fun fetchGrid(lat: Double, lon: Double, zoom: Int): GridResult? {
        val (cx, cy) = tileXY(lat, lon, zoom)
        val out = Bitmap.createBitmap(GRID * TILE, GRID * TILE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val half = GRID / 2
        var failed = 0
        for (gy in 0 until GRID) {
            for (gx in 0 until GRID) {
                val tx = cx - half + gx
                val ty = cy - half + gy
                val bmp = try {
                    download(String.format(Locale.US, TILE_URL, zoom, ty, tx))
                } catch (_: Exception) {
                    null
                }
                if (bmp != null) {
                    canvas.drawBitmap(bmp, (gx * TILE).toFloat(), (gy * TILE).toFloat(), null)
                    bmp.recycle()
                } else {
                    failed++
                }
            }
        }
        if (failed == GRID * GRID) {
            // No imagery at all (offline): running the detector over a fully
            // black bitmap yields garbage. Report failure instead.
            out.recycle()
            return null
        }
        return GridResult(out, complete = failed == 0)
    }

    /**
     * Highest zoom at which the whole [bounds] still fits inside the 4x4-tile
     * scan grid (the grid spans tiles [cx-2, cx+1] around the center tile).
     * Clamps to [MIN_ZOOM] when the area is larger than the grid.
     */
    private fun zoomForBounds(b: GeoBounds): Int {
        val cLat = (b.latMin + b.latMax) / 2.0
        val cLon = (b.lonMin + b.lonMax) / 2.0
        for (z in MAX_ZOOM downTo MIN_ZOOM) {
            val (cx, cy) = tileXY(cLat, cLon, z)
            val half = GRID / 2
            val txMin = tileX(b.lonMin, z)
            val txMax = tileX(b.lonMax, z)
            val tyMin = tileY(b.latMax, z)
            val tyMax = tileY(b.latMin, z)
            if (txMin >= cx - half && txMax <= cx - half + GRID - 1 &&
                tyMin >= cy - half && tyMax <= cy - half + GRID - 1
            ) {
                return z
            }
        }
        return MIN_ZOOM
    }

    /**
     * Converts a pixel in the stitched 4x4-tile grid back to a geographic
     * coordinate. The grid is built by [fetchGrid] around the tile containing
     * the scan center, so a grid pixel maps to a world pixel on the same
     * zoom's tile pyramid, which inverts to lat/lon via Web Mercator.
     */
    private fun gridToGeo(
        centerLat: Double,
        centerLon: Double,
        px: Float,
        py: Float,
        zoom: Int
    ): Pair<Double, Double> {
        val (cx, cy) = tileXY(centerLat, centerLon, zoom)
        val half = GRID / 2
        // Rotated boxes near the grid edge legitimately produce corners just
        // outside [0, GRID*TILE]. Clamp the pixel, then map straight to world
        // pixels; the old per-tile modulo wrapped an out-of-range corner by a
        // full tile (~600 m at z16), grossly distorting the polygon.
        val maxPx = (GRID * TILE).toFloat()
        val cpx = px.coerceIn(0f, maxPx)
        val cpy = py.coerceIn(0f, maxPx)
        val n = 1 shl zoom
        val worldX = (cx - half).toDouble() * TILE + cpx
        val worldY = (cy - half).toDouble() * TILE + cpy
        val lon = worldX / (n * TILE) * 360.0 - 180.0
        val latRad = Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * worldY / (n * TILE))))
        return Math.toDegrees(latRad) to lon
    }

    private fun download(url: String): Bitmap? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "SKY-SPY-Aware/1.0")
            if (conn.responseCode in 200..299) {
                BitmapFactory.decodeStream(conn.inputStream)
            } else {
                null
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun tileX(lon: Double, zoom: Int): Int {
        val n = 1 shl zoom
        return ((lon + 180.0) / 360.0 * n).toInt()
    }

    private fun tileY(lat: Double, zoom: Int): Int {
        val n = 1 shl zoom
        val latRad = Math.toRadians(lat)
        return ((1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) /
            2.0 * n).toInt()
    }

    private fun tileXY(lat: Double, lon: Double, zoom: Int): Pair<Int, Int> =
        tileX(lon, zoom) to tileY(lat, zoom)
}