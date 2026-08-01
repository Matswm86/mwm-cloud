package no.mwmai.mwmcloud.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import no.mwmai.mwmcloud.Graph
import no.mwmai.mwmcloud.R
import no.mwmai.mwmcloud.data.media.BackupLimits
import no.mwmai.mwmcloud.data.media.LocalFile
import no.mwmai.mwmcloud.data.media.MediaCategory
import no.mwmai.mwmcloud.ui.SecondaryButton
import no.mwmai.mwmcloud.ui.formatBytes
import no.mwmai.mwmcloud.ui.formatCount
import no.mwmai.mwmcloud.ui.theme.MwmColors
import no.mwmai.mwmcloud.ui.theme.MwmDimens

/**
 * Per-file view of one category. Everything is included by default; unticking a
 * row excludes that single file.
 *
 * Exclusions are stored, not inclusions. That way a photo taken tomorrow is
 * backed up automatically instead of silently missing because it was not on a
 * list written today.
 */
@Composable
fun BrowseScreen(
    category: MediaCategory,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var files by remember { mutableStateOf<List<LocalFile>>(emptyList()) }
    var excluded by remember { mutableStateOf<Set<String>>(emptySet()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(category) {
        excluded = Graph.settings(context).currentExcluded()
        files = Graph.mediaScanner(context).scan(category).sortedByDescending { it.modified }
        loading = false
    }

    val includedCount = files.count { it.uri.toString() !in excluded }
    val includedBytes = files.filter { it.uri.toString() !in excluded }.sumOf { it.size }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MwmColors.Background)
            .padding(horizontal = MwmDimens.ScreenPadding),
    ) {
        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(labelFor(category)),
            style = MaterialTheme.typography.headlineLarge,
            color = MwmColors.Text,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (loading) {
                stringResource(R.string.folders_counting)
            } else {
                stringResource(
                    R.string.browse_included,
                    formatCount(includedCount),
                    formatCount(files.size),
                    formatBytes(includedBytes),
                )
            },
            style = MaterialTheme.typography.labelMedium,
            color = MwmColors.Muted,
        )
        Spacer(Modifier.height(16.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(files, key = { it.uri.toString() }) { file ->
                val key = file.uri.toString()
                val oversized = file.size > BackupLimits.MAX_FILE_BYTES
                FileRow(
                    file = file,
                    checked = key !in excluded && !oversized,
                    enabled = !oversized,
                    onToggle = { include ->
                        excluded = if (include) excluded - key else excluded + key
                        scope.launch { Graph.settings(context).setExcluded(excluded) }
                    },
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        SecondaryButton(stringResource(R.string.done), onBack)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun FileRow(
    file: LocalFile,
    checked: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onToggle(!checked) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = { if (enabled) onToggle(it) },
            enabled = enabled,
            colors = CheckboxDefaults.colors(
                checkedColor = MwmColors.Safe,
                uncheckedColor = MwmColors.Border,
            ),
        )
        Column(Modifier.weight(1f)) {
            Text(
                file.displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MwmColors.Text else MwmColors.Muted,
                maxLines = 1,
            )
            Text(
                text = if (enabled) {
                    formatBytes(file.size)
                } else {
                    stringResource(R.string.browse_too_large, formatBytes(BackupLimits.MAX_FILE_BYTES))
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (enabled) MwmColors.Muted else MwmColors.Attention,
            )
        }
    }
}

private fun labelFor(c: MediaCategory) = when (c) {
    MediaCategory.IMAGES -> R.string.cat_images
    MediaCategory.AUDIO -> R.string.cat_audio
    MediaCategory.VIDEO -> R.string.cat_video
    MediaCategory.DOCUMENTS -> R.string.cat_documents
}
