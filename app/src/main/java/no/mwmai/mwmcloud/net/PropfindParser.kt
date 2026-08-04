package no.mwmai.mwmcloud.net

import java.io.InputStream
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource

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
            newSafeBuilder().parse(input)
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

    /**
     * A parser that will not fetch anything the response asks it to.
     *
     * The hardening features below are the standard JVM way to close XXE, and on
     * a desktop JVM they work. **On Android they all throw.** Android's
     * `DocumentBuilderFactoryImpl.setFeature` recognises exactly two names,
     * namespaces and validation, and throws `ParserConfigurationException` for
     * anything else *regardless of the value passed*. Setting them unguarded
     * meant every listing on a real phone failed to parse, which surfaced as
     * "could not reach your storage" on the file screen and, worse, as a verify
     * that reported all 444 freshly uploaded files missing. Uploading never broke,
     * because PUT and MKCOL do not parse a response body, so the fault sat
     * invisible until the first screen that listed anything.
     *
     * So the features are attempted and their rejection tolerated, and the actual
     * guarantee comes from the entity resolver: every external entity resolves to
     * an empty document, on every platform, whether or not a doctype got through.
     */
    private fun newSafeBuilder(): javax.xml.parsers.DocumentBuilder {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { isXIncludeAware = false }
            runCatching { isExpandEntityReferences = false }
            HARDENING.forEach { (name, value) -> runCatching { setFeature(name, value) } }
        }
        return factory.newDocumentBuilder().apply {
            // The load-bearing defence. A hostile or compromised server cannot
            // turn a directory listing into a read of a local file, because
            // nothing it names is ever fetched.
            setEntityResolver { _, _ -> InputSource(StringReader("")) }
        }
    }

    private val HARDENING = listOf(
        "http://apache.org/xml/features/disallow-doctype-decl" to true,
        "http://xml.org/sax/features/external-general-entities" to false,
        "http://xml.org/sax/features/external-parameter-entities" to false,
    )

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
        return percentDecode(path)
    }

    /**
     * Plain RFC 3986 percent-decoding, not `URLDecoder`. `URLDecoder` implements
     * form semantics: it turns `+` into a space, so any file with `+` in its name
     * round-tripped wrong — uploaded literal, listed as something else, reported
     * missing forever and re-uploaded by every repair. It also throws on a bare
     * `%`, which let one oddly named file on the box kill its whole directory
     * listing. Here `+` stays `+`, and a `%` not followed by two hex digits is
     * kept literally instead of taking the listing down.
     */
    private fun percentDecode(encoded: String): String {
        val out = java.io.ByteArrayOutputStream(encoded.length)
        var i = 0
        while (i < encoded.length) {
            val c = encoded[i]
            val hi = if (c == '%' && i + 2 < encoded.length) {
                Character.digit(encoded[i + 1], 16)
            } else {
                -1
            }
            val lo = if (hi >= 0) Character.digit(encoded[i + 2], 16) else -1
            if (hi >= 0 && lo >= 0) {
                out.write((hi shl 4) or lo)
                i += 3
            } else {
                out.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
        }
        return out.toByteArray().toString(Charsets.UTF_8)
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
