package no.mwmai.mwmcloud.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import no.mwmai.mwmcloud.R

/**
 * Developer-only reference screen. Renders every design token and icon so they can
 * be held against design/grafikk/palett.png and ui-ikoner.png on a real device.
 * Not reachable from the app's navigation.
 */
@Composable
fun DesignSwatch(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MwmColors.Background)
            .verticalScroll(rememberScrollState())
            .padding(MwmDimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(MwmDimens.CardSpacing),
    ) {
        Text("Farger", style = MaterialTheme.typography.headlineMedium)
        Swatches.forEach { (name, hex, color) -> ColorRow(name, hex, color) }

        Text("Typografi", style = MaterialTheme.typography.headlineMedium)
        Text("displaySmall 40", style = MaterialTheme.typography.displaySmall)
        Text("headlineLarge 34", style = MaterialTheme.typography.headlineLarge)
        Text("headlineMedium 28", style = MaterialTheme.typography.headlineMedium)
        Text("titleLarge 22", style = MaterialTheme.typography.titleLarge)
        Text("bodyLarge 19", style = MaterialTheme.typography.bodyLarge)
        Text("bodyMedium 17", style = MaterialTheme.typography.bodyMedium)
        Text("labelLarge 20", style = MaterialTheme.typography.labelLarge)
        Text("labelMedium 15", style = MaterialTheme.typography.labelMedium)

        Text("Ikoner", style = MaterialTheme.typography.headlineMedium)
        Icons.chunked(6).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                row.forEach { res ->
                    Icon(
                        painter = painterResource(res),
                        contentDescription = null,
                        tint = MwmColors.Text,
                        modifier = Modifier.size(32.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ColorRow(name: String, hex: String, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            Modifier
                .size(56.dp)
                .background(color, RoundedCornerShape(12.dp))
                .border(1.dp, MwmColors.Border, RoundedCornerShape(12.dp)),
        ) {}
        Column {
            Text(name, style = MaterialTheme.typography.labelLarge)
            Text(hex, style = MaterialTheme.typography.labelMedium, color = MwmColors.Muted)
        }
    }
}

private val Swatches = listOf(
    Triple("Handling", "#1F6FC4", MwmColors.Action),
    Triple("Trygt", "#1F9E6E", MwmColors.Safe),
    Triple("Trenger deg", "#E4A11B", MwmColors.Attention),
    Triple("Tekst", "#14212B", MwmColors.Text),
    Triple("Bakgrunn", "#FBF8F3", MwmColors.Background),
    Triple("Kantlinje", "#EFE8DD", MwmColors.Border),
    Triple("Blå tone", "#DCEBFA", MwmColors.ActionTint),
    Triple("Dempet", "#8A9299", MwmColors.Muted),
)

private val Icons = listOf(
    R.drawable.ic_home,
    R.drawable.ic_folder,
    R.drawable.ic_people,
    R.drawable.ic_check,
    R.drawable.ic_arrow_up,
    R.drawable.ic_alert_circle,
    R.drawable.ic_search,
    R.drawable.ic_lock,
    R.drawable.ic_wifi,
    R.drawable.ic_phone,
    R.drawable.ic_copy,
    R.drawable.ic_chevron_right,
)

@Preview(widthDp = 412, heightDp = 892)
@Composable
private fun DesignSwatchPreview() {
    MwmCloudTheme { DesignSwatch(Modifier.height(892.dp)) }
}
