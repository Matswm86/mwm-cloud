package no.mwmai.mwmcloud.data.verify

import android.content.Context
import no.mwmai.mwmcloud.Graph
import no.mwmai.mwmcloud.data.media.LocalFile
import no.mwmai.mwmcloud.net.Transport
import no.mwmai.mwmcloud.net.TransportException
import no.mwmai.mwmcloud.settings.BoxCredentials

/**
 * The answer to "is my stuff actually safe?".
 *
 * The ledger records what the app believes it uploaded. This asks the server what
 * is genuinely there and compares, because an upload that returned 201 and a file
 * that exists are different claims, and only the second one matters when you need
 * the photo back.
 */
data class VerifyResult(
    /** Files present on the server at the right size. */
    val confirmed: Int,
    /** Selected on the phone but not found on the server. */
    val missing: List<String>,
    /** Present but a different size than the phone's copy. Corrupt or truncated. */
    val wrongSize: List<String>,
    /** Files skipped because they are over the per-file limit. */
    val tooLarge: Int,
    val checkedAt: Long,
) {
    val total: Int get() = confirmed + missing.size + wrongSize.size
    val allGood: Boolean get() = missing.isEmpty() && wrongSize.isEmpty()
}

class Verifier(private val context: Context) {

    /**
     * @param onProgress called with (checked, total) so a long verify can show
     *   movement instead of appearing hung.
     */
    suspend fun verify(
        creds: BoxCredentials,
        expected: List<LocalFile>,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): VerifyResult {
        val transport: Transport = Graph.transport(creds)

        // Group by remote directory so each one is listed once, not once per file.
        val byDir = expected.groupBy { it.remotePath.substringBeforeLast('/') }

        var confirmed = 0
        val missing = mutableListOf<String>()
        val wrongSize = mutableListOf<String>()
        var checked = 0

        for ((dir, files) in byDir) {
            val remote = try {
                transport.list(dir).associateBy { it.name }
            } catch (e: TransportException) {
                // A directory that does not exist means everything in it is
                // missing. That is a finding, not an error to swallow.
                emptyMap()
            }

            for (file in files) {
                val match = remote[file.displayName]
                when {
                    match == null -> missing += file.remotePath
                    match.size != null && match.size != file.size -> wrongSize += file.remotePath
                    else -> confirmed++
                }
                checked++
            }
            onProgress(checked, expected.size)
        }

        return VerifyResult(
            confirmed = confirmed,
            missing = missing,
            wrongSize = wrongSize,
            tooLarge = 0,
            checkedAt = System.currentTimeMillis(),
        )
    }
}
