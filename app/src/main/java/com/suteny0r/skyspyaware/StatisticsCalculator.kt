package com.suteny0r.skyspyaware

import java.util.Calendar

/** A single position fix used for statistics. */
data class StatPoint(val ts: Long, val lat: Double, val lon: Double, val alt: Int)

/** Likely operator role of a drone, inferred from where and how it flies. */
enum class PilotRole(val label: String) {
    PORT_INSPECTION("Port / ship inspection"),
    BEACH_TOURISM("Beach tourism / recreational"),
    SITE_MONITORING("Construction / site monitoring"),
    GENERAL("General / transit"),
    UNKNOWN("Unknown")
}

/** Whether the operator appears to have launched from land or a vessel. */
enum class LaunchType(val label: String) {
    LAND("land-launch"),
    SEA("sea-launch"),
    UNKNOWN("launch unknown")
}

/** A single detected flight (a continuous segment of one drone's trail). */
data class FlightSummary(
    val droneKey: String,
    val make: String,
    val model: String,
    val startTs: Long,
    val endTs: Long,
    val durationMs: Long,
    val distanceM: Double,
    val positions: Int,
    val centroidLat: Double,
    val centroidLon: Double
)

/**
 * Segment the entire retained history into individual flights and return one
 * [FlightSummary] per flight (across all drones), newest first. A flight is a
 * continuous run of fixes separated from the next by more than [FLIGHT_GAP_MS].
 * Simulator drones are excluded. Used by the Flights tab to surface repeat
 * flyers (the same drone / same area, many times) for commercial/LEO spotting.
 */

/** Per-drone statistics summary. */
data class DroneStats(
    val key: String,
    val detections: Int,
    val flights: Int,
    val flightTimeMs: Long,
    val distanceM: Double,
    val avgSpeedMs: Double,
    val minSpeedMs: Double,
    val maxSpeedMs: Double,
    val avgAltitudeM: Double,
    val maxAltitudeM: Int,
    val role: PilotRole,
    val roleReason: String,
    val launch: LaunchType,
    val satellite: Map<String, Int>,
    val isSimulator: Boolean,
    val make: String = "",
    val model: String = "",
    val msrpUsd: Int = 0,
    val pilotProfile: List<PilotProfile> = emptyList()
)

/** Aggregate statistics for the Stats tab. */
data class Statistics(
    val totalDetections: Long,
    val activeDrones: Int,
    val totalFlights: Int,
    val totalFlightTimeMs: Long,
    val totalDistanceM: Double,
    val avgSpeedMs: Double,
    val maxSpeedMs: Double,
    val avgAltitudeM: Double,
    val maxAltitudeM: Int,
    val byDayOfWeek: List<Int>,
    val byHour: List<Int>,
    val altitudeHistogram: List<Int>,
    val topDrones: List<DroneStats>,
    val roleCounts: Map<PilotRole, Int>,
    val launchCounts: Map<LaunchType, Int>,
    val simulatorDrones: Int,
    val simulatorDetections: Long,
    /** make -> number of distinct drones of that make. */
    val makeCounts: Map<String, Int>,
    /** "make model" -> number of distinct drones of that model. */
    val modelCounts: Map<String, Int>,
    /** "make model" -> drone keys (basic_id/MAC) attributed to that model. */
    val modelDrones: Map<String, List<String>>,
    /** Pilot-profile -> number of distinct drones (MSRP-based estimate). */
    val pilotProfileCounts: Map<PilotProfile, Int>,
    /** Aircraft category -> number of distinct drones. */
    val categoryCounts: Map<DroneCategory, Int>,
    /** Sum of estimated MSRP across distinct drones (fleet value). */
    val fleetValueUsd: Long
)

/**
 * Computes statistics from the full retained detection history. Flights are
 * grouped from consecutive detections with a gap threshold; speeds come from
 * movement between fixes; the pilot role is a best-effort heuristic from the
 * drone's footprint (where it flies, how tightly, and at what altitude).
 */
object StatisticsCalculator {

    private const val FLIGHT_GAP_MS = 5 * 60 * 1000L
    private const val MIN_SPEED_SEG_S = 1.0
    private const val MAX_SPEED_SEG_S = 180.0
    private const val MAX_PLAUSIBLE_SPEED_MS = 100.0

