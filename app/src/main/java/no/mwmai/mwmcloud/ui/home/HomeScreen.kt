package no.mwmai.mwmcloud.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.launch
import no.mwmai.mwmcloud.Graph
import no.mwmai.mwmcloud.R
import no.mwmai.mwmcloud.ui.PrimaryButton
import no.mwmai.mwmcloud.ui.SecondaryButton
import no.mwmai.mwmcloud.ui.formatCount
import no.mwmai.mwmcloud.ui.theme.MwmColors
import no.mwmai.mwmcloud.ui.theme.MwmDimens
import no.mwmai.mwmcloud.work.UploadWorker

/**
 * Screens 04 and 05 in the design pack, as one screen that changes state.
 *
 * Every number here comes from the ledger or from live work progress. Nothing is
 * assumed: if no upload has finished, it says so rather than showing a reassuring
 * green tick.
 */
@Composable
fun HomeScreen(onChangeFolders: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val workInfos by WorkManager.getInstance(context)
        .getWorkInfosForUniqueWorkLiveData(UploadWorker.WORK_NAME)
        .observeAsState(emptyList())

    val info = workInfos.firstOrNull()
    val running = info?.state == WorkInfo.State.RUNNING || info?.state == WorkInfo.State.ENQUEUED
    val done = info?.progress?.getInt(UploadWorker.KEY_DONE, 0) ?: 0
    val total = info?.progress?.getInt(UploadWorker.KEY_TOTAL, 0) ?: 0
    val failureMessage = info?.outputData?.getString(UploadWorker.KEY_ERROR)

    var backedUp by remember { mutableIntStateOf(0) }
    var refreshKey by remember { mutableIntStateOf(0) }

    androidx.compose.runtime.LaunchedEffect(info?.state, refreshKey) {
        backedUp = Graph.ledger(context).count()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MwmColors.Background)
            .padding(MwmDimens.ScreenPadding),
    ) {
        Spacer(Modifier.height(24.dp))

        when {
            running -> StatusCard(
                tint = MwmColors.ActionTint,
                circle = MwmColors.Action,
                icon = R.drawable.ic_arrow_up,
                title = stringResource(R.string.home_working),
                subtitle = if (total > 0) {
                    stringResource(R.string.home_progress, formatCount(done), formatCount(total))
                } else {
                    stringResource(R.string.home_preparing)
                },
                progress = if (total > 0) done.toFloat() / total else null,
            )

            failureMessage != null -> StatusCard(
                tint = Color(0xFFFDF3E0),
                circle = MwmColors.Attention,
                icon = R.drawable.ic_alert_circle,
                title = stringResource(R.string.home_attention),
                subtitle = failureMessage,
            )

            backedUp > 0 -> StatusCard(
                tint = Color(0xFFE7F5EE),
                circle = MwmColors.Safe,
                icon = R.drawable.ic_check,
                title = stringResource(R.string.home_safe),
                subtitle = stringResource(R.string.home_files_safe, formatCount(backedUp)),
            )

            else -> StatusCard(
                tint = MwmColors.ActionTint,
                circle = MwmColors.Action,
                icon = R.drawable.ic_arrow_up,
                title = stringResource(R.string.home_not_started),
                subtitle = stringResource(R.string.home_not_started_body),
            )
        }

        Spacer(Modifier.height(MwmDimens.CardSpacing))

        Card(
            shape = RoundedCornerShape(MwmDimens.CardRadius),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    formatCount(backedUp),
                    style = MaterialTheme.typography.displaySmall,
                    color = MwmColors.Text,
                )
                Text(
                    stringResource(R.string.home_files_counted),
                    style = MaterialTheme.typography.labelMedium,
                    color = MwmColors.Muted,
                )
            }
        }

        Spacer(Modifier.weight(1f))

        if (!running) {
            PrimaryButton(
                text = stringResource(R.string.home_back_up_now),
                onClick = {
                    scope.launch {
                        UploadWorker.enqueue(context, Graph.settings(context).currentCategories())
                        refreshKey++
                    }
                },
            )
            Spacer(Modifier.height(12.dp))
        }

        SecondaryButton(stringResource(R.string.home_change_folders), onChangeFolders)
        Spacer(Modifier.height(16.dp))

        Text(
            stringResource(R.string.home_wifi_note),
            style = MaterialTheme.typography.labelMedium,
            color = MwmColors.Muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun StatusCard(
    tint: Color,
    circle: Color,
    icon: Int,
    title: String,
    subtitle: String,
    progress: Float? = null,
) {
    Card(
        shape = RoundedCornerShape(MwmDimens.CardRadius),
        colors = CardDefaults.cardColors(containerColor = tint),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier.size(88.dp).clip(CircleShape).background(circle),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(44.dp),
                )
            }
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium,
                color = MwmColors.Text,
                textAlign = TextAlign.Center,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MwmColors.Muted,
                textAlign = TextAlign.Center,
            )
            progress?.let {
                LinearProgressIndicator(
                    progress = { it },
                    color = MwmColors.Action,
                    trackColor = Color.White,
                    modifier = Modifier.fillMaxWidth().height(10.dp).clip(CircleShape),
                )
            }
        }
    }
}
