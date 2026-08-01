package no.mwmai.mwmcloud.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import no.mwmai.mwmcloud.Graph
import no.mwmai.mwmcloud.R
import no.mwmai.mwmcloud.data.media.MediaCategory
import no.mwmai.mwmcloud.settings.BackupSchedule
import no.mwmai.mwmcloud.ui.PrimaryButton
import no.mwmai.mwmcloud.ui.SecondaryButton
import no.mwmai.mwmcloud.ui.theme.MwmColors
import no.mwmai.mwmcloud.ui.theme.MwmDimens

/**
 * Automatic backup: how often, and what goes along.
 *
 * Kept separate from "what should be kept safe" on purpose. Those are different
 * decisions: a 40 GB music library is a fine answer to "back this up when I press
 * the button" and a poor answer to "do this every week on its own". Defaulting the
 * automatic set to the manual one means a user who never opens this screen still
 * gets the obvious behaviour.
 *
 * Automatic runs are wifi-only with no override anywhere in the app. An unattended
 * backup must never be able to spend mobile data.
 */
@Composable
fun ScheduleScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var schedule by remember { mutableStateOf(BackupSchedule.OFF) }
    var includePicked by remember { mutableStateOf(true) }
    val categories: SnapshotStateList<MediaCategory> = remember { mutableStateListOf() }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val settings = Graph.settings(context)
        schedule = settings.currentSchedule()
        includePicked = settings.currentAutoIncludePicked()
        categories.addAll(settings.currentAutoCategories())
        loaded = true
    }

    // Every change is applied immediately, including to WorkManager. A settings
    // screen with a Save button that someone forgets to press is a schedule that
    // silently never runs.
    fun persist() {
        scope.launch {
            val settings = Graph.settings(context)
            val chosen = categories.toSet()
            settings.setSchedule(schedule)
            settings.setAutoCategories(chosen)
            settings.setAutoIncludePicked(includePicked)
            no.mwmai.mwmcloud.work.UploadWorker.schedule(
                context = context,
                schedule = schedule,
                categories = chosen,
                includePicked = includePicked,
            )
        }
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
                stringResource(R.string.schedule_title),
                style = MaterialTheme.typography.headlineLarge,
                color = MwmColors.Text,
            )
            Text(
                stringResource(R.string.schedule_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MwmColors.Muted,
            )

            Card(
                shape = RoundedCornerShape(MwmDimens.CardRadius),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    BackupSchedule.entries.forEach { option ->
                        ChoiceRow(
                            label = stringResource(labelFor(option)),
                            detail = stringResource(detailFor(option)),
                            selected = schedule == option,
                            onClick = { schedule = option; persist() },
                        )
                    }
                }
            }

            // Only worth showing once something is actually scheduled. Asking what
            // an automatic run should cover when there is no automatic run is a
            // question with no consequence.
            if (schedule != BackupSchedule.OFF) {
                Text(
                    stringResource(R.string.schedule_what_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MwmColors.Text,
                )

                Card(
                    shape = RoundedCornerShape(MwmDimens.CardRadius),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column {
                        MediaCategory.entries
                            .filter { it != MediaCategory.DOCUMENTS }
                            .forEach { category ->
                                ToggleRow(
                                    label = stringResource(labelFor(category)),
                                    checked = category in categories,
                                    onCheckedChange = { on ->
                                        if (on) categories.add(category) else categories.remove(category)
                                        persist()
                                    },
                                )
                            }
                        ToggleRow(
                            label = stringResource(R.string.picked_title),
                            checked = includePicked,
                            onCheckedChange = { includePicked = it; persist() },
                        )
                    }
                }

                if (loaded && categories.isEmpty() && !includePicked) {
                    Text(
                        stringResource(R.string.schedule_nothing_chosen),
                        style = MaterialTheme.typography.labelMedium,
                        color = MwmColors.Attention,
                    )
                }
            }

            Text(
                stringResource(R.string.schedule_wifi_note),
                style = MaterialTheme.typography.labelMedium,
                color = MwmColors.Muted,
            )
            Spacer(Modifier.height(4.dp))
        }

        Spacer(Modifier.height(12.dp))
        PrimaryButton(stringResource(R.string.done), onBack)
        Spacer(Modifier.height(12.dp))
        SecondaryButton(stringResource(R.string.back), onBack)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ChoiceRow(label: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(28.dp).clip(CircleShape)
                .background(if (selected) MwmColors.Safe else MwmColors.Border),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    painter = painterResource(R.drawable.ic_check),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.titleLarge, color = MwmColors.Text)
            Text(detail, style = MaterialTheme.typography.labelMedium, color = MwmColors.Muted)
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleLarge,
            color = MwmColors.Text,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = MwmColors.Safe,
                checkedThumbColor = Color.White,
                uncheckedTrackColor = MwmColors.Border,
                uncheckedThumbColor = Color.White,
                uncheckedBorderColor = MwmColors.Border,
            ),
        )
    }
}

private fun labelFor(s: BackupSchedule) = when (s) {
    BackupSchedule.OFF -> R.string.schedule_off
    BackupSchedule.DAILY -> R.string.schedule_daily
    BackupSchedule.WEEKLY -> R.string.schedule_weekly
    BackupSchedule.MONTHLY -> R.string.schedule_monthly
}

private fun detailFor(s: BackupSchedule) = when (s) {
    BackupSchedule.OFF -> R.string.schedule_off_detail
    BackupSchedule.DAILY -> R.string.schedule_daily_detail
    BackupSchedule.WEEKLY -> R.string.schedule_weekly_detail
    BackupSchedule.MONTHLY -> R.string.schedule_monthly_detail
}

private fun labelFor(c: MediaCategory) = when (c) {
    MediaCategory.IMAGES -> R.string.cat_images
    MediaCategory.AUDIO -> R.string.cat_audio
    MediaCategory.VIDEO -> R.string.cat_video
    MediaCategory.DOCUMENTS -> R.string.cat_documents
}
