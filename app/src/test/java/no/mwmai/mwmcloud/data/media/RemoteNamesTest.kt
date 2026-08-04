package no.mwmai.mwmcloud.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The grouping half of [RemoteNames.resolve] rides on [LocalFile], whose
 * `android.net.Uri` is not available in a JVM test, so what is pinned here is
 * the naming rule itself — the part that must stay identical between the
 * uploader and the verifier across runs.
 */
class RemoteNamesTest {

    @Test
    fun `suffix is stable for the same uri`() {
        assertEquals(
            RemoteNames.suffixed("IMG_1.jpg", "content://media/external/images/media/42"),
            RemoteNames.suffixed("IMG_1.jpg", "content://media/external/images/media/42"),
        )
    }

    @Test
    fun `different uris give different names`() {
        assertNotEquals(
            RemoteNames.suffixed("IMG_1.jpg", "content://media/external/images/media/42"),
            RemoteNames.suffixed("IMG_1.jpg", "content://media/external/images/media/43"),
        )
    }

    @Test
    fun `extension survives the suffix`() {
        val name = RemoteNames.suffixed("IMG_1.jpg", "content://x/1")
        assertTrue("kept .jpg: $name", name.endsWith(".jpg"))
        assertTrue("stem kept: $name", name.startsWith("IMG_1~"))
    }

    @Test
    fun `a name without an extension gets the suffix at the end`() {
        val name = RemoteNames.suffixed("README", "content://x/1")
        assertTrue(name.startsWith("README~"))
        assertEquals(-1, name.indexOf('.'))
    }
}
