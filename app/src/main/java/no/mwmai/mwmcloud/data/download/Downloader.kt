package no.mwmai.mwmcloud.data.download

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.mwmai.mwmcloud.data.remote.FileKind
import no.mwmai.mwmcloud.data.remote.RemoteFile
import no.mwmai.mwmcloud.net.Transport

/**
 * Copies a file from the box back onto the phone.
 *
 * The other direction from [no.mwmai.mwmcloud.work.UploadWorker], and the half
 * that makes the app's promise checkable: until something has been restored and
 * opened, "your photos are safe" is an assertion, not a fact.
 *
 * Restored files go into the phone's own public folders, not into app storage.
 * A photo has to appear in the gallery and survive uninstalling this app, or it
 * has not really come back. That is why this goes through MediaStore rather than
 * writing somewhere private and calling it done.
 *
 * Nothing here deletes or overwrites. A file already on the phone with the same
 * name and size is left exactly as it is and reported as [SaveOutcome.AlreadyThere].
 */
class Downloader(context: Context) {

    private val context = context.applicationContext

    /**
     * Fetches [file] and writes it into the phone's public storage.
     *
     * @return where it landed, and whether it was written now or was already there.
     */
    suspend fun save(transport: Transport, file: RemoteFile): SaveOutcome =
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= 29) saveToMediaStore(transport, file)
            else saveToPublicDir(transport, file)
        }

    /**
     * Android 9 and older have no scoped-storage insert, so writing to the public
     * folders needs the old blanket permission. From Android 10 the app owns what
     * it inserts and no permission is involved at all.
     */
    fun needsWritePermission(): Boolean = Build.VERSION.SDK_INT < 29

    // -- Android 10 and newer -------------------------------------------------

    @androidx.annotation.RequiresApi(29)
    private suspend fun saveToMediaStore(transport: Transport, file: RemoteFile): SaveOutcome {
        val dir = relativeDirFor(file.kind, file.path)
        val name = safeName(file.name)
        val collection = collectionFor(file.kind)

        existing(collection, dir, name, file.size)?.let { return SaveOutcome.AlreadyThere(it) }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeOf(name))
            put(MediaStore.MediaColumns.RELATIVE_PATH, dir)
            // Hides the row from the gallery until the bytes are all there, so a
            // half-downloaded photo never shows up as a corrupt thumbnail.
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values)
            ?: throw IOException("MediaStore refused ${file.name}")

        try {
            val body = transport.get(file.path)
            val out = resolver.openOutputStream(uri)
                ?: run { body.close(); throw IOException("Could not open ${file.name} for writing") }
            out.use { sink -> body.use { it.copyTo(sink) } }
        } catch (e: Throwable) {
            // A pending row with no bytes is invisible but real, and would
            // accumulate on every failed attempt. Take it back out.
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }

        resolver.update(
            uri,
            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
            null,
            null,
        )
        return SaveOutcome.Saved(uri)
    }

    /**
     * The MediaStore collection that accepts this kind of file.
     *
     * `VOLUME_EXTERNAL_PRIMARY` rather than `EXTERNAL`: only the primary volume
     * is writable, and inserting into the read-only union throws.
     */
    @androidx.annotation.RequiresApi(29)
    private fun collectionFor(kind: FileKind): Uri {
        val volume = MediaStore.VOLUME_EXTERNAL_PRIMARY
        return when (kind) {
            FileKind.IMAGE -> MediaStore.Images.Media.getContentUri(volume)
            FileKind.VIDEO -> MediaStore.Video.Media.getContentUri(volume)
            FileKind.AUDIO -> MediaStore.Audio.Media.getContentUri(volume)
            FileKind.DOCUMENT -> MediaStore.Downloads.getContentUri(volume)
        }
    }

    /** The row for a file of this exact name, place and size, if the phone already has it. */
    @androidx.annotation.RequiresApi(29)
    private fun existing(collection: Uri, dir: String, name: String, size: Long?): Uri? {
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE)
        val where = "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND " +
            "${MediaStore.MediaColumns.DISPLAY_NAME}=?"
        return context.contentResolver.query(
            collection,
            projection,
            where,
            arrayOf(dir, name),
            null,
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            while (cursor.moveToNext()) {
                // Size is the only cheap check that a previous download finished.
                // Same name at a different size is a different file, and gets
                // written alongside rather than assumed to be the same one.
                val onPhone = cursor.getLong(sizeCol)
                if (size == null || onPhone == size) {
                    return@use ContentUris.withAppendedId(collection, cursor.getLong(idCol))
                }
            }
            null
        }
    }

    // -- Android 9 and older --------------------------------------------------

    private suspend fun saveToPublicDir(transport: Transport, file: RemoteFile): SaveOutcome {
        val dir = File(
            @Suppress("DEPRECATION")
            Environment.getExternalStoragePublicDirectory(baseFolderFor(file.kind)),
            subDirFor(file.path),
        )
        if (!dir.exists() && !dir.mkdirs()) {
            throw IOException("Could not create ${dir.path}")
        }

        val out = File(dir, safeName(file.name))
        if (out.exists() && (file.size == null || out.length() == file.size)) {
            return SaveOutcome.AlreadyThere(Uri.fromFile(out))
        }

        val target = if (out.exists()) unusedName(out) else out
        try {
            val body = transport.get(file.path)
            target.outputStream().use { sink -> body.use { it.copyTo(sink) } }
        } catch (e: Throwable) {
            runCatching { target.delete() }
            throw e
        }

        // Without this the file is on disk but absent from the gallery and from
        // every other app's file picker, which to the user means it is not there.
        MediaScannerConnection.scanFile(context, arrayOf(target.path), null, null)
        return SaveOutcome.Saved(Uri.fromFile(target))
    }

    private fun unusedName(taken: File): File {
        val stem = taken.name.substringBeforeLast('.', taken.name)
        val ext = taken.name.substringAfterLast('.', "")
        val suffix = if (ext.isEmpty()) "" else ".$ext"
        var n = 2
        var candidate = File(taken.parentFile, "$stem ($n)$suffix")
        while (candidate.exists() && n < MAX_NAME_ATTEMPTS) {
            n++
            candidate = File(taken.parentFile, "$stem ($n)$suffix")
        }
        return candidate
    }

    companion object {
        /** The one folder everything restored by this app goes under. */
        const val APP_FOLDER = "MWM Cloud"

        private const val MAX_NAME_ATTEMPTS = 1000

        /**
         * Where a restored file belongs, as a MediaStore `RELATIVE_PATH`.
         *
         * The shape the file has on the box is kept below the app folder, so a
         * restore of `/Bilder/2026/08/IMG_1.jpg` lands in
         * `Pictures/MWM Cloud/2026/08/` rather than dumping six thousand photos
         * into one directory. The section root itself is dropped: `Bilder` is
         * the box's name for what the phone already calls `Pictures`.
         *
         * Always ends in a slash, which is what MediaStore stores and therefore
         * what a `RELATIVE_PATH=?` query has to match.
         */
        fun relativeDirFor(kind: FileKind, remotePath: String): String {
            val sub = subDirFor(remotePath)
            val base = "${baseFolderFor(kind)}/$APP_FOLDER"
            return if (sub.isEmpty()) "$base/" else "$base/$sub/"
        }

        /**
         * The part of the remote path that is worth keeping: everything between
         * the section root and the filename.
         */
        fun subDirFor(remotePath: String): String =
            remotePath.trim('/')
                .split('/')
                .dropLast(1) // the filename
                .drop(1) // the section root: Bilder, Musikk, Video, Valgt
                // Cleaned but not renamed: a segment that is nothing but illegal
                // characters is dropped, where a *file* of that name would be
                // given a fallback. An invented directory would silently scatter
                // a restore across folders nobody asked for.
                .map { clean(it).take(MAX_NAME_LENGTH) }
                .filter { it.isNotEmpty() }
                .joinToString("/")

        /**
         * Public folder per kind. These have to be the folders the matching
         * MediaStore collection accepts: images under `Pictures`, video under
         * `Movies`, audio under `Music`. Anything else is rejected with an
         * unhelpful IllegalArgumentException at insert time.
         *
         * Written out rather than taken from [Environment]: those constants are
         * plain mutable statics in the framework, so they read back as null in
         * unit tests and this decision would be untestable.
         */
        fun baseFolderFor(kind: FileKind): String = when (kind) {
            FileKind.IMAGE -> "Pictures"
            FileKind.VIDEO -> "Movies"
            FileKind.AUDIO -> "Music"
            FileKind.DOCUMENT -> "Download"
        }

        /**
         * Strips what a filesystem cannot hold. The box is happy with names the
         * phone is not, and a `:` in a filename fails the insert rather than
         * being cleaned up for us.
         */
        fun safeName(name: String): String {
            val cleaned = clean(name)
            if (cleaned.isEmpty()) return FALLBACK_NAME
            if (cleaned.length <= MAX_NAME_LENGTH) return cleaned

            // Truncating blindly would eat the extension, and a photo with no
            // extension is one the gallery will not open and MediaStore cannot
            // type. Keep the tail, shorten the front.
            val ext = cleaned.substringAfterLast('.', "")
            return if (ext.isEmpty() || ext.length > MAX_EXTENSION_LENGTH) {
                cleaned.take(MAX_NAME_LENGTH)
            } else {
                cleaned.take(MAX_NAME_LENGTH - ext.length - 1) + ".$ext"
            }
        }

        /** Replaces what a filesystem refuses. Length and emptiness are the caller's problem. */
        private fun clean(s: String): String =
            s.map { if (it in ILLEGAL || it.code < 0x20) '_' else it }
                .joinToString("")
                .trim()
                .trimEnd('.')

        fun mimeOf(name: String): String {
            val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                ?: "application/octet-stream"
        }

        private val ILLEGAL = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

        /** ext4 caps a filename at 255 bytes; leave room for a " (2)" suffix. */
        private const val MAX_NAME_LENGTH = 200

        /** Longer than this after the last dot is part of the name, not a suffix. */
        private const val MAX_EXTENSION_LENGTH = 10

        /** For a name that was nothing but illegal characters. Norwegian for "file". */
        private const val FALLBACK_NAME = "fil"
    }
}

/** What happened to one file. Both cases mean it is on the phone now. */
sealed interface SaveOutcome {
    val uri: Uri

    /** Downloaded during this call. */
    data class Saved(override val uri: Uri) : SaveOutcome

    /** Same name and size was already on the phone, so nothing was fetched. */
    data class AlreadyThere(override val uri: Uri) : SaveOutcome
}