    fun compute(
        rows: List<CachedDetection>,
        simulatorKeys: Set<String>,
        satellite: Map<String, Map<String, Int>>,
        faaText: Map<String, String> = emptyMap(),
        notes: Map<String, String> = emptyMap()
    ): Statistics {
        val byKey = LinkedHashMap<String, MutableList<StatPoint>>()
        val pilotByKey = HashMap<String, Pair<Double, Double>>()
        val macByKey = HashMap<String, String>()
        val simSeen = HashSet<String>()
        var allRows = 0L
        var simDetections = 0L
        for (r in rows) {
            allRows++
            if (!isValidPosition(r.droneLat, r.droneLon)) continue
            val key = r.basicId.ifBlank { r.mac }
            // Simulator drones are identified by basicIds that fail the FAA
            // registration lookup; exclude them from real-world statistics.
            if (key in simulatorKeys) {
                simDetections++
                simSeen.add(key)
                continue
            }
            // Remember a MAC for OUI-based make attribution (only when there
            // is no basic_id to key on).
            macByKey.putIfAbsent(key, r.mac)
            // Pilot positions are usually accurate; keep the latest one seen.
            if (isValidPosition(r.pilotLat, r.pilotLon)) {
                pilotByKey[key] = r.pilotLat to r.pilotLon
            }
            byKey.getOrPut(key) { mutableListOf() }
                .add(StatPoint(r.ts, r.droneLat, r.droneLon, r.droneAltitude))
        }
        val totalDetections = allRows - simDetections

        val dayBuckets = IntArray(7)
        val hourBuckets = IntArray(24)
        val altBins = IntArray(8)
        val droneStats = ArrayList<DroneStats>()
        val roleCounts = HashMap<PilotRole, Int>()
        val launchCounts = HashMap<LaunchType, Int>()
        val makeCounts = HashMap<String, Int>()
        val modelCounts = HashMap<String, Int>()
        val pilotProfileCounts = HashMap<PilotProfile, Int>()
        val categoryCounts = HashMap<DroneCategory, Int>()
        var fleetValueUsd = 0L
        val cal = Calendar.getInstance()

        var totalFlightTimeMs = 0L
        var totalDistanceM = 0.0
        var speedSum = 0.0
        var speedN = 0
        var maxSpeed = 0.0
        var altSum = 0.0
        var altN = 0
        var maxAlt = 0

        for ((key, pts) in byKey) {
            pts.sortBy { it.ts }
            for (p in pts) {
                cal.timeInMillis = p.ts
                dayBuckets[cal.get(Calendar.DAY_OF_WEEK) - 1]++
                hourBuckets[cal.get(Calendar.HOUR_OF_DAY)]++
                val alt = p.alt.coerceAtLeast(0)
                altSum += alt
                altN++
                if (alt > maxAlt) maxAlt = alt
                altBins[(alt / 25).coerceIn(0, 7)]++
            }

            val flights = groupFlights(pts)
            val flightTime = flights.sumOf { it.last().ts - it.first().ts }
            var dist = 0.0
            var fspeed = 0.0
            var fSpeedN = 0
            var fSpeedMax = 0.0
            var fSpeedMin = Double.MAX_VALUE
            for (f in flights) {
                for (i in 1 until f.size) {
                    val a = f[i - 1]
                    val b = f[i]
                    val dt = (b.ts - a.ts) / 1000.0
                    // Skip sub-second bursts (position flip-flops, multiple
                    // frames in the same tick) and long gaps.
                    if (dt < MIN_SPEED_SEG_S || dt > MAX_SPEED_SEG_S) continue
                    val d = haversine(a.lat, a.lon, b.lat, b.lon)
                    if (d <= 0) continue
                    val s = d / dt
                    // Skip implausible jumps (bogus coordinates) so they do
                    // not skew distance or speed.
                    if (s > MAX_PLAUSIBLE_SPEED_MS) continue
                    dist += d
                    fspeed += s
                    fSpeedN++
                    if (s > fSpeedMax) fSpeedMax = s
                    if (s < fSpeedMin) fSpeedMin = s
                }
            }
            val avgAlt = altSumFor(pts)
            val sat = satellite[key] ?: emptyMap()
            val (role, roleReason) = DroneClassifier.classify(pts, sat, notes[key] ?: "")
            val launch = launchFor(key, pts, pilotByKey, sat)
            val mac = macByKey[key] ?: ""
            // Key is the basic_id when one exists, otherwise the MAC itself.
            val basicId = if (key.contains(':')) "" else key
            val (make, model) = DroneIdentity.resolve(basicId, mac, faaText[basicId])
            val spec = DroneCatalog.match(make, model)
            val msrp = spec?.msrpUsd ?: 0
            if (make.isNotEmpty()) makeCounts.merge(make, 1, Int::plus)
            if (make.isNotEmpty() || model.isNotEmpty()) {
                val mm = if (model.isNotEmpty()) "$make $model".trim() else make
                if (mm.isNotEmpty()) modelCounts.merge(mm, 1, Int::plus)
            }
            if (spec != null) {
                spec.pilotProfiles.forEach { pilotProfileCounts.merge(it, 1, Int::plus) }
                spec.categories.forEach { categoryCounts.merge(it, 1, Int::plus) }
                fleetValueUsd += msrp
            }

            droneStats.add(
                DroneStats(
                    key = key,
                    detections = pts.size,
                    flights = flights.size,
                    flightTimeMs = flightTime,
                    distanceM = dist,
                    avgSpeedMs = if (fSpeedN > 0) fspeed / fSpeedN else 0.0,
                    minSpeedMs = if (fSpeedN > 0) fSpeedMin else 0.0,
                    maxSpeedMs = fSpeedMax,
                    avgAltitudeM = avgAlt,
                    maxAltitudeM = pts.maxOf { it.alt.coerceAtLeast(0) },
                    role = role,
                    roleReason = roleReason,
                    launch = launch,
                    satellite = sat,
                    isSimulator = false,
                    make = make,
                    model = model,
                    msrpUsd = msrp,
                    pilotProfile = if (spec != null) spec.pilotProfiles.toList() else listOf(PilotProfile.UNKNOWN)
                )
            )
            roleCounts.merge(role, 1, Int::plus)
            launchCounts.merge(launch, 1, Int::plus)

            totalFlightTimeMs += flightTime
            totalDistanceM += dist
            speedSum += fspeed
            speedN += fSpeedN
            if (fSpeedMax > maxSpeed) maxSpeed = fSpeedMax
        }

        droneStats.sortWith(compareByDescending<DroneStats> { it.flights }
            .thenByDescending { it.detections })
        // Full model -> drone-key map (not limited like topDrones) so the
        // "Drone models" tree can list every drone under its model.
        val modelDrones = LinkedHashMap<String, MutableList<String>>()
        for (d in droneStats) {
            val mm = if (d.model.isNotEmpty()) "${d.make} ${d.model}".trim() else d.make
            if (mm.isEmpty()) continue
            modelDrones.getOrPut(mm) { mutableListOf() }.add(d.key)
        }
        val topDrones = droneStats.take(12)

        return Statistics(
            totalDetections = totalDetections,
            activeDrones = byKey.size,
            totalFlights = droneStats.sumOf { it.flights },
            totalFlightTimeMs = totalFlightTimeMs,
            totalDistanceM = totalDistanceM,
            avgSpeedMs = if (speedN > 0) speedSum / speedN else 0.0,
            maxSpeedMs = maxSpeed,
            avgAltitudeM = if (altN > 0) altSum / altN else 0.0,
            maxAltitudeM = maxAlt,
            byDayOfWeek = dayBuckets.toList(),
            byHour = hourBuckets.toList(),
            altitudeHistogram = altBins.toList(),
            topDrones = topDrones,
            roleCounts = roleCounts,
            launchCounts = launchCounts,
            simulatorDrones = simSeen.size,
            simulatorDetections = simDetections,
            makeCounts = makeCounts,
            modelCounts = modelCounts,
            modelDrones = modelDrones,
            pilotProfileCounts = pilotProfileCounts,
            categoryCounts = categoryCounts,
            fleetValueUsd = fleetValueUsd
        )
    }

