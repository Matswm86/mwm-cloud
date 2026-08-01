package no.mwmai.mwmcloud.net

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the bug that made every listing fail on a real phone.
 *
 * `DocumentBuilderFactory.setFeature` is the standard way to close XXE and works
 * on a desktop JVM. Android's implementation recognises exactly two feature names
 * and throws `ParserConfigurationException` for every other one, whatever value
 * is passed. Setting them unguarded meant no PROPFIND response ever parsed on a
 * device: the file screen said "could not reach your storage" and the verify
 * panel reported 444 freshly uploaded files as missing. Uploads were fine
 * throughout, because PUT and MKCOL never parse a body.
 *
 * These tests cannot run Android's parser, so they pin the two properties that
 * make the fix correct on both: parsing must not depend on any optional feature,
 * and external entities must not resolve even when a doctype gets through.
 */
class PropfindParserHardeningTest {

    @Test
    fun `a response parses without relying on any optional parser feature`() {
        val entries = PropfindParser.parse(MULTISTATUS.byteInputStream(), "/Bilder/2026/08")

        assertEquals(listOf("IMG_1.jpg", "IMG_2.jpg"), entries.map { it.name })
        assertEquals(1234L, entries[0].size)
    }

    /**
     * The load-bearing security property, now carried by the entity resolver
     * rather than by a feature flag Android refuses to set.
     */
    @Test
    fun `an external entity does not read a local file`() {
        val secret = File.createTempFile("mwmcloud-xxe", ".txt").apply {
            writeText("TOP-SECRET-KEYSET")
            deleteOnExit()
        }

        val hostile = """
            <?xml version="1.0"?>
            <!DOCTYPE multistatus [ <!ENTITY xxe SYSTEM "file://${secret.absolutePath}"> ]>
            <D:multistatus xmlns:D="DAV:">
              <D:response>
                <D:href>/Bilder/2026/08/&xxe;</D:href>
                <D:propstat><D:status>HTTP/1.1 200 OK</D:status>
                  <D:prop><D:getcontentlength>1</D:getcontentlength></D:prop>
                </D:propstat>
              </D:response>
            </D:multistatus>
        """.trimIndent()

        // Either the doctype is refused outright, or it is parsed with the entity
        // resolved to nothing. Both are acceptable; leaking the file is not.
        val names = runCatching {
            PropfindParser.parse(hostile.byteInputStream(), "/Bilder/2026/08").map { it.path }
        }.getOrDefault(emptyList())

        assertFalse(
            "the parser leaked local file contents: $names",
            names.any { it.contains("TOP-SECRET") },
        )
        assertTrue(names.none { it.contains(secret.absolutePath) })
    }

    private companion object {
        val MULTISTATUS = """
            <?xml version="1.0" encoding="utf-8"?>
            <D:multistatus xmlns:D="DAV:">
              <D:response>
                <D:href>/Bilder/2026/08/</D:href>
                <D:propstat><D:status>HTTP/1.1 200 OK</D:status>
                  <D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop>
                </D:propstat>
              </D:response>
              <D:response>
                <D:href>/Bilder/2026/08/IMG_1.jpg</D:href>
                <D:propstat><D:status>HTTP/1.1 200 OK</D:status>
                  <D:prop><D:getcontentlength>1234</D:getcontentlength></D:prop>
                </D:propstat>
              </D:response>
              <D:response>
                <D:href>/Bilder/2026/08/IMG_2.jpg</D:href>
                <D:propstat><D:status>HTTP/1.1 200 OK</D:status>
                  <D:prop><D:getcontentlength>99</D:getcontentlength></D:prop>
                </D:propstat>
              </D:response>
            </D:multistatus>
        """.trimIndent()
    }
}
