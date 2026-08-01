package no.mwmai.mwmcloud.net

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source

/**
 * [Transport] over WebDAV.
 *
 * Known limitation, load-bearing for everything above: **PUT cannot resume.** A
 * dropped upload restarts from zero, which is why the scheduler defaults to
 * unmetered networks. If large-file failures become common in practice, the fix
 * is a second [Transport] over SFTP, not patching this class.
 *
 * @param baseUrl e.g. `https://u000000.your-storagebox.de`
 */
class WebDavTransport(
    baseUrl: String,
    private val username: String,
    private val password: String,
    private val client: OkHttpClient = defaultClient(),
) : Transport {

    private val base: HttpUrl = baseUrl.trimEnd('/').toHttpUrl()
    private val credential = Credentials.basic(username, password)

    override suspend fun testConnection() = io {
        // Depth 0 asks only about the root itself: cheapest call that still
        // proves host, TLS, credentials and reachability all at once.
        request("PROPFIND", url("/"), header = "Depth" to "0").use { r ->
            r.requireSuccess("test connection")
        }
    }

    override suspend fun ensureCollection(path: String) = io {
        var current = ""
        for (segment in path.trim('/').split('/').filter { it.isNotEmpty() }) {
            current += "/$segment"
            request("MKCOL", url(current)).use { r ->
                when (r.code) {
                    // 405 means it is already there, which is success for us.
                    201, 405 -> Unit
                    else -> r.requireSuccess("create collection $current")
                }
            }
        }
    }

    override suspend fun put(path: String, content: Content) = io {
        val body = object : RequestBody() {
            override fun contentType() = content.contentType?.toMediaTypeOrNull()
            override fun contentLength() = content.length
            override fun writeTo(sink: BufferedSink) {
                content.open().use { sink.writeAll(it.source()) }
            }
        }
        request("PUT", url(path), body = body).use { r -> r.requireSuccess("upload $path") }
    }

    override suspend fun list(path: String): List<RemoteEntry> = io {
        request("PROPFIND", url(path), header = "Depth" to "1").use { r ->
            r.requireSuccess("list $path")
            val stream = r.body?.byteStream()
                ?: throw TransportException(FailureKind.PROTOCOL, "Empty PROPFIND response for $path")
            PropfindParser.parse(stream, path)
        }
    }

    /**
     * The returned stream owns the live response, so closing it closes the
     * connection. That is why this one call does not use [Response.use].
     */
    override suspend fun get(path: String): InputStream = io {
        val response = request("GET", url(path))
        try {
            response.requireSuccess("download $path")
            response.body?.byteStream()
                ?: throw TransportException(FailureKind.PROTOCOL, "Empty body for $path")
        } catch (e: Throwable) {
            response.close()
            throw e
        }
    }

    override suspend fun delete(path: String) = io {
        request("DELETE", url(path)).use { r ->
            // Already gone is the state the caller wanted.
            if (r.code != 404) r.requireSuccess("delete $path")
        }
    }

    private fun url(path: String): HttpUrl {
        val builder = base.newBuilder()
        path.trim('/').split('/').filter { it.isNotEmpty() }.forEach(builder::addPathSegment)
        return builder.build()
    }

    private fun request(
        method: String,
        url: HttpUrl,
        body: RequestBody? = null,
        header: Pair<String, String>? = null,
    ): Response {
        // OkHttp rejects a null body for methods it believes require one.
        val effectiveBody = body ?: if (method in METHODS_REQUIRING_BODY) EMPTY_BODY else null
        val req = Request.Builder()
            .url(url)
            .method(method, effectiveBody)
            .header("Authorization", credential)
            .apply { header?.let { (k, v) -> header(k, v) } }
            .build()
        return try {
            client.newCall(req).execute()
        } catch (e: IOException) {
            throw TransportException(FailureKind.NETWORK, "${method} ${url.encodedPath} failed", e)
        }
    }

    private fun Response.requireSuccess(what: String) {
        if (isSuccessful) return
        val kind = when (code) {
            401, 403 -> FailureKind.AUTH
            404 -> FailureKind.NOT_FOUND
            405, 409, 412 -> FailureKind.CONFLICT
            507 -> FailureKind.OUT_OF_SPACE
            in 500..599 -> FailureKind.SERVER
            else -> FailureKind.PROTOCOL
        }
        throw TransportException(kind, "Could not $what (HTTP $code)")
    }

    private suspend inline fun <T> io(crossinline block: () -> T): T =
        withContext(Dispatchers.IO) { block() }

    companion object {
        private val METHODS_REQUIRING_BODY = setOf("POST", "PUT", "PATCH", "PROPFIND")
        private val EMPTY_BODY = ByteArray(0).let { RequestBody.create(null, it) }

        /**
         * Timeouts are generous on write because a large video on a slow
         * connection is normal, not a fault. Call timeout stays off for the same
         * reason: a legitimate multi-gigabyte upload must not be killed by a clock.
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.MINUTES)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
