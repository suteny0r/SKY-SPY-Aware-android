package com.suteny0r.skyspyaware

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/** A persisted flight segment, derived at import from log boundary markers. */
data class FlightRecord(
    val key: String,
    val startTs: Long,
    val endTs: Long,
    val startLat: Double,
    val startLon: Double,
    val endLat: Double,
    val endLon: Double,
    val distanceM: Double,
    val nPoints: Int
)

/** A flight's full position series (used to draw its trail on a map). */
data class FlightTrail(
    val key: String,
    val startTs: Long,
    val endTs: Long,
    val points: List<TrailPoint>
)

/** A cached detection row with its arrival timestamp. */
data class CachedDetection(
    val ts: Long,
    val mac: String,
    val rssi: Int,
    val droneLat: Double,
    val droneLon: Double,
    val droneAltitude: Int,
    val pilotLat: Double,
    val pilotLon: Double,
    val basicId: String
) {
    fun toDetection() = Detection(
        mac, rssi, droneLat, droneLon, droneAltitude, pilotLat, pilotLon, basicId
    )
}

/**
 * Persistent store of incoming detections (SQLite). History is kept
 * indefinitely so long-range analysis stays possible. After a restart the app
 * rebuilds drone state for the recent history window from this store.
 */
class DetectionCache(context: Context) {

    private val appContext = context.applicationContext
    private val helper = DetectionDb(appContext)
    private var db: SQLiteDatabase = helper.writableDatabase
    private val lock = Any()

    fun insert(d: Detection, ts: Long) {
        val v = ContentValues().apply {
            put("ts", ts)
            put("mac", d.mac)
            put("rssi", d.rssi)
            put("drone_lat", d.droneLat)
            put("drone_lon", d.droneLon)
            put("drone_alt", d.droneAltitude)
            put("pilot_lat", d.pilotLat)
            put("pilot_lon", d.pilotLon)
            put("basic_id", d.basicId)
        }
        synchronized(lock) {
            db.insert("detections", null, v)
        }
    }

    fun loadSince(sinceMs: Long): List<CachedDetection> {
        val out = ArrayList<CachedDetection>()
        synchronized(lock) {
            db.query(
                "detections", null, "ts >= ?", arrayOf(sinceMs.toString()),
                null, null, "ts ASC"
            ).use { c ->
                val iTs = c.getColumnIndexOrThrow("ts")
                val iMac = c.getColumnIndexOrThrow("mac")
                val iRssi = c.getColumnIndexOrThrow("rssi")
                val iDlat = c.getColumnIndexOrThrow("drone_lat")
                val iDlon = c.getColumnIndexOrThrow("drone_lon")
                val iAlt = c.getColumnIndexOrThrow("drone_alt")
                val iPlat = c.getColumnIndexOrThrow("pilot_lat")
                val iPlon = c.getColumnIndexOrThrow("pilot_lon")
                val iBid = c.getColumnIndexOrThrow("basic_id")
                while (c.moveToNext()) {
                    out += CachedDetection(
                        ts = c.getLong(iTs),
                        mac = c.getString(iMac),
                        rssi = c.getInt(iRssi),
                        droneLat = c.getDouble(iDlat),
                        droneLon = c.getDouble(iDlon),
                        droneAltitude = c.getInt(iAlt),
                        pilotLat = c.getDouble(iPlat),
                        pilotLon = c.getDouble(iPlon),
                        basicId = c.getString(iBid)
                    )
                }
            }
        }
        return out
    }

