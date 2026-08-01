package no.mwmai.mwmcloud.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import no.mwmai.mwmcloud.ui.theme.MwmColors
import no.mwmai.mwmcloud.ui.theme.MwmDimens

/**
 * The design's primary button: full width, 68 dp tall, action blue. Height is not
 * negotiable per screen; it is a legibility and reachability requirement.
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    color: Color = MwmColors.Action,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        modifier = modifier.fillMaxWidth().height(MwmDimens.ButtonHeight),
        shape = MaterialTheme.shapes.large,
        colors = ButtonDefaults.buttonColors(
            containerColor = color,
            contentColor = Color.White,
            disabledContainerColor = MwmColors.Border,
            disabledContentColor = MwmColors.Muted,
        ),
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.height(24.dp),
                color = Color.White,
                strokeWidth = 2.dp,
            )
        } else {
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** The design's secondary button: white fill, hairline border, same height. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(MwmDimens.ButtonHeight),
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MwmColors.Border),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = Color.White,
            contentColor = MwmColors.Text,
        ),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** Formats bytes the way the design does: "38 GB", "0,4 GB". Norwegian decimal comma. */
fun formatBytes(bytes: Long): String {
    val gb = bytes / 1_073_741_824.0
    val mb = bytes / 1_048_576.0
    return when {
        gb >= 10 -> "${gb.toInt()} GB"
        gb >= 1 -> String.format(java.util.Locale.GERMANY, "%.1f GB", gb)
        mb >= 1 -> "${mb.toInt()} MB"
        else -> "${bytes / 1024} kB"
    }
}

/** Thousands separated by a space, as Norwegian typography wants: "8 412". */
fun formatCount(n: Int): String =
    n.toString().reversed().chunked(3).joinToString(" ").reversed()

@Composable
fun CenteredBox(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { content() }
}
