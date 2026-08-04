package no.mwmai.mwmcloud.net

import okhttp3.Credentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The viewer fetches straight off the box with Coil and ExoPlayer rather than
 * through [Transport], so it builds its own URLs and its own `Authorization`
 * header. These pin the two helpers it shares with the transport, because a
 * second, subtly different copy of either is exactly how the UTF-8 bug would
 * come back in a place with no test around it.
 */
class RemoteUrlAndAuthTest {

    @Test
    fun `basic auth is encoded as UTF-8, not ISO-8859-1`() {
        val utf8 = WebDavTransport.basicAuth("u123456", "pa§§ord")

        assertEquals(Credentials.basic("u123456", "pa§§ord", Charsets.UTF_8), utf8)
        // The default overload is ISO-8859-1, and a live Storage Box answers 401
        // to it for this password. If these two ever match, the helper regressed.
        assertNotEquals(Credentials.basic("u123456", "pa§§ord"), utf8)
    }

    @Test
    fun `norwegian passwords survive the round trip`() {
        val header = WebDavTransport.basicAuth("bruker", "blåbærsyltetøy")
        val decoded = String(
            java.util.Base64.getDecoder().decode(header.removePrefix("Basic ")),
            Charsets.UTF_8,
        )
        assertEquals("bruker:blåbærsyltetøy", decoded)
    }

    @Test
    fun `each path segment is encoded separately`() {
        val url = WebDavTransport.remoteUrl("https://box.example.com", "/Bilder/2026/08/på tur.jpg")

        assertEquals("/Bilder/2026/08/p%C3%A5%20tur.jpg", url.encodedPath)
        // Decoded, it is the path we asked for: the encoding is transport-level,
        // not a change of name.
        assertEquals(listOf("Bilder", "2026", "08", "på tur.jpg"), url.pathSegments)
    }

    @Test
    fun `a filename with a hash is not truncated into a fragment`() {
        val url = WebDavTransport.remoteUrl("https://box.example.com", "/Valgt/faktura #7.pdf")

        assertEquals(listOf("Valgt", "faktura #7.pdf"), url.pathSegments)
        assertEquals(null, url.fragment)
    }

    @Test
    fun `a trailing slash on the base url does not double up`() {
        val a = WebDavTransport.remoteUrl("https://box.example.com/", "/Video/film.mp4")
        val b = WebDavTransport.remoteUrl("https://box.example.com", "Video/film.mp4")

        assertEquals(a.toString(), b.toString())
        assertEquals("https://box.example.com/Video/film.mp4", a.toString())
    }
}
