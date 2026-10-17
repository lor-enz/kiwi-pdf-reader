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
    /** SHA-256 of a copy's content, so sharing the same PDF twice reuses one copy. */
    val contentHash: String? = null,
    val sizeBytes: Long = 0,
    val lastOpenedMillis: Long,
    /** 0 until the document has been opened successfully once. */
    val pageCount: Int = 0,
    val lastPage: Int = 0,
    val hasThumbnail: Boolean = false,
)

object CleanupRules {
    const val COPY_MAX_AGE_MILLIS = 30L * 24 * 60 * 60 * 1000
    const val COPIES_MAX_TOTAL_BYTES = 250L * 1024 * 1024
    const val MAX_ENTRIES = 20

    /**
     * Returns the ids of the entries to remove. [openId] is the currently open document, which
     * is never removed.
     */
    fun entriesToRemove(entries: List<RecentFile>, nowMillis: Long, openId: String?): Set<String> {
        val removed = mutableSetOf<String>()
        val newestFirst = entries.sortedByDescending { it.lastOpenedMillis }
        val removable = newestFirst.filter { it.id != openId }

        // Copies not opened for 30 days.
        removable
            .filter { it.isCopy && nowMillis - it.lastOpenedMillis > COPY_MAX_AGE_MILLIS }
            .mapTo(removed) { it.id }

        // Copies above the size budget, least recently opened first.
        var copiesBytes = newestFirst.filter { it.isCopy && it.id !in removed }.sumOf { it.sizeBytes }
        for (entry in removable.asReversed()) {
            if (copiesBytes <= COPIES_MAX_TOTAL_BYTES) break
            if (entry.isCopy && entry.id !in removed) {
                removed += entry.id
                copiesBytes -= entry.sizeBytes
            }
        }

        // Keep the list short: drop the oldest entries beyond the limit.
        val kept = newestFirst.filter { it.id !in removed }
        kept.drop(MAX_ENTRIES)
            .filter { it.id != openId }
            .mapTo(removed) { it.id }

        return removed
    }
}
