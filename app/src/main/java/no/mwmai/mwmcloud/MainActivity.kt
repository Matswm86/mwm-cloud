package no.mwmai.mwmcloud

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import no.mwmai.mwmcloud.ui.AppNav
import no.mwmai.mwmcloud.ui.theme.AppLanguage
import no.mwmai.mwmcloud.ui.theme.MwmCloudTheme
import no.mwmai.mwmcloud.ui.theme.WithAppLanguage

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // Wrapped outside the theme and the navigation graph, so a language
            // change redraws every screen at once instead of leaving whichever
            // one is on top in the old language until it is revisited.
            val context = LocalContext.current
            val language by Graph.settings(context).language
                .collectAsState(initial = AppLanguage.SYSTEM)

            WithAppLanguage(language) {
                MwmCloudTheme {
                    Scaffold(modifier = Modifier.fillMaxSize()) { inner ->
                        AppNav(Modifier.padding(inner))
                    }
                }
            }
        }
    }
}
