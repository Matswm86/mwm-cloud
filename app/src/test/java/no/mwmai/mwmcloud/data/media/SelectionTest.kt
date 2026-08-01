package no.mwmai.mwmcloud.data.media

import java.util.concurrent.TimeUnit
import no.mwmai.mwmcloud.settings.BackupSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that decides what gets backed up.
 *
 * Worth its own tests because three callers depend on it and they must not
 * disagree: the uploader sends what it says is selected, the verify panel checks
 * that same set, and the folder card reports its size. When those drift, the app
 * either reports a good backup as broken or quietly stops sending a category.
 */
class SelectionTest {

    private val a = "content://media/external/images/media/1"
    private val b = "content://media/external/images/media/2"
    private val c = "content://media/external/images/media/3"

    @Test
    fun `everything mode keeps a file nobody excluded`() {
        assertTrue(Selection.isSelected(a, CategoryMode.ALL, excluded = setOf(b), included = emptySet()))
    }

    @Test
    fun `everything mode drops only what was ticked off`() {
        assertFalse(Selection.isSelected(b, CategoryMode.ALL, excluded = setOf(b), included = emptySet()))
    }

    /**
     * The whole point of storing exclusions rather than inclusions: a photo taken
     * after the user last opened the picker must be backed up without being asked
     * about.
     */
    @Test
    fun `everything mode covers a file the user has never seen`() {
        val brandNew = "content://media/external/images/media/999"
        assertTrue(
            Selection.isSelected(brandNew, CategoryMode.ALL, excluded = setOf(a, b), included = setOf(a)),
        )
    }

    @Test
    fun `only-picked mode takes exactly what was picked`() {
        assertTrue(Selection.isSelected(a, CategoryMode.ONLY_PICKED, emptySet(), setOf(a, b)))
        assertFalse(Selection.isSelected(c, CategoryMode.ONLY_PICKED, emptySet(), setOf(a, b)))
    }

    /**
     * The mirror of the case above, and the reason the two modes exist. Someone
     * who asked for four videos must not silently acquire a fifth next month.
     */
    @Test
    fun `only-picked mode does not adopt new files`() {
        val brandNew = "content://media/external/video/media/999"
        assertFalse(Selection.isSelected(brandNew, CategoryMode.ONLY_PICKED, emptySet(), setOf(a)))
    }

    @Test
    fun `only-picked mode ignores the exclusion list entirely`() {
        // A file can be in both lists after a mode switch. Inclusions win in this
        // mode, or a user who picked a file would find it silently dropped.
        assertTrue(Selection.isSelected(a, CategoryMode.ONLY_PICKED, excluded = setOf(a), included = setOf(a)))
    }

    @Test
    fun `an unknown stored mode falls back to everything, never to nothing`() {
        // A settings file written by a newer build, or a corrupted value, must not
        // silently turn a backup off.
        assertEquals(CategoryMode.ALL, CategoryMode.parse(null))
        assertEquals(CategoryMode.ALL, CategoryMode.parse(""))
        assertEquals(CategoryMode.ALL, CategoryMode.parse("SOMETHING_ELSE"))
        assertEquals(CategoryMode.ONLY_PICKED, CategoryMode.parse("ONLY_PICKED"))
    }

    @Test
    fun `an unknown stored schedule falls back to off, never to a surprise upload`() {
        assertEquals(BackupSchedule.OFF, BackupSchedule.parse(null))
        assertEquals(BackupSchedule.OFF, BackupSchedule.parse("HOURLY"))
        assertEquals(BackupSchedule.WEEKLY, BackupSchedule.parse("WEEKLY"))
    }

    /**
     * WorkManager refuses a periodic interval under 15 minutes and silently
     * rounds it up. Every real option here has to be well clear of that floor, or
     * the schedule the user picked is not the schedule they get.
     */
    @Test
    fun `every schedule is above WorkManagers periodic floor`() {
        val floorMinutes = 15L
        BackupSchedule.entries.filter { it != BackupSchedule.OFF }.forEach { s ->
            val minutes = TimeUnit.HOURS.toMinutes(s.hours)
            assertTrue("${s.name} is $minutes min", minutes > floorMinutes)
        }
        assertEquals(0L, BackupSchedule.OFF.hours)
    }
}
