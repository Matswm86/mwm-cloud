package no.mwmai.mwmcloud.data.media

/**
 * How much of a category is backed up.
 *
 * Two modes, because two different people are asking two different questions.
 * "Keep my photos safe" wants everything, forever, including the ones taken
 * tomorrow. "Just these four videos" wants exactly those four and nothing that
 * appears later. Guessing which one is meant is how a backup either misses new
 * photos or quietly balloons to 40 GB the user did not ask for.
 */
enum class CategoryMode {
    /**
     * Everything in the category, minus files the user ticked off. Exclusions,
     * not inclusions, so a photo taken tomorrow is covered automatically.
     */
    ALL,

    /**
     * Only the files the user picked. Nothing new is added on its own. This is
     * the mode for "one folder" or "just these few".
     */
    ONLY_PICKED,
    ;

    companion object {
        fun parse(raw: String?): CategoryMode =
            entries.firstOrNull { it.name == raw } ?: ALL
    }
}

/**
 * The single place that decides whether a file on the phone is backed up.
 *
 * The uploader, the verifier and every count in the interface all call this. When
 * this lived in three places, the screen could say "412 chosen" while the
 * uploader sent 8 000, and only one of them was right.
 */
object Selection {

    /**
     * The rule itself, over the stored key rather than a file.
     *
     * Split out so it can be tested on a plain JVM: [LocalFile] carries an
     * `android.net.Uri`, which is not mocked in unit tests, and the rule is the
     * part worth pinning.
     */
    fun isSelected(
        key: String,
        mode: CategoryMode,
        excluded: Set<String>,
        included: Set<String>,
    ): Boolean = when (mode) {
        CategoryMode.ALL -> key !in excluded
        CategoryMode.ONLY_PICKED -> key in included
    }

    fun isSelected(
        file: LocalFile,
        mode: CategoryMode,
        excluded: Set<String>,
        included: Set<String>,
    ): Boolean = isSelected(file.uri.toString(), mode, excluded, included)

    /** Convenience for a whole list, keeping the same rule. */
    fun filter(
        files: List<LocalFile>,
        mode: CategoryMode,
        excluded: Set<String>,
        included: Set<String>,
    ): List<LocalFile> = files.filter { isSelected(it, mode, excluded, included) }
}
