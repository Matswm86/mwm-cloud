package no.mwmai.mwmcloud.ui.files

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
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import no.mwmai.mwmcloud.Graph
import no.mwmai.mwmcloud.R
import no.mwmai.mwmcloud.data.remote.FileKind
import no.mwmai.mwmcloud.data.remote.RemoteFile
import no.mwmai.mwmcloud.data.remote.RemoteGroup
import no.mwmai.mwmcloud.data.remote.RemoteLibrary
import no.mwmai.mwmcloud.data.remote.RemoteSection
import no.mwmai.mwmcloud.settings.BoxCredentials
import no.mwmai.mwmcloud.ui.SecondaryButton
import no.mwmai.mwmcloud.ui.formatBytes
import no.mwmai.mwmcloud.ui.formatCount
import no.mwmai.mwmcloud.ui.theme.MwmColors
import no.mwmai.mwmcloud.ui.theme.MwmDimens

/**
 * Screen 06, `Filer`. Everything that is on the box, seen from inside the app.
 *
 * The point of this screen is that a user never has to leave. The storage box
 * does serve a plain directory index in a browser, and that route still works,
 * but it looks like an FTP listing from 1998 and it asks the user to understand
 * URLs. Nothing here shows a URL.
 *
 * Months are loaded one at a time, as their heading scrolls into view. A photo
 * library is thousands of files across dozens of months; listing all of them
 * before drawing anything would mean a blank screen for several seconds.
 */
@Composable
fun FilesScreen(
    onBack: () -> Unit,
    onOpen: (RemoteFile) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    var creds by remember { mutableStateOf<BoxCredentials?>(null) }
    var library by remember { mutableStateOf<RemoteLibrary?>(null) }
    var section by remember { mutableStateOf(RemoteSection.PHOTOS) }
    var query by remember { mutableStateOf("") }
    var loadingGroups by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    val groups = remember { mutableStateListOf<RemoteGroup>() }
    val filesByGroup = remember { mutableStateMapOf<String, List<RemoteFile>>() }

    LaunchedEffect(Unit) {
        creds = Graph.credentialStore(context).current()
        library = creds?.let { RemoteLibrary(Graph.transport(it)) }
    }

    LaunchedEffect(library, section) {
        val lib = library ?: return@LaunchedEffect
        loadingGroups = true
        error = null
        groups.clear()
        filesByGroup.clear()
        try {
            groups.addAll(lib.groups(section))
        } catch (e: Exception) {
            error = context.getString(R.string.files_error)
        }
        loadingGroups = false
    }

    // Search has to look at every month, not only the ones already on screen, or
    // it would quietly answer "no matches" for a photo that is right there in a
    // month the user has not scrolled to yet.
    LaunchedEffect(query, groups.size) {
        val lib = library ?: return@LaunchedEffect
        if (query.isBlank()) return@LaunchedEffect
        for (group in groups) {
            if (group.path !in filesByGroup) {
                filesByGroup[group.path] = runCatching { lib.files(group.path) }.getOrDefault(emptyList())
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MwmColors.Background)
            .padding(horizontal = MwmDimens.ScreenPadding),
    ) {
        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(R.string.files_title),
            style = MaterialTheme.typography.headlineLarge,
            color = MwmColors.Text,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.files_subtitle),
            style = MaterialTheme.typography.labelMedium,
            color = MwmColors.Muted,
        )

        Spacer(Modifier.height(14.dp))
        SearchField(query, onChange = { query = it })

        Spacer(Modifier.height(12.dp))
        SectionChips(selected = section, onSelect = { section = it; query = "" })

        Spacer(Modifier.height(12.dp))

        Box(Modifier.weight(1f)) {
            when {
                error != null -> Message(error!!, MwmColors.Attention)
                loadingGroups -> Message(stringResource(R.string.files_loading), MwmColors.Muted)
                groups.isEmpty() -> Message(stringResource(R.string.files_empty), MwmColors.Muted)
                else -> FileGrid(
                    section = section,
                    groups = groups,
                    filesByGroup = filesByGroup,
                    query = query,
                    creds = creds,
                    onNeedFiles = { path ->
                        val lib = library
                        if (lib != null && path !in filesByGroup) {
                            filesByGroup[path] = runCatching { lib.files(path) }.getOrDefault(emptyList())
                        }
                    },
                    onOpen = onOpen,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        SecondaryButton(stringResource(R.string.done), onBack)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun FileGrid(
    section: RemoteSection,
    groups: List<RemoteGroup>,
    filesByGroup: Map<String, List<RemoteFile>>,
    query: String,
    creds: BoxCredentials?,
    onNeedFiles: suspend (String) -> Unit,
    onOpen: (RemoteFile) -> Unit,
) {
    // Photos tile well at three across; a film or a song is a name you have to be
    // able to read, so those get fewer, wider cells.
    val columns = when (section) {
        RemoteSection.PHOTOS -> 3
        RemoteSection.VIDEO -> 2
        else -> 1
    }
    val monthNames = stringArrayResource(R.array.month_names)
    val searching = query.isNotBlank()

    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (searching) {
            // Name or date, and nothing else. "august" has to find August's
            // photos even though no filename contains the word, so a heading
            // match takes the whole month with it. Nothing reads inside a file.
            val hits = groups.flatMap { group ->
                val inGroup = filesByGroup[group.path].orEmpty()
                if (group.headingText(monthNames).contains(query, ignoreCase = true)) {
                    inGroup
                } else {
                    inGroup.filter { it.name.contains(query, ignoreCase = true) }
                }
            }
            if (hits.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Message(stringResource(R.string.files_no_hits), MwmColors.Muted)
                }
            }
            fileItems(hits, columns, creds, onOpen)
            return@LazyVerticalGrid
        }

        groups.forEach { group ->
            item(key = "h:${group.path}", span = { GridItemSpan(maxLineSpan) }) {
                // Asking for a month's files here, at the heading, is what makes
                // the list load as it is scrolled rather than all at once.
                LaunchedEffect(group.path) { onNeedFiles(group.path) }
                GroupHeading(
                    title = group.headingText(monthNames),
                    count = filesByGroup[group.path]?.size,
                )
            }
            fileItems(filesByGroup[group.path].orEmpty(), columns, creds, onOpen)
        }
    }
}

private fun LazyGridScope.fileItems(
    files: List<RemoteFile>,
    columns: Int,
    creds: BoxCredentials?,
    onOpen: (RemoteFile) -> Unit,
) {
    items(files.size, key = { files[it].path }) { index ->
        val file = files[index]
        if (columns >= 2 && file.kind != FileKind.DOCUMENT) {
            Tile(file, creds, onOpen)
        } else {
            FileRow(file, onOpen)
        }
    }
}

/** A square tile: the photo itself, or a play badge for a film. */
@Composable
private fun Tile(file: RemoteFile, creds: BoxCredentials?, onOpen: (RemoteFile) -> Unit) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(MwmColors.Border)
            .clickable { onOpen(file) },
        contentAlignment = Alignment.Center,
    ) {
        if (file.kind == FileKind.IMAGE && creds != null) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(Graph.remoteUrl(creds, file.path))
                    // The box serves originals, not thumbnails. Without an
                    // explicit size Coil would decode a 12-megapixel bitmap for
                    // a 120 dp tile and a screenful would cost hundreds of MB.
                    .size(THUMB_PX)
                    .crossfade(true)
                    .build(),
                imageLoader = Graph.imageLoader(context, creds),
                contentDescription = file.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier.size(52.dp).clip(CircleShape).background(MwmColors.Action),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(iconFor(file.kind)),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(26.dp),
                    )
                }
                Text(
                    file.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = MwmColors.Text,
                    maxLines = 2,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }
    }
}

