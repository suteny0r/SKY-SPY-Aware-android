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

    /** Delete all stored detections older than [beforeMs]. */
    fun prune(beforeMs: Long) {
        synchronized(lock) {
            db.delete("detections", "ts < ?", arrayOf(beforeMs.toString()))
        }
    }

    /** Delete every stored detection and reclaim the disk space. */
    fun purge(): Long {
        synchronized(lock) {
            val removed = db.delete("detections", null, null).toLong()
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
            return try {
                val temp = File(appContext.cacheDir, "import.db")
                FileOutputStream(temp).use { out -> input.copyTo(out) }
                db.close()
                val target = File(db.path)
                if (target.exists()) target.delete()
                temp.copyTo(target, overwrite = true)
                db = helper.writableDatabase
                true
            } catch (_: Exception) {
                false
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

    private class DetectionDb(context: Context) :
        SQLiteOpenHelper(context, "detections.db", null, 4) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE detections (" +
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
            db.execSQL("CREATE INDEX idx_ts ON detections(ts)")
            db.execSQL(
                "CREATE TABLE faa_cache (basic_id TEXT PRIMARY KEY, result TEXT, platform TEXT)"
            )
            db.execSQL(
                "CREATE TABLE satellite_cache (" +
                    "drone_key TEXT PRIMARY KEY, counts TEXT, ts INTEGER)"
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
        }
    }
}
