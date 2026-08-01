package no.mwmai.mwmcloud.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Design tokens from the MWM Cloud graphics pack. These are the only place hex
 * literals appear; screens must reference [MwmColors] or the Material scheme.
 */
object MwmColors {
    /** Primary action. Buttons, active tabs, progress. */
    val Action = Color(0xFF1F6FC4)

    /** "Trygt" — everything is backed up. */
    val Safe = Color(0xFF1F9E6E)

    /** "Trenger deg" — needs the user to do something. Never used for errors. */
    val Attention = Color(0xFFE4A11B)

    val Text = Color(0xFF14212B)
    val Background = Color(0xFFFBF8F3)
    val Border = Color(0xFFEFE8DD)

    /** Tinted surface behind action-coloured content. */
    val ActionTint = Color(0xFFDCEBFA)

    /** Secondary text. Must not be used below 15 sp. */
    val Muted = Color(0xFF8A9299)
}

/** Non-colour tokens the design fixes explicitly. */
object MwmDimens {
    /** Buttons are 68 dp tall throughout — deliberately large for older users. */
    val ButtonHeight = 68.dp

    /** Nothing renders below this. The design's hard floor. */
    val MinTextSize = 15.sp

    val ScreenPadding = 24.dp
    val CardRadius = 20.dp
    val CardSpacing = 16.dp
}

private val MwmLightColors = lightColorScheme(
    primary = MwmColors.Action,
    onPrimary = Color.White,
    primaryContainer = MwmColors.ActionTint,
    onPrimaryContainer = MwmColors.Text,
    secondary = MwmColors.Safe,
    onSecondary = Color.White,
    tertiary = MwmColors.Attention,
    onTertiary = Color.White,
    background = MwmColors.Background,
    onBackground = MwmColors.Text,
    surface = Color.White,
    onSurface = MwmColors.Text,
    surfaceVariant = MwmColors.Background,
    onSurfaceVariant = MwmColors.Muted,
    outline = MwmColors.Border,
)

/**
 * Light-only by design. A dark scheme is not in the graphics pack, and inventing
 * one would put unreviewed colours in front of the people this app is for.
 * [isSystemInDarkTheme] is intentionally ignored until a dark palette is designed.
 */
@Composable
fun MwmCloudTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MwmLightColors,
        typography = MwmTypography,
        content = content,
    )
}
