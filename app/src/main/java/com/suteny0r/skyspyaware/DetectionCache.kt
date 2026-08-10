package com.suteny0r.skyspyaware

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

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
 * Persistent ring buffer of incoming detections (SQLite). All data is kept
 * for [RETAIN_MS] so the app can rebuild drone state after a restart and show
 * history up to 24 hours old.
 */
class DetectionCache(context: Context) {

    private val db: SQLiteDatabase = DetectionDb(context).writableDatabase
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

    fun prune(beforeMs: Long) {
        synchronized(lock) {
            db.delete("detections", "ts < ?", arrayOf(beforeMs.toString()))
        }
    }

    private class DetectionDb(context: Context) :
        SQLiteOpenHelper(context, "detections.db", null, 1) {

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
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    }

    companion object {
        const val RETAIN_MS = 24L * 60 * 60 * 1000
    }
}
