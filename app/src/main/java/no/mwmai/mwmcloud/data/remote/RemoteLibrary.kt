package no.mwmai.mwmcloud.data.remote

import java.util.Locale
import no.mwmai.mwmcloud.net.FailureKind
import no.mwmai.mwmcloud.net.RemoteEntry
import no.mwmai.mwmcloud.net.Transport
import no.mwmai.mwmcloud.net.TransportException

/**
 * The four places files land on the box.
 *
 * These are the real remote roots the uploader writes to, not the four backup
 * categories: hand-picked files and folders go to `/Valgt`, keeping the shape the
 * user chose, while media is filed by date. Browsing has to follow what is
 * actually there.
 */
enum class RemoteSection(val root: String, val dateSharded: Boolean) {
    PHOTOS("/Bilder", dateSharded = true),
    MUSIC("/Musikk", dateSharded = true),
    VIDEO("/Video", dateSharded = true),
    PICKED("/Valgt", dateSharded = false),
}

/** What the viewer should do with a file, decided by extension. */
enum class FileKind { IMAGE, VIDEO, AUDIO, DOCUMENT }

/** One file on the box. */
data class RemoteFile(
    val path: String,
    val name: String,
    val size: Long?,
    val lastModified: Long?,
) {
    val kind: FileKind get() = kindOf(name)

    companion object {
        private val IMAGE = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif")
        private val VIDEO = setOf("mp4", "m4v", "mov", "3gp", "mkv", "webm", "avi", "ts")
        private val AUDIO = setOf("mp3", "m4a", "aac", "ogg", "opus", "flac", "wav", "wma")

        fun kindOf(name: String): FileKind {
            val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
            return when (ext) {
                in IMAGE -> FileKind.IMAGE
                in VIDEO -> FileKind.VIDEO
                in AUDIO -> FileKind.AUDIO
                else -> FileKind.DOCUMENT
            }
        }
    }
}

/**
 * One collection on the box, shown as a heading in the viewer.
 *
 * Files are deliberately not carried here. A photo library is thousands of files
 * across dozens of months, and listing every month up front would mean dozens of
 * round trips before the first thumbnail appears. The screen asks for a group's
 * files when that group scrolls into view.
 */
data class RemoteGroup(
    /** Absolute collection path, e.g. `/Bilder/2026/08`. */
    val path: String,
    /**
     * Year and month, when the section is date-sharded. Kept as numbers rather
     * than a formatted heading: the interface ships in two languages, and
     * "August 2026" baked in here would stay Norwegian in the English build.
     */
    val year: Int? = null,
    val month: Int? = null,
    /** Heading for sections that are not date-sharded: the folder path under `/Valgt`. */
    val folderTitle: String? = null,
)

/**
 * Reads what is on the box, for the in-app viewer.
 *
 * Everything here is read-only. There is no delete or rename path, and there is
 * not meant to be one: the viewer exists so a user can see their files without
 * leaving the app, not so the app can start editing the archive.
 *
 * Results are cached in memory for the lifetime of the instance. Re-opening a
 * month the user already looked at is free, and the box is not asked the same
 * question twice in one session.
 */
class RemoteLibrary(private val transport: Transport) {

    private val groupCache = mutableMapOf<RemoteSection, List<RemoteGroup>>()
    private val fileCache = mutableMapOf<String, List<RemoteFile>>()

    /**
     * Collections in [section], newest first.
     *
     * Returns empty rather than throwing when the root does not exist. A section
     * with nothing backed up yet is a normal state, not a failure, and the
     * screen says so in words.
     */
    suspend fun groups(section: RemoteSection): List<RemoteGroup> =
        groupCache.getOrPut(section) {
            if (section.dateSharded) dateGroups(section.root) else pickedGroups(section.root)
        }

    /** Files directly inside [groupPath]. One round trip, cached. */
    suspend fun files(groupPath: String): List<RemoteFile> =
        fileCache.getOrPut(groupPath) {
            listOrEmpty(groupPath)
                .filter { !it.isCollection }
                .map { RemoteFile(it.path, it.name, it.size, it.lastModified) }
                .sortedByDescending { it.lastModified ?: 0L }
        }

    /** Forgets everything read so far, so a pull-to-refresh really re-asks the box. */
    fun invalidate() {
        groupCache.clear()
        fileCache.clear()
    }

    /** `/Bilder` -> years -> months. Two levels, both listed, nothing deeper. */
    private suspend fun dateGroups(root: String): List<RemoteGroup> {
        val years = listOrEmpty(root)
            .filter { it.isCollection && it.name.toIntOrNull() != null }
            .sortedByDescending { it.name.toInt() }

        return buildList {
            for (year in years) {
                val months = listOrEmpty(year.path)
                    .filter { it.isCollection && it.name.toIntOrNull() != null }
                    .sortedByDescending { it.name.toInt() }
                for (month in months) {
                    add(
                        RemoteGroup(
                            path = month.path,
                            year = year.name.toInt(),
                            month = month.name.toInt(),
                        ),
                    )
                }
            }
        }
    }

    /**
     * `/Valgt` mirrors whatever folder shape the user picked, so it is walked
     * rather than assumed.
     *
     * Iterative and depth-capped. The depth of someone's Downloads folder is not
     * ours to bound, and a deep tree must not be able to turn opening a screen
     * into hundreds of requests.
     */
    private suspend fun pickedGroups(root: String): List<RemoteGroup> {
        val out = mutableListOf<RemoteGroup>()
        val queue = ArrayDeque(listOf(root to 0))
        val seen = mutableSetOf<String>()

        while (queue.isNotEmpty() && out.size < MAX_PICKED_GROUPS) {
            val (path, depth) = queue.removeFirst()
            if (!seen.add(path)) continue

            val entries = listOrEmpty(path)
            if (entries.any { !it.isCollection }) {
                out += RemoteGroup(
                    path = path,
                    folderTitle = path.removePrefix(root).trim('/')
                        .ifEmpty { root.trim('/') },
                )
            }
            if (depth < MAX_PICKED_DEPTH) {
                entries.filter { it.isCollection }
                    .sortedBy { it.name }
                    .forEach { queue.addLast(it.path to depth + 1) }
            }
        }
        return out.sortedBy { it.folderTitle }
    }

    /**
     * A missing collection is an answer, not an error: it means nothing of that
     * kind has been backed up yet. Auth and network failures are different, and
     * are left to propagate so the screen can say which one happened.
     */
    private suspend fun listOrEmpty(path: String): List<RemoteEntry> = try {
        transport.list(path)
    } catch (e: TransportException) {
        if (e.kind == FailureKind.NOT_FOUND) emptyList() else throw e
    }

    private companion object {
        const val MAX_PICKED_DEPTH = 4
        const val MAX_PICKED_GROUPS = 200
    }
}