    private fun groupFlights(pts: List<StatPoint>): List<List<StatPoint>> {
        val out = ArrayList<List<StatPoint>>()
        var cur = ArrayList<StatPoint>()
        var prevTs = Long.MIN_VALUE
        for (p in pts) {
            if (cur.isNotEmpty() && p.ts - prevTs > FLIGHT_GAP_MS) {
                if (cur.size >= 2) out.add(cur)
                cur = ArrayList()
            }
            cur.add(p)
            prevTs = p.ts
        }
        if (cur.size >= 2) out.add(cur)
        return out
    }

    private fun altSumFor(pts: List<StatPoint>): Double =
        pts.map { it.alt.coerceAtLeast(0).toDouble() }.sum() / pts.size.toDouble()

    /**
     * Land-launch vs sea-launch. Uses the pilot (operator) position when known,
     * otherwise the drone's first fix. The land/water decision is driven by the
     * satellite scan for the drone (ships/harbors imply water), falling back to
     * "unknown" when there is no evidence either way. This is location-agnostic:
     * no hardcoded coastline coordinates.
     */
    private fun launchFor(
        key: String,
        pts: List<StatPoint>,
        pilotByKey: Map<String, Pair<Double, Double>>,
        satellite: Map<String, Int>
    ): LaunchType {
        val water = waterEvidence(satellite)
        val (pl, pn) = pilotByKey[key] ?: (0.0 to 0.0)
        val probe = if (pl != 0.0 || pn != 0.0) pl to pn else pts.firstOrNull()?.let { it.lat to it.lon }
        return when {
            probe != null && water != null -> if (water) LaunchType.SEA else LaunchType.LAND
            water != null -> if (water) LaunchType.SEA else LaunchType.LAND
            probe != null -> LaunchType.UNKNOWN
            else -> LaunchType.UNKNOWN
        }
    }

