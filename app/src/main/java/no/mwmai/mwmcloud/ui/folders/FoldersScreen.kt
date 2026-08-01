package no.mwmai.mwmcloud.ui.folders

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import no.mwmai.mwmcloud.Graph
import no.mwmai.mwmcloud.R
import no.mwmai.mwmcloud.data.media.BackupLimits
import no.mwmai.mwmcloud.data.media.CategorySummary
import no.mwmai.mwmcloud.data.media.MediaCategory
import no.mwmai.mwmcloud.ui.PrimaryButton
import no.mwmai.mwmcloud.ui.formatBytes
import no.mwmai.mwmcloud.ui.formatCount
import no.mwmai.mwmcloud.ui.theme.MwmColors
import no.mwmai.mwmcloud.ui.theme.MwmDimens
import no.mwmai.mwmcloud.work.UploadWorker

/**
 * Screen 03 in the design pack. Counts and sizes are read from the phone, not
 * placeholders: an empty category shows as empty rather than pretending.
 */
@Composable
fun FoldersScreen(
    onDone: () -> Unit,
    onBrowse: (MediaCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val selected = remember { mutableStateMapOf<MediaCategory, Boolean>() }
    val summaries = remember { mutableStateMapOf<MediaCategory, CategorySummary>() }
    var scanning by remember { mutableStateOf(true) }

    // MediaStore returns nothing without permission, which would look like an
    // empty phone. Ask first, then scan, so the numbers are real either way.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        scope.launch {
            MediaCategory.entries.forEach { summaries[it] = Graph.mediaScanner(context).summarise(it) }
            scanning = false
        }
    }

    LaunchedEffect(Unit) {
        Graph.settings(context).currentCategories().forEach { selected[it] = true }
        val needed = MediaCategory.entries.mapNotNull { it.permission }.distinct().toTypedArray()
        if (needed.isEmpty()) {
            MediaCategory.entries.forEach { summaries[it] = Graph.mediaScanner(context).summarise(it) }
            scanning = false
        } else {
            permissionLauncher.launch(needed)
        }
    }

    // Hand-picked files and folders, so nothing is limited to MediaStore's fixed
    // categories. This is how documents, downloads and anything else get in.
    var pickedSummary by remember { mutableStateOf<CategorySummary?>(null) }

    suspend fun refreshPicked() {
        val s = Graph.settings(context)
        pickedSummary = Graph.safScanner(context)
            .summarise(s.currentPickedFolders(), s.currentPickedFiles())
    }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            // Persist the grant, or the folder stops working after a reboot.
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            scope.launch {
                Graph.settings(context).addPickedFolders(setOf(uri.toString()))
                refreshPicked()
            }
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri ->
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
            }
            scope.launch {
                Graph.settings(context).addPickedFiles(uris.map { it.toString() }.toSet())
                refreshPicked()
            }
        }
    }

    LaunchedEffect(Unit) { refreshPicked() }

    val chosen = MediaCategory.entries.filter { selected[it] == true }.toSet()
    val chosenBytes = chosen.sumOf { summaries[it]?.totalBytes ?: 0L } +
        (pickedSummary?.totalBytes ?: 0L)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MwmColors.Background)
            .padding(MwmDimens.ScreenPadding),
    ) {
        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(R.string.folders_title),
            style = MaterialTheme.typography.displaySmall,
            color = MwmColors.Text,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.folders_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MwmColors.Muted,
        )
        Spacer(Modifier.height(24.dp))

        // Documents has no MediaStore category; it is covered by the picker below.
        MediaCategory.entries.filter { it != MediaCategory.DOCUMENTS }.forEach { category ->
            CategoryCard(
                category = category,
                summary = summaries[category],
                scanning = scanning,
                checked = selected[category] == true,
                onCheckedChange = { selected[category] = it },
                onOpen = { onBrowse(category) },
            )
            Spacer(Modifier.height(MwmDimens.CardSpacing))
        }

        PickedCard(
            summary = pickedSummary,
            onPickFolder = { folderPicker.launch(null) },
            onPickFiles = { filePicker.launch(arrayOf("*/*")) },
            onClear = { scope.launch { Graph.settings(context).clearPicked(); refreshPicked() } },
        )

        Spacer(Modifier.height(MwmDimens.CardSpacing))
        Spacer(Modifier.weight(1f))

        Text(
            text = stringResource(
                R.string.folders_selected,
                chosen.size,
                MediaCategory.entries.size,
                formatBytes(chosenBytes),
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MwmColors.Muted,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))

        PrimaryButton(
            text = stringResource(R.string.folders_start),
            enabled = (chosen.isNotEmpty() || (pickedSummary?.fileCount ?: 0) > 0) && !scanning,
            onClick = {
                scope.launch {
                    Graph.settings(context).setCategories(chosen)
                    UploadWorker.enqueue(context, chosen)
                    onDone()
                }
            },
        )
        Spacer(Modifier.height(16.dp))
    }
}

