package no.mwmai.mwmcloud.data.media

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The four things the app offers to back up, matching the design's toggles. */
enum class MediaCategory(val remoteFolder: String) {
    IMAGES("Bilder"),
    AUDIO("Musikk"),
    VIDEO("Video"),
    DOCUMENTS("Dokumenter"),
    ;

    /** Runtime permission this category needs, or null when it uses the document picker. */
    val permission: String?
        get() = when (this) {
            IMAGES -> if (Build.VERSION.SDK_INT >= 33) "android.permission.READ_MEDIA_IMAGES"
            else "android.permission.READ_EXTERNAL_STORAGE"
            VIDEO -> if (Build.VERSION.SDK_INT >= 33) "android.permission.READ_MEDIA_VIDEO"
            else "android.permission.READ_EXTERNAL_STORAGE"
            AUDIO -> if (Build.VERSION.SDK_INT >= 33) "android.permission.READ_MEDIA_AUDIO"
            else "android.permission.READ_EXTERNAL_STORAGE"
            // MediaStore has no document category; those come from a folder the
            // user picks, which needs no runtime permission at all.
            DOCUMENTS -> null
        }
}

/** One file on the phone that is a candidate for backup. */
data class LocalFile(
    val uri: Uri,
    val displayName: String,
    val size: Long,
    /** Epoch millis. */
    val modified: Long,
    val category: MediaCategory,
) {
    /**
     * Where this lands on the box. Sharded by year and month so no single remote
     * collection grows into the thousands, which keeps listing cheap.
     */
    val remotePath: String
        get() {
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = modified }
            val year = cal.get(java.util.Calendar.YEAR)
            val month = String.format(Locale.US, "%02d", cal.get(java.util.Calendar.MONTH) + 1)
            return "/${category.remoteFolder}/$year/$month/$displayName"
        }

    /**
     * Identity for deduplication. Name plus size plus mtime, not a content hash:
     * hashing multi-gigabyte video on a phone would cost more battery than the
     * upload it saves.
     */
    val dedupeKey: String get() = "$remotePath|$size|$modified"
}

/** Limits that apply to every category. One place, so nothing drifts. */
object BackupLimits {
    /**
     * Files above this are skipped for now.
     *
     * WebDAV PUT cannot resume, so a 20 GB video that drops at 90% restarts from
     * zero and will realistically never finish on a home connection. Skipping it
     * loudly is honest; retrying it forever is not. Raising this only makes sense
     * once the transport can resume, which means SFTP.
     */
    const val MAX_FILE_BYTES: Long = 5L * 1024 * 1024 * 1024
}

/** Aggregate shown on the folder-picker cards: "8 412 bilder · 38 GB". */
data class CategorySummary(
    val category: MediaCategory,
    val fileCount: Int,
    val totalBytes: Long,
    /**
     * Files above [BackupLimits.MAX_FILE_BYTES]. Surfaced in the UI rather than
     * dropped quietly: a backup that silently omits your biggest videos is worse
     * than one that admits it cannot take them yet.
     */
    val skippedTooLarge: Int = 0,
)

/**
 * Reads the phone's media index.
 *
 * Read-only by construction. There is no code path here that deletes or modifies
 * anything on the device, and there is not meant to be one.
 */
class MediaScanner(private val context: Context) {

    suspend fun summarise(category: MediaCategory): CategorySummary = withContext(Dispatchers.IO) {
        var count = 0
        var bytes = 0L
        var oversized = 0
        query(category) { _, _, size, _ ->
            if (size > BackupLimits.MAX_FILE_BYTES) {
                oversized++
            } else {
                count++
                bytes += size
            }
        }
        CategorySummary(category, count, bytes, oversized)
    }

    suspend fun scan(category: MediaCategory): List<LocalFile> = withContext(Dispatchers.IO) {
        buildList {
            query(category) { uri, name, size, modified ->
                if (size <= BackupLimits.MAX_FILE_BYTES) {
                    add(LocalFile(uri, name, size, modified, category))
                }
            }
        }
    }

    private inline fun query(
        category: MediaCategory,
        onRow: (uri: Uri, name: String, size: Long, modified: Long) -> Unit,
    ) {
        val collection = when (category) {
            MediaCategory.IMAGES -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            MediaCategory.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            MediaCategory.AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            // Handled by the document picker, not MediaStore.
            MediaCategory.DOCUMENTS -> return
        }

        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )

        context.contentResolver.query(collection, projection, null, null, null)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val modCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)

            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val name = c.getString(nameCol) ?: continue
                val size = c.getLong(sizeCol)
                // Zero-byte rows are placeholders for media still being written
                // (a photo mid-save, a partial download). Backing one up would
                // store an empty file and mark the real one as done.
                if (size <= 0L) continue
                // MediaStore reports DATE_MODIFIED in seconds, not millis.
                onRow(
                    android.content.ContentUris.withAppendedId(collection, id),
                    name,
                    size,
                    c.getLong(modCol) * 1000L,
                )
            }
        }
    }
}