/** A full-width row, for songs and documents where the name is what identifies it. */
@Composable
private fun FileRow(file: RemoteFile, onOpen: (RemoteFile) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .clickable { onOpen(file) }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(MwmColors.ActionTint),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(iconFor(file.kind)),
                contentDescription = null,
                tint = MwmColors.Action,
                modifier = Modifier.size(22.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                file.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MwmColors.Text,
                maxLines = 2,
            )
            file.size?.let {
                Text(
                    formatBytes(it),
                    style = MaterialTheme.typography.labelMedium,
                    color = MwmColors.Muted,
                )
            }
        }
        Icon(
            painter = painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = MwmColors.Muted,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun GroupHeading(title: String, count: Int?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = MwmColors.Text)
        Text(
            text = count?.let { formatCount(it) }
                ?: stringResource(R.string.files_loading_month),
            style = MaterialTheme.typography.labelMedium,
            color = MwmColors.Muted,
            modifier = Modifier.padding(bottom = 3.dp),
        )
    }
}

@Composable
private fun SectionChips(selected: RemoteSection, onSelect: (RemoteSection) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RemoteSection.entries.forEach { s ->
            val active = s == selected
            Text(
                text = stringResource(labelFor(s)),
                style = MaterialTheme.typography.labelLarge,
                color = if (active) Color.White else MwmColors.Text,
                modifier = Modifier
                    .clip(RoundedCornerShape(22.dp))
                    .background(if (active) MwmColors.Action else Color.White)
                    .clickable { onSelect(s) }
                    .padding(horizontal = 18.dp, vertical = 11.dp),
            )
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        singleLine = true,
        // Filenames and dates only. Nothing here reads inside a file, and the box
        // is never asked to index content.
        placeholder = {
            Text(
                stringResource(R.string.files_search_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MwmColors.Muted,
            )
        },
        leadingIcon = {
            Icon(
                painter = painterResource(R.drawable.ic_search),
                contentDescription = null,
                tint = MwmColors.Muted,
                modifier = Modifier.size(20.dp),
            )
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Color.White,
            unfocusedContainerColor = Color.White,
            focusedBorderColor = MwmColors.Action,
            unfocusedBorderColor = MwmColors.Border,
            focusedTextColor = MwmColors.Text,
            unfocusedTextColor = MwmColors.Text,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Message(text: String, color: Color) {
    Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

/** Norwegian or English month name comes from resources; the numbers come from the path. */
private fun RemoteGroup.headingText(monthNames: Array<String>): String {
    val y = year
    val m = month
    if (y != null && m != null) {
        val name = monthNames.getOrNull(m - 1) ?: return y.toString()
        return "$name $y"
    }
    return folderTitle.orEmpty()
}

private fun labelFor(s: RemoteSection) = when (s) {
    RemoteSection.PHOTOS -> R.string.cat_images
    RemoteSection.MUSIC -> R.string.cat_audio
    RemoteSection.VIDEO -> R.string.cat_video
    RemoteSection.PICKED -> R.string.files_section_picked
}

private fun iconFor(kind: FileKind) = when (kind) {
    FileKind.VIDEO -> R.drawable.ic_play
    FileKind.AUDIO -> R.drawable.ic_note
    FileKind.IMAGE -> R.drawable.ic_image
    FileKind.DOCUMENT -> R.drawable.ic_document
}

/**
 * Decode target for grid tiles, in pixels. Three across on a phone is roughly
 * 120 dp, so 360 px covers a 3x density screen without decoding the original.
 */
private const val THUMB_PX = 360
