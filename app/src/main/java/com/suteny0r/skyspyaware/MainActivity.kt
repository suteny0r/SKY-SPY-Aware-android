package com.suteny0r.skyspyaware

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.suteny0r.skyspyaware.ui.AppRoot
import com.suteny0r.skyspyaware.ui.SkySpyTheme
import org.osmdroid.config.Configuration

class MainActivity : ComponentActivity() {

    private val vm: SkySpyViewModel by viewModels()

    private val locationPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) LocationController.refresh(applicationContext)
        }

    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // osmdroid requires an explicit User-Agent or tile servers reject the
        // request with HTTP 400 (getUserAgentValue() is null otherwise).
        val prefs = androidx.preference.PreferenceManager
            .getDefaultSharedPreferences(applicationContext)
        Configuration.getInstance().load(applicationContext, prefs)
        Configuration.getInstance().userAgentValue =
            "SKY-SPY-Aware/1.0 (Android; +https://github.com/suteny0r/SKY-SPY-Aware-android)"

        val fineGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (fineGranted) {
            LocationController.refresh(applicationContext)
        } else {
            locationPermLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        // Needed on Android 13+ for the foreground "collecting" notification
        // and new-drone alerts.
        if (Build.VERSION.SDK_INT >= 33) {
            val notifGranted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!notifGranted) notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            SkySpyTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot(vm)
                }
            }
        }
    }
}
