package no.mwmai.mwmcloud.net

import java.net.HttpURLConnection
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WebDavTransportTest {

    private lateinit var server: MockWebServer
    private lateinit var transport: WebDavTransport

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        transport = WebDavTransport(
            baseUrl = server.url("/").toString().trimEnd('/'),
            username = "u000000",
            password = "hunter2",
        )
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `every request carries basic auth`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(207).setBody(EMPTY_MULTISTATUS))

        transport.testConnection()

        val auth = server.takeRequest().getHeader("Authorization")
        assertTrue("expected a Basic credential, got $auth", auth?.startsWith("Basic ") == true)
    }

    @Test
    fun `credentials are encoded as UTF-8, not ISO-8859-1`() = runBlocking {
        // Regression guard. OkHttp's Credentials.basic defaults to ISO-8859-1, and
        // a real Storage Box answers 401 to that but 207 to the UTF-8 form. Any
        // Norwegian password with æ, ø or å would have failed as "wrong password".
        val password = "hemmelig-æøå-§"
        val t = WebDavTransport(server.url("/").toString().trimEnd('/'), "u000000", password)
        server.enqueue(MockResponse().setResponseCode(207).setBody(EMPTY_MULTISTATUS))

        t.testConnection()

        val header = server.takeRequest().getHeader("Authorization")!!.removePrefix("Basic ")
        val decoded = String(java.util.Base64.getDecoder().decode(header), Charsets.UTF_8)
        assertEquals("u000000:$password", decoded)
    }

    @Test
    fun `testConnection asks for Depth 0, not a full listing`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(207).setBody(EMPTY_MULTISTATUS))

        transport.testConnection()

        val req = server.takeRequest()
        assertEquals("PROPFIND", req.method)
        assertEquals("0", req.getHeader("Depth"))
    }

    @Test
    fun `ensureCollection creates each ancestor in order`() = runBlocking {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(201)) }

        transport.ensureCollection("/Bilder/2026/08")

        val paths = (1..3).map { server.takeRequest().let { r -> r.method to r.path } }
        assertEquals(
            listOf(
                "MKCOL" to "/Bilder",
                "MKCOL" to "/Bilder/2026",
                "MKCOL" to "/Bilder/2026/08",
            ),
            paths,
        )
    }

    @Test
    fun `ensureCollection treats 405 as already exists`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(405))

        // Must not throw. Idempotence is what lets the uploader call this freely.
        transport.ensureCollection("/Bilder")

        assertEquals(1, server.requestCount)
    }

    @Test
    fun `put sends the bytes with a known content length`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201))
        val payload = "hei på deg".toByteArray()

        transport.put("/Dokumenter/notat.txt", Content.ofBytes(payload, "text/plain"))

        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/Dokumenter/notat.txt", req.path)
        assertEquals(payload.size.toLong(), req.bodySize)
        assertEquals(payload.toList(), req.body.readByteArray().toList())
    }

    @Test
    fun `put can be retried because the content re-opens`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(201))
        var opens = 0
        val bytes = "same bytes both times".toByteArray()
        val content = Content(bytes.size.toLong()) { opens++; bytes.inputStream() }

        assertThrows(TransportException::class.java) {
            runBlocking { transport.put("/a.txt", content) }
        }
        transport.put("/a.txt", content)

        assertEquals("content must be re-readable for retry to work", 2, opens)
        server.takeRequest()
        assertEquals(bytes.toList(), server.takeRequest().body.readByteArray().toList())
    }

    @Test
    fun `path segments with spaces and Norwegian characters are encoded`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201))

        transport.put("/Dokumenter/årsrapport 2026.pdf", Content.ofBytes(ByteArray(1)))

        val path = server.takeRequest().path
        assertTrue("space must not appear raw in $path", !path!!.contains(' '))
        assertTrue("expected percent-encoded UTF-8 in $path", path.contains("%C3%A5"))
    }

    @Test
    fun `list parses the server response into entries`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(207).setBody(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <D:multistatus xmlns:D="DAV:">
                  <D:response>
                    <D:href>/Bilder/</D:href>
                    <D:propstat>
                      <D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop>
                      <D:status>HTTP/1.1 200 OK</D:status>
                    </D:propstat>
                  </D:response>
                  <D:response>
                    <D:href>/Bilder/a.jpg</D:href>
                    <D:propstat>
                      <D:prop><D:resourcetype/><D:getcontentlength>7</D:getcontentlength></D:prop>
                      <D:status>HTTP/1.1 200 OK</D:status>
                    </D:propstat>
                  </D:response>
                </D:multistatus>
                """.trimIndent(),
            ),
        )

        val entries = transport.list("/Bilder")

        assertEquals(listOf("/Bilder/a.jpg"), entries.map { it.path })
        assertEquals(7L, entries.single().size)
        assertEquals("1", server.takeRequest().getHeader("Depth"))
    }

    @Test
    fun `delete tolerates an already-missing path`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(HttpURLConnection.HTTP_NOT_FOUND))

        // Gone is the state the caller asked for. Not an error.
        transport.delete("/Bilder/gone.jpg")

        assertEquals("DELETE", server.takeRequest().method)
    }

    @Test
    fun `http status maps to the failure kind that drives retry`() {
        val cases = mapOf(
            401 to FailureKind.AUTH,
            403 to FailureKind.AUTH,
            404 to FailureKind.NOT_FOUND,
            409 to FailureKind.CONFLICT,
            507 to FailureKind.OUT_OF_SPACE,
            500 to FailureKind.SERVER,
            502 to FailureKind.SERVER,
        )

        cases.forEach { (code, expected) ->
            server.enqueue(MockResponse().setResponseCode(code))
            val e = assertThrows(
                "HTTP $code",
                TransportException::class.java,
            ) { runBlocking { transport.put("/x", Content.ofBytes(ByteArray(1))) } }
            assertEquals("HTTP $code", expected, e.kind)
            server.takeRequest()
        }
    }

    @Test
    fun `only network and server failures are retryable`() {
        // Auth and out-of-space must reach the user; retrying them forever would
        // hide a problem only the user can fix.
        assertEquals(false, FailureKind.AUTH.isRetryable)
        assertEquals(false, FailureKind.OUT_OF_SPACE.isRetryable)
        assertEquals(false, FailureKind.NOT_FOUND.isRetryable)
        assertEquals(false, FailureKind.CONFLICT.isRetryable)
        assertEquals(false, FailureKind.PROTOCOL.isRetryable)
        assertEquals(true, FailureKind.NETWORK.isRetryable)
        assertEquals(true, FailureKind.SERVER.isRetryable)
    }

    @Test
    fun `a dropped connection is a retryable network failure`() {
        server.enqueue(
            MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START),
        )

        val e = assertThrows(TransportException::class.java) {
            runBlocking { transport.testConnection() }
        }
        assertEquals(FailureKind.NETWORK, e.kind)
        assertTrue(e.kind.isRetryable)
    }

    private companion object {
        const val EMPTY_MULTISTATUS =
            """<?xml version="1.0" encoding="utf-8"?><D:multistatus xmlns:D="DAV:"/>"""
    }
}
