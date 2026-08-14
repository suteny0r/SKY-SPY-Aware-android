package com.suteny0r.skyspyaware

import org.json.JSONObject

/** A single parsed Sky Spy detection line. */
data class Detection(
    val mac: String,
    val rssi: Int,
    val droneLat: Double,
    val droneLon: Double,
    val droneAltitude: Int,
    val pilotLat: Double,
    val pilotLon: Double,
    val basicId: String
)

/** Parses Sky Spy JSON detection lines from the MQTT feed. */
object DetectionParser {
    private const val PREFIX = "{\"mac\""

    fun parse(line: String): Detection? {
        if (!line.startsWith(PREFIX)) return null
        return try {
            val o = JSONObject(line)
            if (!o.has("mac") || !o.has("drone_lat")) return null
            Detection(
                mac = o.getString("mac"),
                rssi = o.optInt("rssi", 0),
                droneLat = o.getDouble("drone_lat"),
                droneLon = o.getDouble("drone_long"),
                droneAltitude = o.optInt("drone_altitude", 0),
                pilotLat = o.optDouble("pilot_lat", 0.0),
                pilotLon = o.optDouble("pilot_long", 0.0),
                basicId = o.optString("basic_id", "")
            )
        } catch (_: Exception) {
            null
        }
    }
}

/** A timestamped position point for trail/history rendering. */
data class TrailPoint(val ts: Long, val lat: Double, val lon: Double)

/** A timestamped drone position paired with the pilot's position at that fix. */
data class TrailPointWithPilot(
    val ts: Long,
    val lat: Double,
    val lon: Double,
    val alt: Int,
    val pilotLat: Double,
    val pilotLon: Double
)

/** Current state of a tracked drone. */
data class Drone(
    val key: String,
    val mac: String,
    val rssi: Int,
    val droneLat: Double,
    val droneLon: Double,
    val droneAltitude: Int,
    val pilotLat: Double,
    val pilotLon: Double,
    val basicId: String,
    val lastSeen: Long,
    val detections: Int,
    /** Per-MAC last-reported position, to ignore stale beacon frames. */
    val macPositions: Map<String, Pair<Double, Double>> = emptyMap(),
    /** Timestamped position history (newest last) for trails/history. */
    val trail: List<TrailPoint> = emptyList()
)

/**
 * Coordinates inside a small box around (0,0) are the "Gulf of Guinea"
 * null-island glitch: sim/ADS-B data occasionally reports positions a few
 * kilometers off the origin instead of exact (0,0). Treat them like the exact
 * (0,0) "no position known" sentinel so a corrupt fix never draws a trail line
 * out into the open ocean.
 */
const val NULL_ISLAND_DEG = 0.5

/**
 * True when [lat],[lon] are a real position. A corrupt fix can zero out only
 * one axis (e.g. lat=25.78, lon=0.0), so a valid position requires BOTH
 * coordinates away from the null-island box, not just one.
 */
fun isValidPosition(lat: Double, lon: Double): Boolean =
    !(kotlin.math.abs(lat) < NULL_ISLAND_DEG || kotlin.math.abs(lon) < NULL_ISLAND_DEG)
