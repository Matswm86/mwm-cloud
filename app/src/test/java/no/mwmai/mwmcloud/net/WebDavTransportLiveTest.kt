package no.mwmai.mwmcloud.net

import java.security.MessageDigest
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Exercises [WebDavTransport] against a real storage box.
 *
 * Skipped unless all three environment variables are set, so it never runs in CI
 * and never blocks a contributor who has no box. Run it with:
 *
 * ```
 * MWMCLOUD_TEST_HOST=https://uXXXXXX.your-storagebox.de \
 * MWMCLOUD_TEST_USER=uXXXXXX \
 * MWMCLOUD_TEST_PASS=... \
 *   ./gradlew :app:testDebugUnitTest --tests '*WebDavTransportLiveTest'
 * ```
 *
 * MockWebServer proves the client speaks the protocol we think it does. Only this
 * proves the server agrees. Both are needed: a mock cannot disagree with you.
 *
 * Everything is written under a single scratch collection and deleted afterwards.
 */
class WebDavTransportLiveTest {

    private val host = System.getenv("MWMCLOUD_TEST_HOST")
    private val user = System.getenv("MWMCLOUD_TEST_USER")
    private val pass = System.getenv("MWMCLOUD_TEST_PASS")

    private val scratch = "/mwmcloud-livetest"

    private fun transport(): Transport {
        assumeTrue(
            "set MWMCLOUD_TEST_HOST/USER/PASS to run the live transport test",
            !host.isNullOrBlank() && !user.isNullOrBlank() && !pass.isNullOrBlank(),
        )
        return WebDavTransport(host!!, user!!, pass!!)
    }

    @Test
    fun `credentials and reachability`() = runBlocking {
        transport().testConnection()
    }

    @Test
    fun `wrong password is an AUTH failure, not a network one`() = runBlocking {
        val t = transport()
        assumeTrue(t is WebDavTransport)
        val bad = WebDavTransport(host!!, user!!, pass!! + "-wrong")

        val e = runCatching { bad.testConnection() }.exceptionOrNull()
        assertTrue("expected TransportException, got $e", e is TransportException)
        assertEquals(FailureKind.AUTH, (e as TransportException).kind)
        assertEquals("auth failures must not be retried forever", false, e.kind.isRetryable)
    }

    @Test
    fun `full roundtrip - nested collections, upload, list, download, delete`() = runBlocking {
        val t = transport()
        val dir = "$scratch/2026/08"
        try {
            t.ensureCollection(dir)
            // Idempotent: calling it again on an existing tree must not throw.
            t.ensureCollection(dir)

            val small = Random(1).nextBytes(1024)
            val large = Random(2).nextBytes(20 * 1024 * 1024)
            t.put("$dir/small.bin", Content.ofBytes(small))
            t.put("$dir/large.bin", Content.ofBytes(large))

            val listed = t.list(dir).associateBy { it.name }
            assertEquals(setOf("small.bin", "large.bin"), listed.keys)
            assertEquals(1024L, listed.getValue("small.bin").size)
            assertEquals(20L * 1024 * 1024, listed.getValue("large.bin").size)
            assertEquals(false, listed.getValue("small.bin").isCollection)
            assertNotNull("server should report a modification time", listed.getValue("small.bin").lastModified)

            assertEquals(sha256(small), sha256(t.get("$dir/small.bin").use { it.readBytes() }))
            assertEquals(sha256(large), sha256(t.get("$dir/large.bin").use { it.readBytes() }))

            // A name with a space and a Norwegian character must survive the round trip.
            val awkward = "$dir/årsrapport 2026.pdf"
            t.put(awkward, Content.ofBytes("hei".toByteArray()))
            assertTrue(
                "awkward filename did not come back intact",
                t.list(dir).any { it.name == "årsrapport 2026.pdf" },
            )
        } finally {
            runCatching { t.delete(scratch) }
        }
    }

    @Test
    fun `deleting something already gone is not an error`() = runBlocking {
        transport().delete("$scratch/definitely-not-there-${Random.nextLong()}")
    }

    @Test
    fun `listing a missing collection is NOT_FOUND`() = runBlocking {
        val e = runCatching {
            transport().list("$scratch/missing-${Random.nextLong()}")
        }.exceptionOrNull()
        assertEquals(FailureKind.NOT_FOUND, (e as TransportException).kind)
    }

    private fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}
