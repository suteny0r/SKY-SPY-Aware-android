package com.suteny0r.skyspyaware

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.suteny0r.skyspyaware.ui.AppRoot
import com.suteny0r.skyspyaware.ui.SkySpyTheme
import org.osmdroid.config.Configuration

class MainActivity : ComponentActivity() {

    private val vm: SkySpyViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(
            applicationContext,
            androidx.preference.PreferenceManager
                .getDefaultSharedPreferences(applicationContext)
        )
        setContent {
            SkySpyTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot(vm)
                }
            }
        }
    }
}
