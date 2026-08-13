package com.suteny0r.skyspyaware

/**
 * Location-agnostic role classification for a drone. Unlike the earlier
 * Miami-specific heuristic, this infers the operator's likely role purely from
 * observable evidence: the satellite object scan (DOTA classes: ships, harbors,
 * pools, courts, storage tanks, vehicles) combined with how the drone flies
 * (footprint tightness, altitude, hover) and where it appears to launch from.
 *
 * Rules are ordered most-specific first:
 *  1. Maritime: the scan area shows ships/harbors -> ship / port inspection.
 *  2. Recreation: pools / tennis / ball courts nearby -> recreational flying.
 *  3. Site monitoring: storage tanks + vehicles, or a tight hover at altitude.
 *  4. Aviation: planes / helicopters present -> flight-corridor transit.
 *  5. Otherwise -> general / transit.
 */
object DroneClassifier {

    private const val CENTER_FILTER_KM = 5.0

    // DOTA (and future VisDrone/xView) class names that signal each context.
    private val MARITIME = setOf("ship", "harbor", "boat", "maritime vessel")
    private val RECREATION = setOf(
        "swimming pool", "tennis court", "baseball diamond",
        "soccer ball field", "basketball court", "ground track field"
    )
    private val INDUSTRIAL = setOf(
        "storage tank", "large vehicle", "small vehicle", "roundabout",
        "truck", "crane", "construction site"
    )
    private val AVIATION = setOf("plane", "helicopter")

    private fun evidence(satellite: Map<String, Int>, keys: Set<String>): Int =
        satellite.entries.sumOf { (k, v) -> if (k in keys) v else 0 }

    /**
     * Classify from a position trail and the satellite scan for that drone.
     * Returns the role plus a human-readable justification string.
     */
    fun classify(
        pts: List<StatPoint>,
        satellite: Map<String, Int>
    ): Pair<PilotRole, String> {
        if (pts.isEmpty()) return PilotRole.UNKNOWN to "no position history"

        val (cLat, cLon) = meanCenter(pts)
        val bboxKm = bboxKm(pts, cLat, cLon)
        val avgAlt = pts.map { it.alt.coerceAtLeast(0) }.sum().toDouble() / pts.size

        val maritime = evidence(satellite, MARITIME)
        val recreation = evidence(satellite, RECREATION)
        val industrial = evidence(satellite, INDUSTRIAL)
        val aviation = evidence(satellite, AVIATION)

        return when {
            maritime >= 2 -> PilotRole.PORT_INSPECTION to
                "area shows $maritime ship/harbor objects"
            recreation >= 2 -> PilotRole.BEACH_TOURISM to
                "area shows $recreation recreational features (pool/courts)"
            industrial >= 2 && bboxKm <= 0.5 -> PilotRole.SITE_MONITORING to
                "area shows $industrial industrial objects; tight ${
                    "%.2f".format(bboxKm)}km footprint"
            bboxKm <= 0.15 && avgAlt >= 15 -> PilotRole.SITE_MONITORING to
                "tight hover at ${avgAlt.toInt()}m altitude"
            aviation >= 1 -> PilotRole.GENERAL to
                "area shows $aviation aircraft (flight corridor)"
            else -> PilotRole.GENERAL to
                "no distinctive pattern (${maritime} ship, ${recreation} rec, " +
                "$industrial industrial objects)"
        }
    }

    /** Outlier-robust center (same filter as StatisticsCalculator). */
    fun meanCenter(pts: List<StatPoint>): Pair<Double, Double> {
        if (pts.isEmpty()) return 0.0 to 0.0
        val cLat = pts.map { it.lat }.sum() / pts.size
        val cLon = pts.map { it.lon }.sum() / pts.size
        val inliers = pts.filter {
            haversine(it.lat, it.lon, cLat, cLon) / 1000.0 <= CENTER_FILTER_KM
        }
        val use = inliers.ifEmpty { pts }
        return (use.map { it.lat }.sum() / use.size) to
            (use.map { it.lon }.sum() / use.size)
    }

    fun bboxKm(pts: List<StatPoint>, cLat: Double, cLon: Double): Double {
        var minLat = Double.MAX_VALUE
        var maxLat = -Double.MAX_VALUE
        var minLon = Double.MAX_VALUE
        var maxLon = -Double.MAX_VALUE
        var n = 0
        for (p in pts) {
            if (haversine(p.lat, p.lon, cLat, cLon) / 1000.0 > CENTER_FILTER_KM) continue
            if (p.lat < minLat) minLat = p.lat
            if (p.lat > maxLat) maxLat = p.lat
            if (p.lon < minLon) minLon = p.lon
            if (p.lon > maxLon) maxLon = p.lon
            n++
        }
        if (n < 2) return 0.0
        return haversine(minLat, minLon, maxLat, maxLon) / 1000.0
    }

    /** Great-circle distance between two coordinates in meters. */
    fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }
}
