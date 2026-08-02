package no.mwmai.mwmcloud.data.download

import no.mwmai.mwmcloud.data.remote.FileKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a restored file lands, and under what name.
 *
 * These four functions decide the answer, and every one of them fails in a way
 * that is invisible until a user goes looking for a photo that is not where the
 * app said it would be. MediaStore rejects a wrong base folder outright, and
 * accepts a wrong sub-path silently, so both are worth pinning.
 *
 * The write itself is not tested here: it needs a real ContentResolver, and a
 * fake one would only prove that the fake works.
 */
class DownloaderTest {

    @Test
    fun `each kind goes to the folder its MediaStore collection accepts`() {
        // Not cosmetic. MediaStore.Images rejects an insert whose RELATIVE_PATH
        // is not under Pictures or DCIM, and the exception says nothing useful.
        assertEquals("Pictures", Downloader.baseFolderFor(FileKind.IMAGE))
        assertEquals("Movies", Downloader.baseFolderFor(FileKind.VIDEO))
        assertEquals("Music", Downloader.baseFolderFor(FileKind.AUDIO))
        assertEquals("Download", Downloader.baseFolderFor(FileKind.DOCUMENT))
    }

    @Test
    fun `the box's date shape is kept below the app folder`() {
        assertEquals(
            "Pictures/MWM Cloud/2026/08/",
            Downloader.relativeDirFor(FileKind.IMAGE, "/Bilder/2026/08/IMG_1234.jpg"),
        )
        assertEquals(
            "Movies/MWM Cloud/2025/12/",
            Downloader.relativeDirFor(FileKind.VIDEO, "/Video/2025/12/ferie.mp4"),
        )
    }

    @Test
    fun `the section root is dropped, not translated`() {
        // "Bilder" is the box's word for what the phone calls Pictures. Keeping
        // it would give the user Pictures/MWM Cloud/Bilder/2026/08.
        val dir = Downloader.relativeDirFor(FileKind.IMAGE, "/Bilder/2026/08/a.jpg")
        assertFalse(dir.contains("Bilder"))
    }

    @Test
    fun `a picked folder keeps its own shape`() {
        assertEquals(
            "Download/MWM Cloud/Skatt/2025/",
            Downloader.relativeDirFor(FileKind.DOCUMENT, "/Valgt/Skatt/2025/selvangivelse.pdf"),
        )
    }

    @Test
    fun `a file straight under the section root still gets a folder`() {
        assertEquals(
            "Pictures/MWM Cloud/",
            Downloader.relativeDirFor(FileKind.IMAGE, "/Bilder/løsfoto.jpg"),
        )
    }

    @Test
    fun `the relative path always ends in a slash`() {
        // MediaStore stores RELATIVE_PATH with a trailing slash, so the
        // "is it already there" query has to ask for it with one. Without this,
        // every file would be re-downloaded on every run.
        val paths = listOf(
            Downloader.relativeDirFor(FileKind.IMAGE, "/Bilder/2026/08/a.jpg"),
            Downloader.relativeDirFor(FileKind.IMAGE, "/Bilder/a.jpg"),
            Downloader.relativeDirFor(FileKind.AUDIO, "/Musikk/2020/01/b.mp3"),
        )
        paths.forEach { assertTrue(it, it.endsWith("/")) }
    }

    @Test
    fun `characters the filesystem refuses are replaced, not passed through`() {
        // WebDAV is happy with all of these; ext4 and FAT are not, and the insert
        // fails rather than sanitising for us.
        assertEquals("12_30 notat.txt", Downloader.safeName("12:30 notat.txt"))
        assertEquals("a_b_c.jpg", Downloader.safeName("a/b\\c.jpg"))
        assertEquals("q_.pdf", Downloader.safeName("q?.pdf"))
    }

    @Test
    fun `a trailing dot is trimmed`() {
        // Windows-hostile and, more to the point, silently dropped by some
        // filesystems, which turns "faktura." into "faktura" behind our back.
        assertEquals("faktura", Downloader.safeName("faktura."))
    }

    @Test
    fun `norwegian letters survive`() {
        assertEquals("bålkveld på øya.jpg", Downloader.safeName("bålkveld på øya.jpg"))
    }

    @Test
    fun `a very long name is cut but keeps its extension`() {
        // Cut blindly, the ".jpg" goes and the gallery will not open the file.
        val safe = Downloader.safeName("a".repeat(400) + ".jpg")
        assertTrue(safe.length <= 200)
        assertTrue(safe, safe.endsWith(".jpg"))
    }

    @Test
    fun `a name that is nothing but illegal characters still gets one`() {
        // An empty DISPLAY_NAME fails the insert outright.
        assertTrue(Downloader.safeName("...").isNotEmpty())
    }

    @Test
    fun `a path segment that sanitises to nothing is dropped`() {
        // Otherwise the relative path would contain an empty segment and the
        // insert would fail on a double slash.
        assertEquals("Pictures/MWM Cloud/2026/", Downloader.relativeDirFor(FileKind.IMAGE, "/Bilder/2026///a.jpg"))
    }
}
