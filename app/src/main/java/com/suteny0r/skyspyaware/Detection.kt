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
    /** Position history (newest last) for the flight-path trail. */
    val trail: List<Pair<Double, Double>> = emptyList()
)
