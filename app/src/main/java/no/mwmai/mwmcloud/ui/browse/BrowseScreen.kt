package no.mwmai.mwmcloud.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import no.mwmai.mwmcloud.Graph
import no.mwmai.mwmcloud.R
import no.mwmai.mwmcloud.data.media.BackupLimits
import no.mwmai.mwmcloud.data.media.CategoryMode
import no.mwmai.mwmcloud.data.media.LocalFile
import no.mwmai.mwmcloud.data.media.MediaCategory
import no.mwmai.mwmcloud.ui.PrimaryButton
import no.mwmai.mwmcloud.ui.SecondaryButton
import no.mwmai.mwmcloud.ui.formatBytes
import no.mwmai.mwmcloud.ui.formatCount
import no.mwmai.mwmcloud.ui.theme.MwmColors
import no.mwmai.mwmcloud.ui.theme.MwmDimens

/** How the list is ordered. Date and size, which is what people actually sort by. */
enum class SortOrder(val labelRes: Int) {
    NEWEST(R.string.sort_newest),
    OLDEST(R.string.sort_oldest),
    LARGEST(R.string.sort_largest),
    SMALLEST(R.string.sort_smallest),
    NAME(R.string.sort_name),
}

/**
 * Per-file view of one category, with a thumbnail for every file so a user can
 * see what a file is rather than guess from `IMG_20240817_113244.jpg`.
 *
 * Two modes, chosen by the chips at the top:
 *
 * - **Everything** keeps the whole category and stores what the user ticked *off*,
 *   so a photo taken tomorrow is backed up automatically.
 * - **Only what I pick** stores what the user ticked *on*, and nothing new joins
 *   on its own. This is the mode for "just this folder" or "these four videos".
 *
 * Which one is in force is stored per category, and the uploader reads the same
 * setting through `Selection`, so this screen cannot promise something the backup
 * does not do.
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
    var included by remember { mutableStateOf<Set<String>>(emptySet()) }
    var mode by remember { mutableStateOf(CategoryMode.ALL) }
    var sort by remember { mutableStateOf(SortOrder.NEWEST) }
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(category) {
        val settings = Graph.settings(context)
        excluded = settings.currentExcluded()
        included = settings.currentIncluded(category)
        mode = settings.currentCategoryMode(category)
        files = Graph.mediaScanner(context).scan(category)
        loading = false
    }

    fun isOn(file: LocalFile): Boolean {
        val key = file.uri.toString()
        return when (mode) {
            CategoryMode.ALL -> key !in excluded
            CategoryMode.ONLY_PICKED -> key in included
        }
    }

    fun setOn(file: LocalFile, on: Boolean) {
        val key = file.uri.toString()
        when (mode) {
            CategoryMode.ALL -> {
                excluded = if (on) excluded - key else excluded + key
                scope.launch { Graph.settings(context).setExcluded(excluded) }
            }
            CategoryMode.ONLY_PICKED -> {
                included = if (on) included + key else included - key
                scope.launch { Graph.settings(context).setIncluded(category, included) }
            }
        }
    }

    // Sorting and filtering are applied to the view, never to what is stored. A
    // user who searches, ticks a box and clears the search must not find that the
    // files they could not see at that moment were quietly dropped.
    val shown = remember(files, sort, query) {
        val matching = if (query.isBlank()) {
            files
        } else {
            files.filter { it.displayName.contains(query, ignoreCase = true) }
        }
        when (sort) {
            SortOrder.NEWEST -> matching.sortedByDescending { it.modified }
            SortOrder.OLDEST -> matching.sortedBy { it.modified }
            SortOrder.LARGEST -> matching.sortedByDescending { it.size }
            SortOrder.SMALLEST -> matching.sortedBy { it.size }
            SortOrder.NAME -> matching.sortedBy { it.displayName.lowercase() }
        }
    }

    val selected = files.filter { isOn(it) }
    val selectedBytes = selected.sumOf { it.size }

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
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (loading) {
                stringResource(R.string.folders_counting)
            } else {
                stringResource(
                    R.string.browse_included,
                    formatCount(selected.size),
                    formatCount(files.size),
                    formatBytes(selectedBytes),
                )
            },
            style = MaterialTheme.typography.labelMedium,
            color = MwmColors.Muted,
        )

        Spacer(Modifier.height(12.dp))
        ModeChips(
            mode = mode,
            onChange = { next ->
                // Switching to "only what I pick" with nothing picked would show an
                // empty selection and silently stop backing the category up. Seed
                // it with what was already going, so the change is visible without
                // being destructive.
                mode = next
                scope.launch {
                    val settings = Graph.settings(context)
                    if (next == CategoryMode.ONLY_PICKED && included.isEmpty()) {
                        included = files.map { it.uri.toString() }.toSet() - excluded
                        settings.setIncluded(category, included)
                    }
                    settings.setCategoryMode(category, next)
                }
            },
        )

        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SearchField(query, { query = it }, Modifier.weight(1f))
            SortMenu(sort) { sort = it }
        }

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallButton(
                text = stringResource(R.string.browse_select_all),
                modifier = Modifier.weight(1f),
                enabled = !loading,
                onClick = {
                    // Acts on what is on screen. With a search active, a "select
                    // all" that silently took the other 3 000 photos would be a trap.
                    val keys = shown.filter { it.size <= BackupLimits.MAX_FILE_BYTES }
                        .map { it.uri.toString() }.toSet()
                    scope.launch {
                        val settings = Graph.settings(context)
                        if (mode == CategoryMode.ALL) {
                            excluded = excluded - keys
                            settings.setExcluded(excluded)
                        } else {
                            included = included + keys
                            settings.setIncluded(category, included)
                        }
                    }
                },
            )
            SmallButton(
                text = stringResource(R.string.browse_select_none),
                modifier = Modifier.weight(1f),
                enabled = !loading,
                onClick = {
                    val keys = shown.map { it.uri.toString() }.toSet()
                    scope.launch {
                        val settings = Graph.settings(context)
                        if (mode == CategoryMode.ALL) {
                            excluded = excluded + keys
                            settings.setExcluded(excluded)
                        } else {
                            included = included - keys
                            settings.setIncluded(category, included)
                        }
                    }
                },
            )
        }

        Spacer(Modifier.height(12.dp))

        if (!loading && shown.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.browse_no_hits),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MwmColors.Muted,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(if (category == MediaCategory.AUDIO) 1 else 3),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f),
            ) {
                items(shown, key = { it.uri.toString() }) { file ->
                    val oversized = file.size > BackupLimits.MAX_FILE_BYTES
                    if (category == MediaCategory.AUDIO) {
                        AudioRow(
                            file = file,
                            checked = isOn(file) && !oversized,
                            enabled = !oversized,
                            onToggle = { setOn(file, it) },
                        )
                    } else {
                        Thumb(
                            file = file,
                            checked = isOn(file) && !oversized,
                            enabled = !oversized,
                            onToggle = { setOn(file, it) },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        PrimaryButton(stringResource(R.string.done), onBack)
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * A square thumbnail with a tick in the corner.
 *
 * Unselected files are dimmed rather than hidden. "These are the ones not going"
 * is information the user needs as much as the ones that are.
 */
