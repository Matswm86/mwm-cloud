package no.mwmai.mwmcloud.data.media

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads files and folders the user picked by hand through the system picker.
 *
 * This is the escape hatch from MediaStore's fixed categories: it takes any file
 * of any type from anywhere the user can reach, including Downloads and
 * documents, which MediaStore has no category for at all.
 *
 * Read-only. Permissions are persisted so a picked folder keeps working across
 * reboots, but nothing here writes or deletes on the device.
 */
class SafScanner(private val context: Context) {

    /** Expands picked trees and single files into concrete uploadable files. */
    suspend fun scan(treeUris: Set<String>, fileUris: Set<String>): List<LocalFile> =
        withContext(Dispatchers.IO) {
            buildList {
                treeUris.forEach { addAll(walkTree(Uri.parse(it))) }
                fileUris.forEach { raw -> readSingle(Uri.parse(raw))?.let(::add) }
            }
        }

    suspend fun summarise(treeUris: Set<String>, fileUris: Set<String>): CategorySummary =
        withContext(Dispatchers.IO) {
            val all = scan(treeUris, fileUris)
            val fitting = all.filter { it.size <= BackupLimits.MAX_FILE_BYTES }
            CategorySummary(
                category = MediaCategory.DOCUMENTS,
                fileCount = fitting.size,
                totalBytes = fitting.sumOf { it.size },
                skippedTooLarge = all.size - fitting.size,
            )
        }

    /**
     * Walks a picked folder, subfolders included.
     *
     * Iterative rather than recursive: a deep folder tree on a phone should not
     * be able to overflow the stack, and the depth is not ours to bound.
     */
    private fun walkTree(treeUri: Uri): List<LocalFile> {
        val out = mutableListOf<LocalFile>()
        val rootDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return emptyList()

        val queue = ArrayDeque(listOf(rootDocId to relativeRootName(treeUri, rootDocId)))
        val seen = mutableSetOf<String>()

        while (queue.isNotEmpty()) {
            val (docId, prefix) = queue.removeFirst()
            if (!seen.add(docId)) continue

            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            queryChildren(childrenUri) { childId, name, mime, size, modified ->
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    queue.addLast(childId to "$prefix/$name")
                } else if (size > 0) {
                    out += LocalFile(
                        uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId),
                        displayName = name,
                        size = size,
                        modified = modified,
                        category = MediaCategory.DOCUMENTS,
                        explicitRemoteDir = "/Valgt$prefix",
                    )
                }
            }
        }
        return out
    }

    private fun readSingle(uri: Uri): LocalFile? {
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        return runCatching {
            context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use null
                val name = c.getStringOrNull(0) ?: return@use null
                val size = c.getLongOrNull(1) ?: 0L
                if (size <= 0L) return@use null
                LocalFile(
                    uri = uri,
                    displayName = name,
                    size = size,
                    modified = c.getLongOrNull(2)?.takeIf { it > 0 } ?: System.currentTimeMillis(),
                    category = MediaCategory.DOCUMENTS,
                    explicitRemoteDir = "/Valgt",
                )
            }
        }.getOrNull()
    }

    private inline fun queryChildren(
        childrenUri: Uri,
        onRow: (docId: String, name: String, mime: String, size: Long, modified: Long) -> Unit,
    ) {
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        // A picked folder can be revoked or unmounted between runs; that is a
        // reason to skip it, not to fail the whole backup.
        runCatching {
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getStringOrNull(0) ?: continue
                    val name = c.getStringOrNull(1) ?: continue
                    val mime = c.getStringOrNull(2).orEmpty()
                    onRow(
                        id,
                        name,
                        mime,
                        c.getLongOrNull(3) ?: 0L,
                        c.getLongOrNull(4)?.takeIf { it > 0 } ?: System.currentTimeMillis(),
                    )
                }
            }
        }
    }

    /** Uses the picked folder's own name so the remote mirrors what the user chose. */
    private fun relativeRootName(treeUri: Uri, rootDocId: String): String {
        val name = rootDocId.substringAfterLast(':').trim('/').substringAfterLast('/')
        return if (name.isBlank()) "" else "/$name"
    }

    private fun Cursor.getStringOrNull(i: Int): String? =
        if (isNull(i)) null else runCatching { getString(i) }.getOrNull()

    private fun Cursor.getLongOrNull(i: Int): Long? =
        if (isNull(i)) null else runCatching { getLong(i) }.getOrNull()
}
