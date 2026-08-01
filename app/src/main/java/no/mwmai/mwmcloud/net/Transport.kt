package no.mwmai.mwmcloud.net

import java.io.InputStream

/**
 * A remote file store the app can back up to.
 *
 * Deliberately narrow and protocol-agnostic. WebDAV is the first implementation,
 * but PUT over WebDAV cannot resume a broken upload, so SFTP is a likely second
 * one. Nothing above this interface should know which is in use.
 *
 * Paths are always absolute, slash-separated, and rooted at the account's home
 * directory: `/Bilder/2026/08/IMG_1234.jpg`.
 */
interface Transport {

    /** Verifies host, credentials and reachability in one round trip. */
    suspend fun testConnection()

    /**
     * Creates [path] and every missing ancestor. Succeeds if they already exist,
     * so callers can treat it as idempotent.
     */
    suspend fun ensureCollection(path: String)

    /**
     * Uploads [content] to [path], replacing anything already there.
     *
     * The parent collection must exist; call [ensureCollection] first. [content]
     * may be opened more than once if the request is retried, so it must be
     * re-readable rather than a one-shot stream.
     */
    suspend fun put(path: String, content: Content)

    /** Lists the direct children of [path]. Not recursive. */
    suspend fun list(path: String): List<RemoteEntry>

    /** Opens [path] for reading. The caller closes the stream. */
    suspend fun get(path: String): InputStream

    /** Deletes [path]. Only ever called on remote paths; never touches the device. */
    suspend fun delete(path: String)
}

/**
 * Re-readable upload source. [open] may be called again on retry, which is why
 * this is a factory rather than a plain [InputStream].
 */
class Content(
    val length: Long,
    val contentType: String? = null,
    private val opener: () -> InputStream,
) {
    fun open(): InputStream = opener()

    companion object {
        fun ofBytes(bytes: ByteArray, contentType: String? = null): Content =
            Content(bytes.size.toLong(), contentType) { bytes.inputStream() }
    }
}

/** One entry returned by [Transport.list]. */
data class RemoteEntry(
    /** Absolute path, with no trailing slash even for collections. */
    val path: String,
    val isCollection: Boolean,
    /** Bytes. Null when the server does not report it, which is normal for collections. */
    val size: Long?,
    /** Epoch millis, or null when the server does not report it. */
    val lastModified: Long?,
) {
    val name: String get() = path.substringAfterLast('/')
}

/**
 * Why a transport call failed. The upload scheduler branches on this: [RETRYABLE]
 * kinds go back on the queue with backoff, the rest are surfaced to the user
 * because retrying cannot fix them.
 */
enum class FailureKind {
    /** Wrong username or password. Retrying will not help. */
    AUTH,

    /** Path does not exist. */
    NOT_FOUND,

    /** Parent collection missing, or a collection/file name clash. */
    CONFLICT,

    /** Quota exhausted. The user has to free space or buy more. */
    OUT_OF_SPACE,

    /** Connection failed, timed out, or dropped mid-transfer. Worth retrying. */
    NETWORK,

    /** Server returned 5xx. Worth retrying. */
    SERVER,

    /** Response did not parse. Not retryable — the same bytes will fail again. */
    PROTOCOL,
    ;

    val isRetryable: Boolean get() = this == NETWORK || this == SERVER
}

class TransportException(
    val kind: FailureKind,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
