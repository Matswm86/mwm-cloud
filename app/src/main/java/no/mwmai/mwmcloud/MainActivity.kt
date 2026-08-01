package no.mwmai.mwmcloud

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import no.mwmai.mwmcloud.ui.theme.DesignSwatch
import no.mwmai.mwmcloud.ui.theme.MwmCloudTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MwmCloudTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { inner ->
                    // Temporary. Shows the design swatch so tokens, fonts and icons
                    // can be checked against the graphics pack on a real device.
                    // Replaced by the Velkommen screen when the screens land.
                    DesignSwatch(Modifier.padding(inner))
                }
            }
        }
    }
}
