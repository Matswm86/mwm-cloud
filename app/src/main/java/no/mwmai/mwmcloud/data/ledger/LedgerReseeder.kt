package no.mwmai.mwmcloud.data.ledger

import no.mwmai.mwmcloud.data.media.LocalFile
import no.mwmai.mwmcloud.net.FailureKind
import no.mwmai.mwmcloud.net.Transport
import no.mwmai.mwmcloud.net.TransportException

/**
 * Rebuilds an empty ledger from what is already on the box.
 *
 * A reinstall (or a new phone without a Google backup) starts with an empty
 * ledger, and without this the first backup re-uploads the entire library —
 * tens of gigabytes the box already holds. Instead, each remote directory the
 * scan would write into is listed once, and every local file whose name and
 * size already match the server is recorded as uploaded.
 *
 * Size is the same evidence the verify screen accepts. A same-name file with a
 * different size is NOT seeded: it is honestly not the same bytes, and the
 * upload that follows will replace it.
 */
object LedgerReseeder {

    /**
     * @param files the scan result, already through RemoteNames.resolve, so the
     *   paths checked here are exactly the paths the uploader would write.
     * @return how many files were recorded as already uploaded.
     */
    suspend fun seed(transport: Transport, ledger: UploadLedger, files: List<LocalFile>): Int {
        var seeded = 0
        for ((dir, group) in files.groupBy { it.remotePath.substringBeforeLast('/') }) {
            val remote = try {
                transport.list(dir).associateBy { it.name }
            } catch (e: TransportException) {
                // A directory the box does not have simply holds nothing yet.
                // Any other failure aborts the reseed: recording guesses while
                // the server is unreachable would defeat the point of a ledger.
                if (e.kind != FailureKind.NOT_FOUND) throw e
                continue
            }
            for (file in group) {
                val match = remote[file.displayName] ?: continue
                if (match.size == null || match.size == file.size) {
                    ledger.recordUploaded(
                        dedupeKey = file.dedupeKey,
                        remotePath = file.remotePath,
                        size = file.size,
                        modified = file.modified,
                        uploadedAt = System.currentTimeMillis(),
                    )
                    seeded++
                }
            }
        }
        return seeded
    }
}
