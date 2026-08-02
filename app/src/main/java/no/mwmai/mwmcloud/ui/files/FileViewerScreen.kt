package no.mwmai.mwmcloud.ui.files

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import no.mwmai.mwmcloud.Graph
import no.mwmai.mwmcloud.R
import no.mwmai.mwmcloud.data.download.Downloader
import no.mwmai.mwmcloud.data.download.SaveOutcome
import no.mwmai.mwmcloud.data.remote.FileKind
import no.mwmai.mwmcloud.data.remote.RemoteFile
import no.mwmai.mwmcloud.settings.BoxCredentials
import no.mwmai.mwmcloud.ui.PrimaryButton
import no.mwmai.mwmcloud.ui.SecondaryButton
import no.mwmai.mwmcloud.ui.formatBytes
import no.mwmai.mwmcloud.ui.theme.MwmColors
import no.mwmai.mwmcloud.ui.theme.MwmDimens

/**
 * One file, opened inside the app.
 *
 * Photos and video are streamed straight off the box rather than downloaded
 * first: the box answers range requests with 206, so a film starts playing and
 * seeks without a copy ever landing on the phone. Anything the app cannot render
 * itself is downloaded to its own cache and handed to whichever app the user
 * already has, through a FileProvider, so no other app is ever given the storage
 * credentials.
 */
@Composable
fun FileViewerScreen(
    file: RemoteFile,
    creds: BoxCredentials?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MwmColors.Background)
            .padding(horizontal = MwmDimens.ScreenPadding),
    ) {
        Spacer(Modifier.height(16.dp))
        Text(
            file.name,
            style = MaterialTheme.typography.titleLarge,
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
        Spacer(Modifier.height(12.dp))

        Box(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            when {
                creds == null -> Text(
                    stringResource(R.string.files_error),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MwmColors.Attention,
                )
                file.kind == FileKind.IMAGE -> ImageView(file, creds)
                file.kind == FileKind.VIDEO || file.kind == FileKind.AUDIO ->
                    PlayerSurface(file, creds)
                else -> DocumentView(file, creds)
            }
        }

        if (creds != null) {
            Spacer(Modifier.height(12.dp))
            SaveToPhone(file, creds)
        }

        Spacer(Modifier.height(12.dp))
        SecondaryButton(stringResource(R.string.back), onBack)
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * Copies this one file back onto the phone, into the phone's own Pictures,
 * Movies, Music or Download folder.
 *
 * Immediate rather than queued: the user is looking at the file, tapped a button,
 * and needs to see it happen. Whole folders go through
 * [no.mwmai.mwmcloud.work.DownloadWorker] instead, because those take minutes.
 *
 * The result line names the real folder the file landed in, not a friendly
 * translation of it. Someone who wants to find the photo afterwards has to be
 * able to type that name into a file manager and have it match.
 */
@Composable
private fun SaveToPhone(file: RemoteFile, creds: BoxCredentials) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val downloader = remember(context) { Downloader(context) }

    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }

    fun save() {
        busy = true
        message = null
        failed = false
        scope.launch {
            try {
                message = when (downloader.save(Graph.transport(creds), file)) {
                    is SaveOutcome.AlreadyThere -> context.getString(R.string.save_already)
                    is SaveOutcome.Saved -> context.getString(
                        R.string.save_done,
                        Downloader.relativeDirFor(file.kind, file.path).trimEnd('/'),
                    )
                }
            } catch (e: Exception) {
                // Covers a dropped connection, a full phone and a filename the
                // filesystem refuses. The user cannot act on which, only on that
                // it did not work.
                message = context.getString(R.string.save_failed)
                failed = true
            } finally {
                busy = false
            }
        }
    }

    val askPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            save()
        } else {
            message = context.getString(R.string.save_needs_permission)
            failed = true
        }
    }

    message?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodyMedium,
            color = if (failed) MwmColors.Attention else MwmColors.Safe,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
    }

    PrimaryButton(
        text = stringResource(R.string.save_to_phone),
        busy = busy,
        onClick = {
            // Android 9 and older need the old write permission; Android 10 and
            // newer need nothing, because the app owns what it inserts.
            val needed = downloader.needsWritePermission() &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ) != PackageManager.PERMISSION_GRANTED
            if (needed) askPermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) else save()
        },
    )
}