    /** True if the scan area looks like water, false if land, null if unclear. */
    private fun waterEvidence(satellite: Map<String, Int>): Boolean? {
        val water = (satellite["ship"] ?: 0) + (satellite["harbor"] ?: 0) +
            (satellite["boat"] ?: 0)
        val land = (satellite["swimming pool"] ?: 0) + (satellite["roundabout"] ?: 0) +
            (satellite["baseball diamond"] ?: 0) + (satellite["tennis court"] ?: 0) +
            (satellite["storage tank"] ?: 0) + (satellite["large vehicle"] ?: 0) +
            (satellite["small vehicle"] ?: 0)
        return when {
            water > land -> true
            land > water -> false
            else -> null
        }
    }

    /** Great-circle distance between two coordinates in meters. */
    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    /**
     * Segment the entire retained history into individual flights (gap-based)
     * and return one [FlightSummary] per flight, newest first. A flight is a
     * continuous run of fixes separated from the next by more than
     * [FLIGHT_GAP_MS]. Simulator drones are excluded. Used as a fallback when
     * no persisted flight segments exist in the database.
     */
    fun computeFlights(
        rows: List<CachedDetection>,
        simulatorKeys: Set<String>,
        faaText: Map<String, String> = emptyMap()
    ): List<FlightSummary> {
        val byKey = LinkedHashMap<String, MutableList<StatPoint>>()
        val macByKey = HashMap<String, String>()
        val basicIdByKey = HashMap<String, String>()
        for (r in rows) {
            if (!isValidPosition(r.droneLat, r.droneLon)) continue
            val key = r.basicId.ifBlank { r.mac }
            if (key in simulatorKeys) continue
            byKey.getOrPut(key) { mutableListOf() }
                .add(StatPoint(r.ts, r.droneLat, r.droneLon, r.droneAltitude))
            macByKey.putIfAbsent(key, r.mac)
            basicIdByKey.putIfAbsent(key, r.basicId)
        }
        val out = ArrayList<FlightSummary>()
        for ((key, pts) in byKey) {
            pts.sortBy { it.ts }
            val basicId = basicIdByKey[key].orEmpty().ifBlank { if (key.contains(':')) "" else key }
            val mac = macByKey[key] ?: key
            val (make, model) = DroneIdentity.resolve(basicId, mac, faaText[basicId])
            for (seg in groupFlights(pts)) {
                var dist = 0.0
                for (i in 1 until seg.size) {
                    val d = haversine(seg[i - 1].lat, seg[i - 1].lon, seg[i].lat, seg[i].lon)
                    if (d <= 0) continue
                    dist += d
                }
                var latSum = 0.0
                var lonSum = 0.0
                for (p in seg) {
                    latSum += p.lat
                    lonSum += p.lon
                }
                out.add(
                    FlightSummary(
                        droneKey = key,
                        make = make,
                        model = model,
                        startTs = seg.first().ts,
                        endTs = seg.last().ts,
                        durationMs = (seg.last().ts - seg.first().ts).coerceAtLeast(0),
                        distanceM = dist,
                        positions = seg.size,
                        centroidLat = latSum / seg.size,
                        centroidLon = lonSum / seg.size
                    )
                )
            }
        }
        out.sortByDescending { it.startTs }
        return out
    }

    /**
     * Build flight summaries from persisted flight segments derived at import
     * time from the log boundary markers (preferred over gap-based inference).
     */
    fun flightsFromDb(
        records: List<FlightRecord>,
        faaText: Map<String, String> = emptyMap(),
        simulatorKeys: Set<String> = emptySet()
    ): List<FlightSummary> {
        val out = ArrayList<FlightSummary>()
        for (rec in records) {
            if (rec.key in simulatorKeys) continue
            val key = rec.key
            val basicId = if (key.contains(':')) "" else key
            val (make, model) = DroneIdentity.resolve(basicId, "", faaText[basicId])
            out.add(
                FlightSummary(
                    droneKey = key,
                    make = make,
                    model = model,
                    startTs = rec.startTs,
                    endTs = rec.endTs,
                    durationMs = (rec.endTs - rec.startTs).coerceAtLeast(0),
                    distanceM = rec.distanceM,
                    positions = rec.nPoints,
                    centroidLat = (rec.startLat + rec.endLat) / 2.0,
                    centroidLon = (rec.startLon + rec.endLon) / 2.0
                )
            )
        }
        out.sortByDescending { it.startTs }
        return out
    }
}
