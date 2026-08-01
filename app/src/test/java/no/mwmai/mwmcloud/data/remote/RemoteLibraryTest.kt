package no.mwmai.mwmcloud.data.remote

import java.io.InputStream
import kotlinx.coroutines.runBlocking
import no.mwmai.mwmcloud.net.Content
import no.mwmai.mwmcloud.net.FailureKind
import no.mwmai.mwmcloud.net.RemoteEntry
import no.mwmai.mwmcloud.net.Transport
import no.mwmai.mwmcloud.net.TransportException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteLibraryTest {

    // ---- file kinds -------------------------------------------------------

    @Test
    fun `extension decides how a file is opened`() {
        assertEquals(FileKind.IMAGE, RemoteFile.kindOf("IMG_0042.JPG"))
        assertEquals(FileKind.IMAGE, RemoteFile.kindOf("ferie.heic"))
        assertEquals(FileKind.VIDEO, RemoteFile.kindOf("bursdag.MP4"))
        assertEquals(FileKind.AUDIO, RemoteFile.kindOf("sang.m4a"))
        assertEquals(FileKind.DOCUMENT, RemoteFile.kindOf("faktura.pdf"))
    }

    @Test
    fun `a file with no extension is treated as a document, not guessed at`() {
        assertEquals(FileKind.DOCUMENT, RemoteFile.kindOf("NOTES"))
        assertEquals(FileKind.DOCUMENT, RemoteFile.kindOf(""))
    }

    // ---- date-sharded sections -------------------------------------------

    @Test
    fun `photo months come back newest first`() = runBlocking {
        val transport = FakeTransport(
            "/Bilder" to listOf(dir("/Bilder/2025"), dir("/Bilder/2026")),
            "/Bilder/2026" to listOf(dir("/Bilder/2026/07"), dir("/Bilder/2026/08")),
            "/Bilder/2025" to listOf(dir("/Bilder/2025/12")),
        )

        val groups = RemoteLibrary(transport).groups(RemoteSection.PHOTOS)

        assertEquals(
            listOf(2026 to 8, 2026 to 7, 2025 to 12),
            groups.map { it.year to it.month },
        )
    }

    @Test
    fun `a collection that is not a year is ignored rather than shown as a month`() = runBlocking {
        val transport = FakeTransport(
            "/Bilder" to listOf(dir("/Bilder/2026"), dir("/Bilder/gammelt"), file("/Bilder/les.txt")),
            "/Bilder/2026" to listOf(dir("/Bilder/2026/08")),
        )

        val groups = RemoteLibrary(transport).groups(RemoteSection.PHOTOS)

        assertEquals(1, groups.size)
        assertEquals(2026 to 8, groups[0].year to groups[0].month)
    }

    @Test
    fun `a section with nothing backed up yet is empty, not an error`() = runBlocking {
        val transport = FakeTransport()

        assertTrue(RemoteLibrary(transport).groups(RemoteSection.VIDEO).isEmpty())
    }

    /**
     * A missing collection means "nothing here yet". A refused password does not,
     * and swallowing it would show an empty library to someone whose files are
     * all present.
     */
    @Test
    fun `an auth failure propagates instead of looking like an empty library`() {
        val transport = object : FakeTransport() {
            override suspend fun list(path: String): List<RemoteEntry> =
                throw TransportException(FailureKind.AUTH, "nope")
        }

        assertThrows(TransportException::class.java) {
            runBlocking { RemoteLibrary(transport).groups(RemoteSection.PHOTOS) }
        }
    }

    // ---- files within a group --------------------------------------------

    @Test
    fun `a month lists only files, newest first`() = runBlocking {
        val transport = FakeTransport(
            "/Bilder/2026/08" to listOf(
                file("/Bilder/2026/08/a.jpg", size = 10, modified = 1_000),
                dir("/Bilder/2026/08/rot"),
                file("/Bilder/2026/08/b.jpg", size = 20, modified = 5_000),
            ),
        )

        val files = RemoteLibrary(transport).files("/Bilder/2026/08")

        assertEquals(listOf("b.jpg", "a.jpg"), files.map { it.name })
        assertEquals(20L, files[0].size)
    }

    @Test
    fun `a month already read is not asked for twice`() = runBlocking {
        val transport = FakeTransport(
            "/Bilder/2026/08" to listOf(file("/Bilder/2026/08/a.jpg")),
        )
        val library = RemoteLibrary(transport)

        library.files("/Bilder/2026/08")
        library.files("/Bilder/2026/08")

        assertEquals(1, transport.calls.count { it == "/Bilder/2026/08" })

        library.invalidate()
        library.files("/Bilder/2026/08")
        assertEquals(2, transport.calls.count { it == "/Bilder/2026/08" })
    }

    // ---- hand-picked section ---------------------------------------------

    @Test
    fun `picked folders are walked, and only folders holding files become groups`() = runBlocking {
        val transport = FakeTransport(
            "/Valgt" to listOf(dir("/Valgt/Skatt"), dir("/Valgt/Tomt")),
            "/Valgt/Skatt" to listOf(dir("/Valgt/Skatt/2025"), file("/Valgt/Skatt/oversikt.pdf")),
            "/Valgt/Skatt/2025" to listOf(file("/Valgt/Skatt/2025/selvangivelse.pdf")),
            // No files anywhere below, so this must not appear as a heading.
            "/Valgt/Tomt" to emptyList(),
        )

        val groups = RemoteLibrary(transport).groups(RemoteSection.PICKED)

        assertEquals(listOf("Skatt", "Skatt/2025"), groups.map { it.folderTitle })
    }

    @Test
    fun `the picked walk stops at a depth cap instead of following a tree forever`() = runBlocking {
        // Ten levels, each holding a file. The cap is four levels below /Valgt.
        val pairs = mutableListOf<Pair<String, List<RemoteEntry>>>()
        var path = "/Valgt"
        repeat(10) { i ->
            val child = "$path/n$i"
            pairs += path to listOf(dir(child), file("$path/f$i.pdf"))
            path = child
        }
        val groups = RemoteLibrary(FakeTransport(*pairs.toTypedArray()))
            .groups(RemoteSection.PICKED)

        // /Valgt itself plus four levels down.
        assertEquals(5, groups.size)
    }

    // ---- helpers ----------------------------------------------------------

    private fun dir(path: String) =
        RemoteEntry(path = path, isCollection = true, size = null, lastModified = null)

    private fun file(path: String, size: Long = 1, modified: Long = 0) =
        RemoteEntry(path = path, isCollection = false, size = size, lastModified = modified)

    /** Answers from a fixed map and records what it was asked, so caching is testable. */
    private open class FakeTransport(vararg entries: Pair<String, List<RemoteEntry>>) : Transport {
        private val tree = entries.toMap()
        val calls = mutableListOf<String>()

        open override suspend fun list(path: String): List<RemoteEntry> {
            calls += path
            return tree[path] ?: throw TransportException(FailureKind.NOT_FOUND, path)
        }

        override suspend fun testConnection() = Unit
        override suspend fun ensureCollection(path: String) = Unit
        override suspend fun put(path: String, content: Content) = Unit
        override suspend fun get(path: String): InputStream = throw UnsupportedOperationException()
        override suspend fun delete(path: String) = Unit
    }
}
