package no.mwmai.mwmcloud.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PropfindParserTest {

    /** Shape and prefix taken from a real Hetzner Storage Box response. */
    private val hetznerStyle = """
        <?xml version="1.0" encoding="utf-8"?>
        <D:multistatus xmlns:D="DAV:">
          <D:response>
            <D:href>/Bilder/2026/08/</D:href>
            <D:propstat>
              <D:prop>
                <D:resourcetype><D:collection/></D:resourcetype>
                <D:getlastmodified>Fri, 01 Aug 2026 12:00:00 GMT</D:getlastmodified>
              </D:prop>
              <D:status>HTTP/1.1 200 OK</D:status>
            </D:propstat>
          </D:response>
          <D:response>
            <D:href>/Bilder/2026/08/IMG_1234.jpg</D:href>
            <D:propstat>
              <D:prop>
                <D:resourcetype/>
                <D:getcontentlength>4194304</D:getcontentlength>
                <D:getlastmodified>Fri, 01 Aug 2026 09:30:00 GMT</D:getlastmodified>
              </D:prop>
              <D:status>HTTP/1.1 200 OK</D:status>
            </D:propstat>
          </D:response>
        </D:multistatus>
    """.trimIndent()

    @Test
    fun `parses entries and excludes the queried collection itself`() {
        val entries = PropfindParser.parse(hetznerStyle.byteInputStream(), "/Bilder/2026/08")

        assertEquals(1, entries.size)
        val file = entries.single()
        assertEquals("/Bilder/2026/08/IMG_1234.jpg", file.path)
        assertEquals("IMG_1234.jpg", file.name)
        assertEquals(4194304L, file.size)
        assertEquals(false, file.isCollection)
    }

    @Test
    fun `trailing slash on the queried path does not change what is excluded`() {
        val withSlash = PropfindParser.parse(hetznerStyle.byteInputStream(), "/Bilder/2026/08/")
        val without = PropfindParser.parse(hetznerStyle.byteInputStream(), "/Bilder/2026/08")
        assertEquals(without.map { it.path }, withSlash.map { it.path })
    }

    @Test
    fun `namespace prefix is irrelevant`() {
        // Same document with a lowercase prefix. Servers pick their own, and some
        // use none at all, so matching must be on namespace URI and local name.
        // The declaration has to be renamed too, or the document is simply invalid.
        val lowercase = hetznerStyle
            .replace("D:", "d:")
            .replace("xmlns:D=", "xmlns:d=")
        assertEquals(
            listOf("/Bilder/2026/08/IMG_1234.jpg"),
            PropfindParser.parse(lowercase.byteInputStream(), "/Bilder/2026/08").map { it.path },
        )

        // And with no prefix at all, via a default namespace.
        val unprefixed = """
            <?xml version="1.0" encoding="utf-8"?>
            <multistatus xmlns="DAV:">
              <response>
                <href>/Bilder/a.jpg</href>
                <propstat>
                  <prop><resourcetype/><getcontentlength>3</getcontentlength></prop>
                  <status>HTTP/1.1 200 OK</status>
                </propstat>
              </response>
            </multistatus>
        """.trimIndent()
        assertEquals(
            listOf("/Bilder/a.jpg"),
            PropfindParser.parse(unprefixed.byteInputStream(), "/Bilder").map { it.path },
        )
    }

    @Test
    fun `percent-encoded hrefs are decoded, including Norwegian characters`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <D:multistatus xmlns:D="DAV:">
              <D:response>
                <D:href>/Dokumenter/%C3%A5rsrapport%202026.pdf</D:href>
                <D:propstat>
                  <D:prop><D:resourcetype/><D:getcontentlength>1024</D:getcontentlength></D:prop>
                  <D:status>HTTP/1.1 200 OK</D:status>
                </D:propstat>
              </D:response>
            </D:multistatus>
        """.trimIndent()

        val entry = PropfindParser.parse(xml.byteInputStream(), "/Dokumenter").single()
        assertEquals("/Dokumenter/årsrapport 2026.pdf", entry.path)
        assertEquals("årsrapport 2026.pdf", entry.name)
    }

    @Test
    fun `absolute-URL hrefs reduce to a path`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <D:multistatus xmlns:D="DAV:">
              <D:response>
                <D:href>https://example.com/Musikk/song.mp3</D:href>
                <D:propstat>
                  <D:prop><D:resourcetype/><D:getcontentlength>10</D:getcontentlength></D:prop>
                  <D:status>HTTP/1.1 200 OK</D:status>
                </D:propstat>
              </D:response>
            </D:multistatus>
        """.trimIndent()

        assertEquals("/Musikk/song.mp3", PropfindParser.parse(xml.byteInputStream(), "/Musikk").single().path)
    }

    @Test
    fun `404 propstat blocks are ignored rather than read as real values`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <D:multistatus xmlns:D="DAV:">
              <D:response>
                <D:href>/Video/clip.mp4</D:href>
                <D:propstat>
                  <D:prop><D:resourcetype/><D:getcontentlength>512</D:getcontentlength></D:prop>
                  <D:status>HTTP/1.1 200 OK</D:status>
                </D:propstat>
                <D:propstat>
                  <D:prop><D:getcontentlanguage/></D:prop>
                  <D:status>HTTP/1.1 404 Not Found</D:status>
                </D:propstat>
              </D:response>
            </D:multistatus>
        """.trimIndent()

        val entry = PropfindParser.parse(xml.byteInputStream(), "/Video").single()
        assertEquals(512L, entry.size)
    }

    @Test
    fun `missing size and date come back null rather than zero`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <D:multistatus xmlns:D="DAV:">
              <D:response>
                <D:href>/Musikk/album/</D:href>
                <D:propstat>
                  <D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop>
                  <D:status>HTTP/1.1 200 OK</D:status>
                </D:propstat>
              </D:response>
            </D:multistatus>
        """.trimIndent()

        val entry = PropfindParser.parse(xml.byteInputStream(), "/Musikk").single()
        assertTrue(entry.isCollection)
        assertNull("a collection with no reported size must be null, not 0", entry.size)
        assertNull(entry.lastModified)
    }

    @Test
    fun `malformed xml fails as PROTOCOL, which is not retryable`() {
        val e = assertThrows(TransportException::class.java) {
            PropfindParser.parse("<D:multistatus".byteInputStream(), "/")
        }
        assertEquals(FailureKind.PROTOCOL, e.kind)
        assertEquals(false, e.kind.isRetryable)
    }

    @Test
    fun `external entities are not resolved`() {
        // A hostile or compromised server must not be able to turn a directory
        // listing into a local file read.
        val xxe = """
            <?xml version="1.0"?>
            <!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <D:multistatus xmlns:D="DAV:">
              <D:response><D:href>&xxe;</D:href></D:response>
            </D:multistatus>
        """.trimIndent()

        // Doctype declarations are rejected outright, so this never reaches entity
        // expansion. Either way the requirement is the same: no file read.
        val e = assertThrows(TransportException::class.java) {
            PropfindParser.parse(xxe.byteInputStream(), "/")
        }
        assertEquals(FailureKind.PROTOCOL, e.kind)
    }
}
