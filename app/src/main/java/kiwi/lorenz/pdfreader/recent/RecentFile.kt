package kiwi.lorenz.pdfreader.recent

/**
 * An entry in the recent files list.
 *
 * Files from the app's own file picker are opened through their persisted [uri]. Files handed
 * over by other apps are copied into private storage ([isCopy]), because their access permission
 * ends once we close them. See CLAUDE.md for the rules.
 */
data class RecentFile(
    val id: String,
    /** The URI to open: a persisted content URI, or the file URI of the private copy. */
    val uri: String,
    val name: String,
    val isCopy: Boolean,
    /**
     * SHA-256 of the content, so the same PDF gets one entry however it was opened. For picked
     * files it's computed after opening and is only valid while [sizeBytes] and [modifiedMillis]
     * match the original, see [isHashCurrent].
     */
    val contentHash: String? = null,
    val sizeBytes: Long = 0,
    /** A picked file's modification time when it was hashed, if its provider reports one. */
    val modifiedMillis: Long? = null,
    val lastOpenedMillis: Long,
    /** 0 until the document has been opened successfully once. */
    val pageCount: Int = 0,
    val lastPage: Int = 0,
    val hasThumbnail: Boolean = false,
    /** Set when the user chose to remember the password; see [PasswordCipher]. */
    val encryptedPassword: String? = null,
    /**
     * Set while a filled-in version of a form is stored: the [contentHash] of the original it was
     * filled in from, so it's dropped once the original changes.
     */
    val filledFromHash: String? = null,
    val filledBytes: Long = 0,
) {
    val hasFilledVersion: Boolean get() = filledFromHash != null

    /** Whether [contentHash] still describes a picked file that now has [size] and [modified]. */
    fun isHashCurrent(size: Long?, modified: Long?): Boolean =
        contentHash != null && size != null && size == sizeBytes && modified == modifiedMillis

    /** What the app stores for this entry itself: its copy and its filled-in version. */
    val storedBytes: Long get() = (if (isCopy) sizeBytes else 0) + filledBytes
}

/** What [CleanupRules.plan] decided: entries to remove, and filled-in versions to drop. */
data class Cleanup(val removeEntries: Set<String>, val dropFilled: Set<String>)

/**
 * The limits for what the app stores itself: copies of files from other apps and filled-in
 * forms. Both go after 30 days without being opened, and above 250 MB in total, least recently
 * opened first. A copy goes with its entry; a picked file only loses its filled-in version.
 */
object CleanupRules {
    const val STORED_MAX_AGE_MILLIS = 30L * 24 * 60 * 60 * 1000
    const val STORED_MAX_TOTAL_BYTES = 250L * 1024 * 1024
    const val MAX_ENTRIES = 20

    /** [openIds] are the documents open in a viewer, which are left alone. */
    fun plan(entries: List<RecentFile>, nowMillis: Long, openIds: Set<String>): Cleanup {
        val removed = mutableSetOf<String>()
        val dropped = mutableSetOf<String>()
        val newestFirst = entries.sortedByDescending { it.lastOpenedMillis }
        val removable = newestFirst.filter { it.id !in openIds }

        fun release(entry: RecentFile) {
            if (entry.isCopy) removed += entry.id else dropped += entry.id
        }
        fun isStored(entry: RecentFile) = entry.id !in removed && entry.id !in dropped && entry.storedBytes > 0

        // Not opened for 30 days.
        removable
            .filter { isStored(it) && nowMillis - it.lastOpenedMillis > STORED_MAX_AGE_MILLIS }
            .forEach(::release)

        // Above the size budget, least recently opened first.
        var storedBytes = newestFirst.filter(::isStored).sumOf { it.storedBytes }
        for (entry in removable.asReversed()) {
            if (storedBytes <= STORED_MAX_TOTAL_BYTES) break
            if (isStored(entry)) {
                storedBytes -= if (entry.isCopy) entry.storedBytes else entry.filledBytes
                release(entry)
            }
        }

        // Keep the list short: drop the oldest entries beyond the limit.
        newestFirst.filter { it.id !in removed }
            .drop(MAX_ENTRIES)
            .filter { it.id !in openIds }
            .mapTo(removed) { it.id }

        return Cleanup(removed, dropped - removed)
    }
}