/** Hand-picked files and folders. The way in for anything MediaStore cannot see. */
@Composable
private fun PickedCard(
    summary: CategorySummary?,
    onPickFolder: () -> Unit,
    onPickFiles: () -> Unit,
    onClear: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(MwmDimens.CardRadius),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.picked_title),
                style = MaterialTheme.typography.titleLarge,
                color = MwmColors.Text,
            )
            Text(
                text = if ((summary?.fileCount ?: 0) == 0) {
                    stringResource(R.string.picked_none)
                } else {
                    stringResource(
                        R.string.picked_count,
                        formatCount(summary!!.fileCount),
                        formatBytes(summary.totalBytes),
                    )
                },
                style = MaterialTheme.typography.labelMedium,
                color = MwmColors.Muted,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SmallAction(stringResource(R.string.picked_add_folder), onPickFolder, Modifier.weight(1f))
                SmallAction(stringResource(R.string.picked_add_files), onPickFiles, Modifier.weight(1f))
            }

            if ((summary?.fileCount ?: 0) > 0) {
                SmallAction(stringResource(R.string.picked_clear), onClear, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun SmallAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.material3.OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(56.dp),
        shape = MaterialTheme.shapes.medium,
        border = androidx.compose.foundation.BorderStroke(1.dp, MwmColors.Border),
        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
            containerColor = Color.White,
            contentColor = MwmColors.Action,
        ),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun CategoryCard(
    category: MediaCategory,
    summary: CategorySummary?,
    scanning: Boolean,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onOpen: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(MwmDimens.CardRadius),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MwmColors.ActionTint),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(iconFor(category)),
                    contentDescription = null,
                    tint = MwmColors.Text,
                    modifier = Modifier.size(28.dp),
                )
            }

            Spacer(Modifier.size(16.dp))

            Column(
                Modifier
                    .weight(1f)
                    .clickable(enabled = summary != null && summary.fileCount > 0, onClick = onOpen),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    stringResource(labelFor(category)),
                    style = MaterialTheme.typography.titleLarge,
                    color = MwmColors.Text,
                )
                Text(
                    text = when {
                        scanning -> stringResource(R.string.folders_counting)
                        summary == null || summary.fileCount == 0 -> stringResource(R.string.folders_empty)
                        else -> stringResource(
                            unitFor(category),
                            formatCount(summary.fileCount),
                            formatBytes(summary.totalBytes),
                        )
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MwmColors.Muted,
                )

                // Never let oversized files disappear without saying so.
                if (!scanning && (summary?.skippedTooLarge ?: 0) > 0) {
                    Text(
                        text = stringResource(
                            R.string.folders_too_large,
                            summary!!.skippedTooLarge,
                            formatBytes(BackupLimits.MAX_FILE_BYTES),
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MwmColors.Attention,
                    )
                }
            }

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
}

private fun iconFor(c: MediaCategory) = when (c) {
    MediaCategory.IMAGES -> R.drawable.ic_folder
    MediaCategory.AUDIO -> R.drawable.ic_folder
    MediaCategory.VIDEO -> R.drawable.ic_folder
    MediaCategory.DOCUMENTS -> R.drawable.ic_folder
}

private fun labelFor(c: MediaCategory) = when (c) {
    MediaCategory.IMAGES -> R.string.cat_images
    MediaCategory.AUDIO -> R.string.cat_audio
    MediaCategory.VIDEO -> R.string.cat_video
    MediaCategory.DOCUMENTS -> R.string.cat_documents
}

private fun unitFor(c: MediaCategory) = when (c) {
    MediaCategory.IMAGES -> R.string.unit_images
    MediaCategory.AUDIO -> R.string.unit_audio
    MediaCategory.VIDEO -> R.string.unit_video
    MediaCategory.DOCUMENTS -> R.string.unit_documents
}
