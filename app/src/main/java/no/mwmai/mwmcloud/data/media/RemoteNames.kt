package no.mwmai.mwmcloud.data.media

/**
 * The single place that resolves remote-path collisions.
 *
 * Two different files can share a display name in the same category and month —
 * a camera roll and a messaging app both emitting `IMG_0001.jpg` is enough — and
 * both then map to the same remote path. Deduplicating on remote path alone made
 * the second file vanish: never uploaded, and never reported missing either,
 * because verify deduplicated the same way. The uploader and the verifier both
 * call this, for the same reason they both call [Selection]: two copies of the
 * rule is how they end up disagreeing.
 */
object RemoteNames {

    /**
     * Collapses true duplicates and renames genuine collisions.
     *
     * A file reached both by its category and by a picked folder is the same
     * file (same dedupe key) and is kept once. Different files colliding on one
     * remote path are kept apart: the oldest keeps the plain name, so an
     * existing backup is never renamed when a newcomer collides with it, and
     * the rest get a suffix derived from their content URI, which upload and
     * verify compute identically.
     */
    fun resolve(files: List<LocalFile>): List<LocalFile> =
        files.groupBy { it.remotePath }.values.flatMap { group ->
            val distinct = group.distinctBy { it.dedupeKey }
            if (distinct.size == 1) {
                distinct
            } else {
                distinct
                    .sortedWith(compareBy({ it.modified }, { it.uri.toString() }))
                    .mapIndexed { i, file ->
                        if (i == 0) {
                            file
                        } else {
                            file.copy(
                                displayName = suffixed(file.displayName, file.uri.toString()),
                            )
                        }
                    }
            }
        }

    /**
     * `IMG_1.jpg` -> `IMG_1~6a2b3c4d.jpg`. The tag comes from the content URI,
     * which is stable across scans, so the file keeps the same remote path on
     * every run. Split out on plain strings so the naming rule is testable
     * without an Android `Uri`.
     */
    fun suffixed(displayName: String, uriKey: String): String {
        val tag = "%08x".format(uriKey.hashCode())
        val ext = displayName.substringAfterLast('.', "")
        return if (ext.isEmpty()) {
            "$displayName~$tag"
        } else {
            "${displayName.substringBeforeLast('.')}~$tag.$ext"
        }
    }
}
