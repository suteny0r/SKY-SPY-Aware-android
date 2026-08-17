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

    private const val REQUEST_TIMEOUT_MS = 30_000L

    // All callers waiting on the in-flight single-update request. A single
    // slot would drop the first caller's callback when a second refresh
    // arrives before the fix does.
    private val pendingCallbacks = ArrayList<(GeoPoint?) -> Unit>()
    private var requestInFlight = false

    private fun flushCallbacks(p: GeoPoint?) {
        val cbs = synchronized(pendingCallbacks) {
            requestInFlight = false
            val copy = pendingCallbacks.toList()
            pendingCallbacks.clear()
            copy
        }
        cbs.forEach { it(p) }
    }

    /** Any last known position from any provider, regardless of age, or null. */
    fun lastKnown(context: Context): GeoPoint? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER
        ).firstNotNullOfOrNull { provider ->
            runCatching { lm.getLastKnownLocation(provider) }.getOrNull()
                ?.let { GeoPoint(it.latitude, it.longitude) }
        }
    }

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
        val alreadyInFlight = synchronized(pendingCallbacks) {
            if (onResult != null) pendingCallbacks.add(onResult)
            val was = requestInFlight
            requestInFlight = true
            was
        }
        if (alreadyInFlight) return

        var requested = false
        for (provider in providers) {
            try {
                lm.requestSingleUpdate(
                    provider,
                    object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            _location.value = GeoPoint(location.latitude, location.longitude)
                            flushCallbacks(_location.value)
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
            flushCallbacks(_location.value)
            return
        }
        // If no fix ever arrives, release the waiters with whatever we have
        // instead of leaving them (and the provider request) pending forever.
        android.os.Handler(Looper.getMainLooper()).postDelayed({
            val stillWaiting = synchronized(pendingCallbacks) { requestInFlight }
            if (stillWaiting) flushCallbacks(_location.value)
        }, REQUEST_TIMEOUT_MS)
    }
}
