package no.mwmai.mwmcloud.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.first
import no.mwmai.mwmcloud.Graph
import no.mwmai.mwmcloud.data.media.MediaCategory
import no.mwmai.mwmcloud.ui.browse.BrowseScreen
import no.mwmai.mwmcloud.ui.folders.FoldersScreen
import no.mwmai.mwmcloud.ui.home.HomeScreen
import no.mwmai.mwmcloud.ui.setup.SetupScreen
import no.mwmai.mwmcloud.ui.welcome.WelcomeScreen

private object Routes {
    const val WELCOME = "welcome"
    const val SETUP = "setup"
    const val FOLDERS = "folders"
    const val HOME = "home"
    const val BROWSE = "browse"
}

@Composable
fun AppNav(modifier: Modifier = Modifier) {
    val nav = rememberNavController()
    val context = LocalContext.current

    // Someone who has already set the app up should land on Hjem, not be walked
    // through onboarding again every launch. Null means "still deciding", which
    // renders nothing rather than flashing the wrong screen first.
    val start by produceState<String?>(initialValue = null) {
        value = if (Graph.settings(context).setupComplete.first()) Routes.HOME else Routes.WELCOME
    }

    val startRoute = start ?: return

    NavHost(navController = nav, startDestination = startRoute, modifier = modifier) {
        composable(Routes.WELCOME) {
            WelcomeScreen(onStart = { nav.navigate(Routes.SETUP) })
        }
        composable(Routes.SETUP) {
            SetupScreen(
                onConnected = {
                    nav.navigate(Routes.FOLDERS) { popUpTo(Routes.WELCOME) { inclusive = true } }
                },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.FOLDERS) {
            FoldersScreen(
                onDone = {
                    nav.navigate(Routes.HOME) { popUpTo(Routes.FOLDERS) { inclusive = true } }
                },
                onBrowse = { category -> nav.navigate("${Routes.BROWSE}/${category.name}") },
            )
        }
        composable("${Routes.BROWSE}/{category}") { entry ->
            val category = runCatching {
                MediaCategory.valueOf(entry.arguments?.getString("category").orEmpty())
            }.getOrNull() ?: MediaCategory.IMAGES
            BrowseScreen(category = category, onBack = { nav.popBackStack() })
        }
        composable(Routes.HOME) {
            HomeScreen(onChangeFolders = { nav.navigate(Routes.FOLDERS) })
        }
    }
}
