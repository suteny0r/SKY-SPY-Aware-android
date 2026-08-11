package com.suteny0r.skyspyaware

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.osmdroid.util.GeoPoint

/**
 * Device-location holder. [refresh] never centers on a stale cached fix: if a
 * fresh fix (under [FRESH_MS]) is available it is used immediately, otherwise a
 * fresh single update is requested and [onResult] is called when it arrives.
 */
object LocationController {

    private const val FRESH_MS = 30_000L

    private val _location = MutableStateFlow<GeoPoint?>(null)
    val location: StateFlow<GeoPoint?> = _location

    private var pendingCallback: ((GeoPoint?) -> Unit)? = null

    @SuppressLint("MissingPermission")
    fun refresh(context: Context, onResult: ((GeoPoint?) -> Unit)? = null) {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER
        )

        // A fix newer than FRESH_MS is fine to use immediately.
        val recent = providers.firstNotNullOfOrNull { provider ->
            runCatching { lm.getLastKnownLocation(provider) }.getOrNull()
                ?.takeIf { System.currentTimeMillis() - it.time < FRESH_MS }
        }
        if (recent != null) {
            _location.value = GeoPoint(recent.latitude, recent.longitude)
            onResult?.invoke(_location.value)
            return
        }

        // Otherwise request a fresh fix and report it when it arrives.
        pendingCallback = onResult
        var requested = false
        for (provider in providers) {
            try {
                lm.requestSingleUpdate(
                    provider,
                    object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            _location.value = GeoPoint(location.latitude, location.longitude)
                            val cb = pendingCallback
                            pendingCallback = null
                            cb?.invoke(_location.value)
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onStatusChanged(
                            provider: String?, status: Int, extras: Bundle?
                        ) {
                        }

                        override fun onProviderEnabled(provider: String) {}
                        override fun onProviderDisabled(provider: String) {}
                    },
                    Looper.getMainLooper()
                )
                requested = true
                break
            } catch (_: SecurityException) {
            } catch (_: IllegalArgumentException) {
            }
        }
        if (!requested) {
            pendingCallback = null
            onResult?.invoke(_location.value)
        }
    }
}
