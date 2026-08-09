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
 * Simple device-location holder. Call [refresh] after the location permission
 * is granted; the latest fix is exposed as [location].
 */
object LocationController {

    private val _location = MutableStateFlow<GeoPoint?>(null)
    val location: StateFlow<GeoPoint?> = _location

    @SuppressLint("MissingPermission")
    fun refresh(context: Context) {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER
        )
        val last = providers.firstNotNullOfOrNull { provider ->
            runCatching { lm.getLastKnownLocation(provider) }.getOrNull()
        }
        if (last != null) {
            _location.value = GeoPoint(last.latitude, last.longitude)
            return
        }
        for (provider in providers) {
            try {
                lm.requestSingleUpdate(
                    provider,
                    object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            _location.value = GeoPoint(location.latitude, location.longitude)
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
                break
            } catch (_: SecurityException) {
            } catch (_: IllegalArgumentException) {
            }
        }
    }
}
