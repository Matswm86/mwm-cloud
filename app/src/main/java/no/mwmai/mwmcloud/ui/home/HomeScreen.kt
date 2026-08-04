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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
fun HomeScreen(
    onChangeFolders: () -> Unit,
    onSeeFiles: () -> Unit,
    onHelp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val workInfos by WorkManager.getInstance(context)
        .getWorkInfosForUniqueWorkLiveData(UploadWorker.WORK_NAME)
        .observeAsState(emptyList())

    // The scheduled backup runs under its own work name. Watching only the
    // manual one made an automatic run invisible: the screen said "safe" while
    // files were going up, and the verify button was enabled mid-upload, which
    // could report freshly uploading files as missing. A periodic job sits
    // ENQUEUED between runs by design, so only RUNNING counts for it.
    val periodicInfos by WorkManager.getInstance(context)
        .getWorkInfosForUniqueWorkLiveData(UploadWorker.PERIODIC_WORK_NAME)
        .observeAsState(emptyList())

    val manual = workInfos.firstOrNull()
    val manualActive = manual?.state == WorkInfo.State.RUNNING ||
        manual?.state == WorkInfo.State.ENQUEUED
    val periodic = periodicInfos.firstOrNull { it.state == WorkInfo.State.RUNNING }

    val running = manualActive || periodic != null
    val info = if (manualActive) manual else periodic ?: manual
    val done = info?.progress?.getInt(UploadWorker.KEY_DONE, 0) ?: 0
    val total = info?.progress?.getInt(UploadWorker.KEY_TOTAL, 0) ?: 0
    // Failure text only from the manual run: a periodic job never finishes (it
    // re-enqueues), so it has no outputData worth reading.
    val failureMessage = manual?.outputData?.getString(UploadWorker.KEY_ERROR)

    var backedUp by remember { mutableIntStateOf(0) }
    var refreshKey by remember { mutableIntStateOf(0) }

    // Keyed on `done` as well as state. Keyed only on state, the count froze at
    // whatever it was when the run started, because RUNNING does not change while
    // files are going up. That made a live backup look stuck at zero.
    androidx.compose.runtime.LaunchedEffect(info?.state, done, refreshKey) {
        backedUp = Graph.ledger(context).count()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MwmColors.Background)
            // Scrollable rather than pinned. With the status card, the count, the
            // verify panel and four buttons, this screen is taller than a small
            // phone, and a weighted spacer would push the last button off-screen
            // instead of letting the user reach it.
            .verticalScroll(rememberScrollState())
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

        Spacer(Modifier.height(MwmDimens.CardSpacing))

        // The trust surface: ask the server what is genuinely there, rather than
        // trusting the app's own record of what it thinks it sent.
        VerifyPanel(running = running)

        Spacer(Modifier.height(24.dp))

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

        // Above "change what gets backed up", because seeing that the photos are
        // really there is the question people actually have.
        SecondaryButton(stringResource(R.string.home_see_files), onSeeFiles)
        Spacer(Modifier.height(12.dp))

        SecondaryButton(stringResource(R.string.home_change_folders), onChangeFolders)
        Spacer(Modifier.height(12.dp))

        SecondaryButton(stringResource(R.string.home_help), onHelp)
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

/**
 * Runs a real listing of the storage box and compares it against what the phone
 * holds. This is the only screen element that can honestly say "safe", because
 * it is the only one that asked the server.
 */
@Composable
private fun VerifyPanel(running: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var busy by remember { mutableStateOf(false) }
    var checked by remember { mutableIntStateOf(0) }
    var expected by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<no.mwmai.mwmcloud.data.verify.VerifyResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var repairing by remember { mutableStateOf(false) }
    var repaired by remember { mutableStateOf<Int?>(null) }

    Card(
        shape = RoundedCornerShape(MwmDimens.CardRadius),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stringResource(R.string.verify_title),
                style = MaterialTheme.typography.titleLarge,
                color = MwmColors.Text,
            )

            val body = when {
                repairing -> stringResource(R.string.verify_repairing)
                repaired != null -> stringResource(
                    R.string.verify_repaired,
                    formatCount(repaired!!),
                )
                busy && expected > 0 -> stringResource(
                    R.string.verify_checking_progress,
                    formatCount(checked),
                    formatCount(expected),
                )
                busy -> stringResource(R.string.verify_checking)
                error != null -> error!!
                result == null -> stringResource(R.string.verify_never)
                result!!.allGood -> stringResource(
                    R.string.verify_all_good,
                    formatCount(result!!.confirmed),
                )
                else -> stringResource(
                    R.string.verify_problems,
                    formatCount(result!!.confirmed),
                    formatCount(result!!.total),
                    result!!.missing.size,
                    result!!.wrongSize.size,
                )
            }

            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = when {
                    error != null -> MwmColors.Attention
                    repaired != null -> MwmColors.Action
                    result?.allGood == true -> MwmColors.Safe
                    result != null -> MwmColors.Attention
                    else -> MwmColors.Muted
                },
            )

            // Name the first few that are wrong. A count alone tells the user
            // there is a problem but not which photo to worry about.
            if (repaired == null) {
                result?.let { r ->
                    (r.missing + r.wrongSize).take(3).forEach { path ->
                        Text(
                            "· ${path.substringAfterLast('/')}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MwmColors.Muted,
                        )
                    }
                }
            }

            // Finding a gap and leaving it there is only half an answer. Dropping
            // the ledger rows for the files the server does not have makes the
            // next run treat them as never uploaded, which is exactly what they
            // are: the ledger recorded intent, the server holds the truth.
            val broken = result?.takeIf { !it.allGood }
            if (broken != null && repaired == null) {
                SecondaryButton(
                    text = stringResource(R.string.verify_repair),
                    enabled = !running && !busy && !repairing,
                    onClick = {
                        repairing = true
                        scope.launch {
                            try {
                                val ledger = Graph.ledger(context)
                                val paths = broken.missing + broken.wrongSize
                                paths.forEach { ledger.forget(it) }
                                UploadWorker.enqueue(
                                    context,
                                    Graph.settings(context).currentCategories(),
                                )
                                repaired = paths.size
                            } catch (e: Exception) {
                                error = context.getString(R.string.err_generic)
                            } finally {
                                repairing = false
                            }
                        }
                    },
                )
            }

            PrimaryButton(
                text = stringResource(R.string.verify_button),
                enabled = !running,
                busy = busy,
                color = MwmColors.Safe,
                onClick = {
                    busy = true; error = null; result = null; repaired = null
                    checked = 0; expected = 0
                    scope.launch {
                        try {
                            val creds = Graph.credentialStore(context).current()
                            if (creds == null) {
                                error = context.getString(R.string.err_generic)
                                return@launch
                            }
                            // Same rule as the uploader, through the same
                            // resolver. A verify that checked a different set
                            // would report missing files that were never meant
                            // to go, and call a good backup broken.
                            val settings = Graph.settings(context)
                            val excluded = settings.currentExcluded()
                            val modes = settings.currentCategoryModes()
                            val included = settings.currentIncludedAll()
                            val files = buildList {
                                settings.currentCategories().forEach { category ->
                                    addAll(
                                        no.mwmai.mwmcloud.data.media.Selection.filter(
                                            files = Graph.mediaScanner(context).scan(category),
                                            mode = modes[category]
                                                ?: no.mwmai.mwmcloud.data.media.CategoryMode.ALL,
                                            excluded = excluded,
                                            included = included[category].orEmpty(),
                                        ),
                                    )
                                }
                                addAll(
                                    Graph.safScanner(context).scan(
                                        settings.currentPickedFolders(),
                                        settings.currentPickedFiles(),
                                    ).filter { it.uri.toString() !in excluded },
                                )
                            }.let { no.mwmai.mwmcloud.data.media.RemoteNames.resolve(it) }

                            expected = files.size
                            result = no.mwmai.mwmcloud.data.verify.Verifier(context)
                                .verify(creds, files) { c, t -> checked = c; expected = t }
                        } catch (e: Exception) {
                            error = context.getString(R.string.err_generic)
                        } finally {
                            busy = false
                        }
                    }
                },
            )
        }
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