@Composable
private fun Thumb(
    file: LocalFile,
    checked: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(MwmColors.Border)
            .clickable(enabled = enabled) { onToggle(!checked) },
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(file.uri)
                .size(THUMB_PX)
                .crossfade(false)
                .build(),
            imageLoader = Graph.localImageLoader(context),
            contentDescription = file.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().alpha(if (checked) 1f else 0.35f),
        )

        if (!enabled) {
            Text(
                stringResource(R.string.browse_too_large_short),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .background(MwmColors.Attention)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        } else {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(if (checked) MwmColors.Safe else Color.White.copy(alpha = 0.75f)),
                contentAlignment = Alignment.Center,
            ) {
                if (checked) {
                    Icon(
                        painter = painterResource(R.drawable.ic_check),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

/** Music has no useful picture, so it stays a readable row: title, size, tick. */
@Composable
private fun AudioRow(
    file: LocalFile,
    checked: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .clickable(enabled = enabled) { onToggle(!checked) }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape)
                .background(if (checked) MwmColors.Safe else MwmColors.Border),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(if (checked) R.drawable.ic_check else R.drawable.ic_note),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
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

@Composable
private fun ModeChips(mode: CategoryMode, onChange: (CategoryMode) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CategoryMode.entries.forEach { m ->
            val active = m == mode
            Text(
                text = stringResource(
                    if (m == CategoryMode.ALL) R.string.browse_mode_all else R.string.browse_mode_picked,
                ),
                style = MaterialTheme.typography.labelLarge,
                color = if (active) Color.White else MwmColors.Text,
                modifier = Modifier
                    .clip(RoundedCornerShape(22.dp))
                    .background(if (active) MwmColors.Action else Color.White)
                    .clickable { onChange(m) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun SortMenu(sort: SortOrder, onChange: (SortOrder) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White)
                .clickable { open = true }
                .padding(horizontal = 14.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                stringResource(sort.labelRes),
                style = MaterialTheme.typography.labelMedium,
                color = MwmColors.Text,
                maxLines = 1,
            )
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = stringResource(R.string.sort_label),
                tint = MwmColors.Muted,
                modifier = Modifier.size(16.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SortOrder.entries.forEach { order ->
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(order.labelRes),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (order == sort) MwmColors.Action else MwmColors.Text,
                        )
                    },
                    onClick = { onChange(order); open = false },
                )
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        singleLine = true,
        placeholder = {
            Text(
                stringResource(R.string.browse_search_hint),
                style = MaterialTheme.typography.labelMedium,
                color = MwmColors.Muted,
                maxLines = 1,
            )
        },
        leadingIcon = {
            Icon(
                painter = painterResource(R.drawable.ic_search),
                contentDescription = null,
                tint = MwmColors.Muted,
                modifier = Modifier.size(18.dp),
            )
        },
        textStyle = MaterialTheme.typography.bodyMedium,
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Color.White,
            unfocusedContainerColor = Color.White,
            focusedBorderColor = MwmColors.Action,
            unfocusedBorderColor = MwmColors.Border,
            focusedTextColor = MwmColors.Text,
            unfocusedTextColor = MwmColors.Text,
        ),
        modifier = modifier,
    )
}

@Composable
private fun SmallButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    // Full 68 dp height, like every other button. Halving it for a "secondary"
    // action is exactly the shortcut the design pack forbids: these two are the
    // ones an unsteady hand needs to hit.
    SecondaryButton(text = text, onClick = onClick, modifier = modifier, enabled = enabled)
}

private fun labelFor(c: MediaCategory) = when (c) {
    MediaCategory.IMAGES -> R.string.cat_images
    MediaCategory.AUDIO -> R.string.cat_audio
    MediaCategory.VIDEO -> R.string.cat_video
    MediaCategory.DOCUMENTS -> R.string.cat_documents
}

/** Three across on a phone is about 120 dp, so 360 px covers a 3x screen. */
private const val THUMB_PX = 360
