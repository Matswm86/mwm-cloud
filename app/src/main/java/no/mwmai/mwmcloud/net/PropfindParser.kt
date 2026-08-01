package no.mwmai.mwmcloud.net

import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * Parses a WebDAV `PROPFIND` multistatus response.
 *
 * DOM rather than a streaming parser, on purpose. The remote layout shards by
 * year and month (`/Bilder/2026/08/`), so a single collection holds hundreds of
 * entries, not tens of thousands, and the whole response is comfortably small.
 * If a flat layout ever appears, this needs to become streaming.
 *
 * Namespace prefixes are not fixed by the spec — servers emit `D:`, `d:`, or none
 * at all — so matching is on local name and namespace URI, never on the prefix.
 */
internal object PropfindParser {

    private const val DAV_NS = "DAV:"

    /**
     * @param basePath the collection that was queried, so it can be excluded from
     *   its own listing (servers include the collection itself at Depth: 1).
     */
    fun parse(input: InputStream, basePath: String): List<RemoteEntry> {
        val doc = try {
            DocumentBuilderFactory.newInstance()
                .apply {
                    isNamespaceAware = true
                    // The response is untrusted input from the network. Disable
                    // external entity resolution so a hostile or compromised
                    // server cannot turn a directory listing into an XXE read.
                    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                    setFeature("http://xml.org/sax/features/external-general-entities", false)
                    setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                    isXIncludeAware = false
                    isExpandEntityReferences = false
                }
                .newDocumentBuilder()
                .parse(input)
        } catch (e: Exception) {
            throw TransportException(FailureKind.PROTOCOL, "Could not parse PROPFIND response", e)
        }

        val normalisedBase = normalise(basePath)
        val responses = doc.getElementsByTagNameNS(DAV_NS, "response")

        return (0 until responses.length).mapNotNull { i ->
            val entry = parseResponse(responses.item(i) as Element)
            // Skip the queried collection's own entry.
            entry?.takeIf { normalise(it.path) != normalisedBase }
        }
    }

    private fun parseResponse(response: Element): RemoteEntry? {
        val rawHref = response.childText(DAV_NS, "href") ?: return null
        val href = decodeHref(rawHref)

        // Only 200-status propstats carry real values; 404 propstats list the
        // properties the server does not have, and their contents are empty.
        val props = response.elements(DAV_NS, "propstat")
            .filter { it.childText(DAV_NS, "status")?.contains(" 200 ") == true }
            .mapNotNull { it.elements(DAV_NS, "prop").firstOrNull() }

        val isCollection = props.any { prop ->
            prop.elements(DAV_NS, "resourcetype")
                .any { it.elements(DAV_NS, "collection").isNotEmpty() }
        } || href.endsWith("/")

        val size = props.firstNotNullOfOrNull { it.childText(DAV_NS, "getcontentlength") }
            ?.trim()?.toLongOrNull()

        val modified = props.firstNotNullOfOrNull { it.childText(DAV_NS, "getlastmodified") }
            ?.let(::parseHttpDate)

        return RemoteEntry(
            path = normalise(href),
            isCollection = isCollection,
            size = size,
            lastModified = modified,
        )
    }

    /**
     * hrefs may be absolute URLs or absolute paths, and are percent-encoded.
     * Both forms reduce to a path.
     */
    private fun decodeHref(href: String): String {
        val path = if (href.startsWith("http://") || href.startsWith("https://")) {
            val afterScheme = href.substringAfter("://")
            "/" + afterScheme.substringAfter('/', "")
        } else {
            href
        }
        return java.net.URLDecoder.decode(path, "UTF-8")
    }

    /** Leading slash, no trailing slash, so collections and files compare alike. */
    private fun normalise(path: String): String =
        "/" + path.trim('/')

    /** RFC 1123, which is what `getlastmodified` uses. Always GMT. */
    private fun parseHttpDate(raw: String): Long? = try {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("GMT") }
            .parse(raw.trim())
            ?.let(Date::getTime)
    } catch (_: Exception) {
        null
    }

    private fun Element.elements(ns: String, name: String): List<Element> =
        (0 until childNodes.length)
            .map { childNodes.item(it) }
            .filterIsInstance<Element>()
            .filter { it.localName == name && it.namespaceURI == ns }

    private fun Element.childText(ns: String, name: String): String? =
        elements(ns, name).firstOrNull()?.textOf()

    private fun Element.textOf(): String =
        (0 until childNodes.length)
            .map { childNodes.item(it) }
            .filter { it.nodeType == Node.TEXT_NODE || it.nodeType == Node.CDATA_SECTION_NODE }
            .joinToString("") { it.nodeValue.orEmpty() }
}