@Composable
private fun ImageView(file: RemoteFile, creds: BoxCredentials) {
    val context = LocalContext.current
    AsyncImage(
        model = ImageRequest.Builder(context)
            .data(Graph.remoteUrl(creds, file.path))
            .crossfade(true)
            .build(),
        imageLoader = Graph.imageLoader(context, creds),
        contentDescription = file.name,
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * ExoPlayer over plain HTTP.
 *
 * The credential goes on as a default request property rather than on one call,
 * because the player opens a new request for every seek, and a header set once
 * on the first request would 401 the moment the user drags the scrubber.
 */
// DefaultHttpDataSource, DefaultMediaSourceFactory and PlayerView are all marked
// @UnstableApi in Media3. That is Media3's own API-stability marker, not a warning
// about reliability; opting in here keeps the marker scoped to the one composable
// that uses them rather than silencing it build-wide.
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun PlayerSurface(file: RemoteFile, creds: BoxCredentials) {
    val context = LocalContext.current
    val url = Graph.remoteUrl(creds, file.path)
    val auth = Graph.authHeader(creds)

    val player = remember(url) {
        val http = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(mapOf("Authorization" to auth))
            .setAllowCrossProtocolRedirects(true)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(url))
                prepare()
                playWhenReady = true
            }
    }

    // Without this the player keeps the socket and the audio focus after the
    // screen is gone, and the next film starts underneath the previous one.
    DisposableEffect(player) {
        onDispose { player.release() }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = true
                setBackgroundColor(android.graphics.Color.BLACK)
            }
        },
        modifier = Modifier.fillMaxWidth().height(if (file.kind == FileKind.AUDIO) 200.dp else 320.dp),
    )
}

/**
 * Formats the app cannot render: PDFs, spreadsheets, whatever else ended up in a
 * picked folder. Downloaded to the app's own cache, then handed over by content
 * URI so the receiving app gets the bytes and nothing else.
 */
@Composable
private fun DocumentView(file: RemoteFile, creds: BoxCredentials) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = error ?: stringResource(R.string.files_doc_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = if (error != null) MwmColors.Attention else MwmColors.Muted,
            textAlign = TextAlign.Center,
        )
        PrimaryButton(
            text = stringResource(R.string.files_doc_open),
            busy = busy,
            onClick = {
                busy = true
                error = null
                scope.launch {
                    try {
                        val local = withContext(Dispatchers.IO) { download(context, creds, file) }
                        val uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            local,
                        )
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, mimeOf(file.name))
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        // Covers both "download failed" and "no app on this phone
                        // opens this kind of file". Either way the user needs to
                        // be told, not left looking at a button that did nothing.
                        error = context.getString(R.string.files_doc_failed)
                    } finally {
                        busy = false
                    }
                }
            },
        )
    }
}

private suspend fun download(
    context: android.content.Context,
    creds: BoxCredentials,
    file: RemoteFile,
): File {
    val dir = File(context.cacheDir, "viewer_docs").apply { mkdirs() }
    // Name collisions across different remote folders are real: two picked
    // folders can each hold a "faktura.pdf". The path hash keeps them apart.
    val out = File(dir, "${file.path.hashCode().toUInt()}_${file.name}")
    if (out.exists() && file.size != null && out.length() == file.size) return out

    Graph.transport(creds).get(file.path).use { input ->
        out.outputStream().use { input.copyTo(it) }
    }
    return out
}

private fun mimeOf(name: String): String {
    val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
}