    /** All distinct, non-empty basic_ids ever seen, for background registration. */
    fun distinctBasicIds(): Set<String> {
        val out = HashSet<String>()
        synchronized(lock) {
            db.rawQuery(
                "SELECT DISTINCT basic_id FROM detections",
                null
            ).use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: ""
                    if (id.isNotBlank()) out.add(id)
                }
            }
        }
        return out
    }

    /** Number of stored detections. */
    fun count(): Long {
        synchronized(lock) {
            db.rawQuery("SELECT COUNT(*) FROM detections", null).use { c ->
                return if (c.moveToFirst()) c.getLong(0) else 0L
            }
        }
    }

    /** Number of unique drones (distinct basic-id-or-mac keys) in history. */
    fun uniqueDroneCount(): Long {
        synchronized(lock) {
            db.rawQuery(
                "SELECT COUNT(*) FROM (" +
                    "SELECT COALESCE(NULLIF(basic_id, ''), mac) AS k FROM detections GROUP BY k" +
                    ")",
                null
            ).use { c ->
                return if (c.moveToFirst()) c.getLong(0) else 0L
            }
        }
    }

    /** Approximate on-disk size of the history database, in bytes. */
    fun dbSizeBytes(): Long = try {
        File(db.path).length()
    } catch (_: Exception) {
        0L
    }

    /** Delete all stored detections (and completed flights) older than [beforeMs]. */
    fun prune(beforeMs: Long) {
        synchronized(lock) {
            db.delete("detections", "ts < ?", arrayOf(beforeMs.toString()))
            try {
                db.delete("flights", "end_ts < ?", arrayOf(beforeMs.toString()))
            } catch (_: Exception) {
                // flights table missing in an older DB.
            }
        }
    }

    /** Delete every stored detection and flight and reclaim the disk space. */
    fun purge(): Long {
        synchronized(lock) {
            val removed = db.delete("detections", null, null).toLong()
            try {
                db.delete("flights", null, null)
            } catch (_: Exception) {
            }
            try {
                db.execSQL("VACUUM")
            } catch (_: Exception) {
            }
            return removed
        }
    }

    /** Persisted registration lookups (basic_id -> display text). */
    fun loadFaaCache(): Map<String, String> {
        val out = HashMap<String, String>()
        synchronized(lock) {
            db.query("faa_cache", arrayOf("basic_id", "result"), null, null, null, null, null)
                .use { c ->
                    while (c.moveToNext()) out[c.getString(0)] = c.getString(1)
                }
        }
        return out
    }

    /** Persisted public-safety platform labels (basic_id -> label). */
    fun loadFaaPlatforms(): Map<String, String> {
        val out = HashMap<String, String>()
        synchronized(lock) {
            db.query("faa_cache", arrayOf("basic_id", "platform"), null, null, null, null, null)
                .use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(0)
                        val label = c.getString(1)
                        if (label != null && label.isNotBlank()) out[id] = label
                    }
                }
        }
        return out
    }

    fun saveFaaCache(basicId: String, result: String, platform: String?) {
        val v = ContentValues().apply {
            put("basic_id", basicId)
            put("result", result)
            put("platform", platform)
        }
        synchronized(lock) {
            db.insertWithOnConflict("faa_cache", null, v, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    fun clearFaaCache() {
        synchronized(lock) {
            db.delete("faa_cache", null, null)
        }
    }

    /** Cached satellite object-detection results (drone key -> counts JSON + ts). */
    fun loadSatelliteCache(): Map<String, Pair<String, Long>> {
        val out = HashMap<String, Pair<String, Long>>()
        synchronized(lock) {
            db.query(
                "satellite_cache", arrayOf("drone_key", "counts", "ts"),
                null, null, null, null, null
            ).use { c ->
                while (c.moveToNext()) {
                    val key = c.getString(0)
                    val counts = c.getString(1)
                    val ts = c.getLong(2)
                    out[key] = (counts ?: "") to ts
                }
            }
        }
        return out
    }

    fun saveSatelliteCache(droneKey: String, countsJson: String, ts: Long) {
        val v = ContentValues().apply {
            put("drone_key", droneKey)
            put("counts", countsJson)
            put("ts", ts)
        }
        synchronized(lock) {
            db.insertWithOnConflict("satellite_cache", null, v, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    fun clearSatelliteCache() {
        synchronized(lock) {
            db.delete("satellite_cache", null, null)
        }
    }

    /** Write a consistent snapshot of the database to [out]. */
    fun exportTo(out: OutputStream): Boolean {
        synchronized(lock) {
            return try {
                // Fold the WAL into the main file first, or the copy silently
                // misses everything committed since the last autocheckpoint.
                try {
                    db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
                } catch (_: Exception) {
                }
                FileInputStream(File(db.path)).use { input -> input.copyTo(out) }
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    /** Replace the database with the contents of [input] and reopen it. */
    fun importFrom(input: InputStream): Boolean {
        synchronized(lock) {
            val temp = File(appContext.cacheDir, "import.db")
            try {
                FileOutputStream(temp).use { out -> input.copyTo(out) }
                // Validate the candidate BEFORE closing/replacing the live DB:
                // it must be a readable SQLite file, at or below our schema
                // version (default onDowngrade throws), with a detections table.
                SQLiteDatabase.openDatabase(
                    temp.path, null, SQLiteDatabase.OPEN_READONLY
                ).use { cand ->
                    val ver = cand.rawQuery("PRAGMA user_version", null).use { c ->
                        if (c.moveToFirst()) c.getInt(0) else 0
                    }
                    if (ver > DB_VERSION) return false
                    cand.rawQuery("SELECT ts FROM detections LIMIT 1", null).use { }
                }
            } catch (_: Exception) {
                temp.delete()
                return false
            }
            return try {
                db.close()
                val target = File(db.path)
                if (target.exists()) target.delete()
                // Drop stale sidecars so the imported main file isn't paired
                // with the previous database's WAL/journal.
                File(target.path + "-wal").delete()
                File(target.path + "-shm").delete()
                File(target.path + "-journal").delete()
                temp.copyTo(target, overwrite = true)
                db = helper.writableDatabase
                true
            } catch (_: Exception) {
                // Never leave the cache on a closed handle: reopen (recreating
                // if necessary) so live detections keep persisting.
                try {
                    db = helper.writableDatabase
                } catch (_: Exception) {
                }
                false
            } finally {
                temp.delete()
            }
        }
    }

    /** Full ordered trail for one drone (key = basicId or mac). */
    fun loadDroneTrail(key: String): List<TrailPoint> {
        val out = ArrayList<TrailPoint>()
        synchronized(lock) {
            db.query(
                "detections",
                arrayOf("ts", "drone_lat", "drone_lon"),
                "basic_id = ? OR (mac = ? AND (basic_id = '' OR basic_id IS NULL))",
                arrayOf(key, key),
                null, null, "ts ASC"
            ).use { c ->
                val iTs = c.getColumnIndexOrThrow("ts")
                val iLat = c.getColumnIndexOrThrow("drone_lat")
                val iLon = c.getColumnIndexOrThrow("drone_lon")
                while (c.moveToNext()) {
                    val lat = c.getDouble(iLat)
                    val lon = c.getDouble(iLon)
                    if (!isValidPosition(lat, lon)) continue
                    out.add(TrailPoint(c.getLong(iTs), lat, lon))
                }
            }
        }
        return out
    }

    /** Position fixes for one drone within [fromTs, toTs] (a single flight). */
    fun loadDroneTrail(key: String, fromTs: Long, toTs: Long): List<TrailPoint> {
        val out = ArrayList<TrailPoint>()
        synchronized(lock) {
            db.query(
                "detections",
                arrayOf("ts", "drone_lat", "drone_lon"),
                "(basic_id = ? OR (mac = ? AND (basic_id = '' OR basic_id IS NULL))) AND ts >= ? AND ts <= ?",
                arrayOf(key, key, fromTs.toString(), toTs.toString()),
                null, null, "ts ASC"
            ).use { c ->
                val iTs = c.getColumnIndexOrThrow("ts")
                val iLat = c.getColumnIndexOrThrow("drone_lat")
                val iLon = c.getColumnIndexOrThrow("drone_lon")
                while (c.moveToNext()) {
                    val lat = c.getDouble(iLat)
                    val lon = c.getDouble(iLon)
                    if (!isValidPosition(lat, lon)) continue
                    out.add(TrailPoint(c.getLong(iTs), lat, lon))
                }
            }
        }
        return out
    }

    /** Full ordered trail plus pilot positions for one drone. */
    fun loadDroneTrailWithPilot(key: String): List<TrailPointWithPilot> {
        val out = ArrayList<TrailPointWithPilot>()
        synchronized(lock) {
            db.query(
                "detections",
                arrayOf("ts", "drone_lat", "drone_lon", "drone_alt", "pilot_lat", "pilot_lon"),
                "basic_id = ? OR (mac = ? AND (basic_id = '' OR basic_id IS NULL))",
                arrayOf(key, key),
                null, null, "ts ASC"
            ).use { c ->
                val iTs = c.getColumnIndexOrThrow("ts")
                val iLat = c.getColumnIndexOrThrow("drone_lat")
                val iLon = c.getColumnIndexOrThrow("drone_lon")
                val iAlt = c.getColumnIndexOrThrow("drone_alt")
                val iPilLat = c.getColumnIndexOrThrow("pilot_lat")
                val iPilLon = c.getColumnIndexOrThrow("pilot_lon")
                while (c.moveToNext()) {
                    val lat = c.getDouble(iLat)
                    val lon = c.getDouble(iLon)
                    if (!isValidPosition(lat, lon)) continue
                    out.add(
                        TrailPointWithPilot(
                            c.getLong(iTs), lat, lon,
                            c.getInt(iAlt),
                            c.getDouble(iPilLat), c.getDouble(iPilLon)
                        )
                    )
                }
            }
        }
        return out
    }

    /** Trail plus pilot positions for one drone within [fromTs, toTs]. */
    fun loadDroneTrailWithPilot(key: String, fromTs: Long, toTs: Long): List<TrailPointWithPilot> {
        val out = ArrayList<TrailPointWithPilot>()
        synchronized(lock) {
            db.query(
                "detections",
                arrayOf("ts", "drone_lat", "drone_lon", "drone_alt", "pilot_lat", "pilot_lon"),
                "(basic_id = ? OR (mac = ? AND (basic_id = '' OR basic_id IS NULL))) AND ts >= ? AND ts <= ?",
                arrayOf(key, key, fromTs.toString(), toTs.toString()),
                null, null, "ts ASC"
            ).use { c ->
                val iTs = c.getColumnIndexOrThrow("ts")
                val iLat = c.getColumnIndexOrThrow("drone_lat")
                val iLon = c.getColumnIndexOrThrow("drone_lon")
                val iAlt = c.getColumnIndexOrThrow("drone_alt")
                val iPilLat = c.getColumnIndexOrThrow("pilot_lat")
                val iPilLon = c.getColumnIndexOrThrow("pilot_lon")
                while (c.moveToNext()) {
                    val lat = c.getDouble(iLat)
                    val lon = c.getDouble(iLon)
                    if (!isValidPosition(lat, lon)) continue
                    out.add(
                        TrailPointWithPilot(
                            c.getLong(iTs), lat, lon,
                            c.getInt(iAlt),
                            c.getDouble(iPilLat), c.getDouble(iPilLon)
                        )
                    )
                }
            }
        }
        return out
    }

    /** All persisted flight segments, newest first. Empty if no flights table. */
    fun loadFlights(): List<FlightRecord> {
        val out = ArrayList<FlightRecord>()
        synchronized(lock) {
            try {
                db.query(
                    "flights",
                    arrayOf(
                        "key", "start_ts", "end_ts", "start_lat", "start_lon",
                        "end_lat", "end_lon", "distance_m", "n_points"
                    ),
                    null, null, null, null, "start_ts DESC"
                ).use { c ->
                    val iKey = c.getColumnIndexOrThrow("key")
                    val iStart = c.getColumnIndexOrThrow("start_ts")
                    val iEnd = c.getColumnIndexOrThrow("end_ts")
                    val iSLat = c.getColumnIndexOrThrow("start_lat")
                    val iSLon = c.getColumnIndexOrThrow("start_lon")
                    val iELat = c.getColumnIndexOrThrow("end_lat")
                    val iELon = c.getColumnIndexOrThrow("end_lon")
                    val iDist = c.getColumnIndexOrThrow("distance_m")
                    val iN = c.getColumnIndexOrThrow("n_points")
                    while (c.moveToNext()) {
                        out.add(
                            FlightRecord(
                                c.getString(iKey),
                                c.getLong(iStart),
                                c.getLong(iEnd),
                                c.getDouble(iSLat),
                                c.getDouble(iSLon),
                                c.getDouble(iELat),
                                c.getDouble(iELon),
                                c.getDouble(iDist),
                                c.getInt(iN)
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                // flights table missing in an older DB: fall back to gap-based.
            }
        }
        return out
    }

    /**
     * All flight trails across every drone, one entry per flight segment.
     * A flight is a continuous run of fixes for one key separated by more than
     * [gapMs]. Used by the Flights tab "All" view to overlay every trail.
     */
    fun loadAllFlightTrails(gapMs: Long = 5 * 60 * 1000L): List<FlightTrail> {
        val byKey = LinkedHashMap<String, MutableList<TrailPoint>>()
        synchronized(lock) {
            db.query(
                "detections",
                arrayOf("ts", "basic_id", "mac", "drone_lat", "drone_lon"),
                null, null, null, null, "ts ASC"
            ).use { c ->
                val iTs = c.getColumnIndexOrThrow("ts")
                val iBid = c.getColumnIndexOrThrow("basic_id")
                val iMac = c.getColumnIndexOrThrow("mac")
                val iLat = c.getColumnIndexOrThrow("drone_lat")
                val iLon = c.getColumnIndexOrThrow("drone_lon")
                while (c.moveToNext()) {
                    val lat = c.getDouble(iLat)
                    val lon = c.getDouble(iLon)
                    if (!isValidPosition(lat, lon)) continue
                    val bid = c.getString(iBid)
                    val mac = c.getString(iMac)
                    val key = if (!bid.isNullOrBlank()) bid else mac ?: ""
                    if (key.isEmpty()) continue
                    byKey.getOrPut(key) { mutableListOf() }
                        .add(TrailPoint(c.getLong(iTs), lat, lon))
                }
            }
        }
        val out = ArrayList<FlightTrail>()
        for ((key, pts) in byKey) {
            pts.sortBy { it.ts }
            var segStart = 0
            for (i in 1..pts.size) {
                if (i == pts.size || pts[i].ts - pts[i - 1].ts > gapMs) {
                    val seg = pts.subList(segStart, i)
                    if (seg.size >= 2) {
                        out.add(
                            FlightTrail(
                                key = key,
                                startTs = seg.first().ts,
                                endTs = seg.last().ts,
                                points = seg.toList()
                            )
                        )
                    }
                    segStart = i
                }
            }
        }
        out.sortByDescending { it.startTs }
        return out
    }

    /** All persisted notes (drone key -> note). Empty map if table missing. */
    fun loadDroneNotes(): Map<String, String> {
        val out = HashMap<String, String>()
        synchronized(lock) {
            try {
                db.query("drone_notes", arrayOf("key", "note"), null, null, null, null, null).use { c ->
                    val iKey = c.getColumnIndexOrThrow("key")
                    val iNote = c.getColumnIndexOrThrow("note")
                    while (c.moveToNext()) out[c.getString(iKey)] = c.getString(iNote)
                }
            } catch (_: Exception) {
                // drone_notes table missing in an older DB.
            }
        }
        return out
    }

    /** Save (or replace) a note for one drone. */
    fun saveDroneNote(key: String, note: String) {
        val v = ContentValues().apply {
            put("key", key)
            put("note", note)
            put("updated_ts", System.currentTimeMillis())
        }
        synchronized(lock) {
            try {
                db.insertWithOnConflict(
                    "drone_notes", null, v, SQLiteDatabase.CONFLICT_REPLACE
                )
            } catch (_: Exception) {
            }
        }
    }

    /** Remove any saved note for one drone. */
    fun clearDroneNote(key: String) {
        synchronized(lock) {
            try {
                db.delete("drone_notes", "key = ?", arrayOf(key))
            } catch (_: Exception) {
            }
        }
    }

    /** Remember a note value globally (shared prefill history for all drones). */
    fun recordNoteHistory(note: String) {
        val v = ContentValues().apply {
            put("note", note)
            put("updated_ts", System.currentTimeMillis())
        }
        synchronized(lock) {
            try {
                db.insertWithOnConflict(
                    "note_history", null, v, SQLiteDatabase.CONFLICT_REPLACE
                )
            } catch (_: Exception) {
            }
        }
    }

    /** Most-recently-used note values (global prefills), newest first. */
    fun loadNoteSuggestions(limit: Int): List<String> {
        val out = ArrayList<String>()
        synchronized(lock) {
            try {
                db.query(
                    "note_history", arrayOf("note"),
                    null, null, null, null, "updated_ts DESC", limit.toString()
                ).use { c ->
                    val iNote = c.getColumnIndexOrThrow("note")
                    while (c.moveToNext()) out.add(c.getString(iNote))
                }
            } catch (_: Exception) {
                // note_history table missing in an older DB.
            }
        }
        return out
    }

    private companion object {
        const val DB_VERSION = 7
    }

    private class DetectionDb(context: Context) :
        SQLiteOpenHelper(context, "detections.db", null, DB_VERSION) {

        // IF NOT EXISTS throughout: an imported DB written by an external tool
        // can carry user_version 0 with our tables already present, which
        // routes through onCreate.
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS detections (" +
                    "ts INTEGER NOT NULL," +
                    "mac TEXT NOT NULL," +
                    "rssi INTEGER," +
                    "drone_lat REAL," +
                    "drone_lon REAL," +
                    "drone_alt INTEGER," +
                    "pilot_lat REAL," +
                    "pilot_lon REAL," +
                    "basic_id TEXT)"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_ts ON detections(ts)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS faa_cache (basic_id TEXT PRIMARY KEY, result TEXT, platform TEXT)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS satellite_cache (" +
                    "drone_key TEXT PRIMARY KEY, counts TEXT, ts INTEGER)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS flights (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "key TEXT NOT NULL," +
                    "start_ts INTEGER," +
                    "end_ts INTEGER," +
                    "start_lat REAL," +
                    "start_lon REAL," +
                    "end_lat REAL," +
                    "end_lon REAL," +
                    "distance_m REAL," +
                    "n_points INTEGER)"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_flights_key ON flights(key)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_flights_start ON flights(start_ts)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS drone_notes (" +
                    "key TEXT PRIMARY KEY," +
                    "note TEXT NOT NULL," +
                    "updated_ts INTEGER)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS note_history (" +
                    "note TEXT PRIMARY KEY," +
                    "updated_ts INTEGER)"
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                db.execSQL("CREATE TABLE faa_cache (basic_id TEXT PRIMARY KEY, result TEXT, platform TEXT)")
            } else if (oldVersion < 3) {
                db.execSQL("ALTER TABLE faa_cache ADD COLUMN platform TEXT")
            }
            if (oldVersion < 4) {
                db.execSQL(
                    "CREATE TABLE satellite_cache (" +
                        "drone_key TEXT PRIMARY KEY, counts TEXT, ts INTEGER)"
                )
            }
            if (oldVersion < 5) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS flights (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                        "key TEXT NOT NULL," +
                        "start_ts INTEGER," +
                        "end_ts INTEGER," +
                        "start_lat REAL," +
                        "start_lon REAL," +
                        "end_lat REAL," +
                        "end_lon REAL," +
                        "distance_m REAL," +
                        "n_points INTEGER)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_flights_key ON flights(key)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_flights_start ON flights(start_ts)")
            }
            if (oldVersion < 6) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS drone_notes (" +
                        "key TEXT PRIMARY KEY," +
                        "note TEXT NOT NULL," +
                        "updated_ts INTEGER)"
                )
            }
            if (oldVersion < 7) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS note_history (" +
                        "note TEXT PRIMARY KEY," +
                        "updated_ts INTEGER)"
                )
            }
        }
    }
}
