package no.mwmai.mwmcloud.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the two design rules that are easy to break by accident and expensive to
 * notice: the palette drifting from the graphics pack, and text creeping below the
 * legibility floor.
 */
class DesignTokenTest {

    /** Hex values transcribed from design/grafikk/palett.png and LES-MEG.txt. */
    private val expectedPalette = mapOf(
        "Action" to 0xFF1F6FC4,
        "Safe" to 0xFF1F9E6E,
        "Attention" to 0xFFE4A11B,
        "Text" to 0xFF14212B,
        "Background" to 0xFFFBF8F3,
        "Border" to 0xFFEFE8DD,
        "ActionTint" to 0xFFDCEBFA,
        "Muted" to 0xFF8A9299,
    )

    private val actualPalette = mapOf(
        "Action" to MwmColors.Action,
        "Safe" to MwmColors.Safe,
        "Attention" to MwmColors.Attention,
        "Text" to MwmColors.Text,
        "Background" to MwmColors.Background,
        "Border" to MwmColors.Border,
        "ActionTint" to MwmColors.ActionTint,
        "Muted" to MwmColors.Muted,
    )

    @Test
    fun `palette matches the graphics pack`() {
        expectedPalette.forEach { (name, argb) ->
            assertEquals("$name drifted from the design pack", Color(argb), actualPalette.getValue(name))
        }
    }

    @Test
    fun `no text style drops below the 15sp legibility floor`() {
        val floor = MwmDimens.MinTextSize.value
        typographyStyles(MwmTypography).forEach { (name, style) ->
            val size = style.fontSize.value
            assertTrue(
                "$name is ${size}sp, below the ${floor}sp floor. That floor exists " +
                    "because the intended reader is often holding the phone at arm's length.",
                size >= floor,
            )
        }
    }

    @Test
    fun `line height always exceeds font size`() {
        typographyStyles(MwmTypography).forEach { (name, style) ->
            assertTrue(
                "$name has lineHeight ${style.lineHeight.value} <= fontSize ${style.fontSize.value}",
                style.lineHeight.value > style.fontSize.value,
            )
        }
    }

    /** Only the styles the app actually defines; Material's untouched defaults are not ours to police. */
    private fun typographyStyles(t: Typography): List<Pair<String, TextStyle>> = listOf(
        "displaySmall" to t.displaySmall,
        "headlineLarge" to t.headlineLarge,
        "headlineMedium" to t.headlineMedium,
        "titleLarge" to t.titleLarge,
        "bodyLarge" to t.bodyLarge,
        "bodyMedium" to t.bodyMedium,
        "labelLarge" to t.labelLarge,
        "labelMedium" to t.labelMedium,
    )
}
