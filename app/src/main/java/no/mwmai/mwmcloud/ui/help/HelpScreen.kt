package no.mwmai.mwmcloud.ui.help

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import no.mwmai.mwmcloud.Graph
import no.mwmai.mwmcloud.R
import no.mwmai.mwmcloud.ui.SecondaryButton
import no.mwmai.mwmcloud.ui.theme.MwmColors
import no.mwmai.mwmcloud.ui.theme.MwmDimens

/**
 * "Where are my files, and what is this app allowed to do?"
 *
 * This existed only in a handoff document before, which means the person using
 * the app did not have it. Three questions, answered in the order people ask
 * them, in plain language and with no jargon: where the files went, whether the
 * phone is safe, and how to reach the files from something other than this app.
 *
 * The address shown is the user's own, read from the stored connection. Nothing
 * is invented and no example host is displayed as if it were theirs.
 */
@Composable
fun HelpScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current

    val host by produceState<String?>(initialValue = null) {
        value = Graph.credentialStore(context).current()?.baseUrl
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MwmColors.Background)
            .padding(horizontal = MwmDimens.ScreenPadding),
    ) {
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(MwmDimens.CardSpacing),
        ) {
            Spacer(Modifier.height(20.dp))
            Text(
                stringResource(R.string.help_title),
                style = MaterialTheme.typography.headlineLarge,
                color = MwmColors.Text,
            )

            HelpCard(
                icon = R.drawable.ic_folder,
                tint = MwmColors.Action,
                title = stringResource(R.string.help_where_title),
                body = stringResource(R.string.help_where_body),
            )

            HelpCard(
                icon = R.drawable.ic_phone,
                tint = MwmColors.Safe,
                title = stringResource(R.string.help_phone_title),
                body = stringResource(R.string.help_phone_body),
            )

            HelpCard(
                icon = R.drawable.ic_home,
                tint = MwmColors.Action,
                title = stringResource(R.string.help_computer_title),
                // Only shown once the address is known. A placeholder host here
                // would be a wrong answer printed with full confidence.
                body = host?.let { stringResource(R.string.help_computer_body, it) }
                    ?: stringResource(R.string.help_computer_body_unknown),
            )

            HelpCard(
                icon = R.drawable.ic_wifi,
                tint = MwmColors.Muted,
                title = stringResource(R.string.help_wifi_title),
                body = stringResource(R.string.help_wifi_body),
            )

            HelpCard(
                icon = R.drawable.ic_lock,
                tint = MwmColors.Muted,
                title = stringResource(R.string.help_limits_title),
                body = stringResource(R.string.help_limits_body),
            )

            Spacer(Modifier.height(4.dp))
        }

        Spacer(Modifier.height(12.dp))
        SecondaryButton(stringResource(R.string.back), onBack)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun HelpCard(icon: Int, tint: Color, title: String, body: String) {
    Card(
        shape = RoundedCornerShape(MwmDimens.CardRadius),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(tint),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = MwmColors.Text)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MwmColors.Muted)
            }
        }
    }
}
