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
    /** Needs a password to open. Known once it has opened, or when its password is remembered. */
    val isProtected: Boolean = false,
    /** Set when the user chose to remember the password; see [PasswordCipher]. */
    val encryptedPassword: String? = null,
    /**
     * Set while a filled-in version of a form is stored: the [contentHash] of the original it was
     * filled in from, so it's dropped once the original changes.
     */
    val filledFromHash: String? = null,
    val filledBytes: Long = 0,
    /**
     * The title in the document's metadata, shown above the file name. Kept for a protected
     * document only while its password is remembered, like the thumbnail.
     */
    val title: String? = null,
    /** False for entries saved before titles were stored, until theirs is read. */
    val isTitleRead: Boolean = true,
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

/** How long an entry stays in the list without being opened. Chosen in Settings. */
enum class KeepUnused(val millis: Long) {
    WEEK(7 * DAY_MILLIS),
    MONTH(30 * DAY_MILLIS),
    THREE_MONTHS(91 * DAY_MILLIS),
    SIX_MONTHS(182 * DAY_MILLIS),
    YEAR(365 * DAY_MILLIS),
}

/**
 * How much the app may store itself: copies of files from other apps and filled-in forms.
 * Decimal, like the sizes Android shows. Chosen in Settings.
 */
enum class StorageLimit(val bytes: Long) {
    MB_100(100_000_000),
    MB_500(500_000_000),
    GB_2(2_000_000_000),
}

data class CleanupLimits(
    val keepUnused: KeepUnused = KeepUnused.THREE_MONTHS,
    val storageLimit: StorageLimit = StorageLimit.MB_500,
)

/**
 * Entries go once they haven't been opened for [CleanupLimits.keepUnused]. Above the
 * [CleanupLimits.storageLimit], what the app stores goes too, least recently opened first: a copy
 * with its entry, a picked file only loses its filled-in version.
 */
object CleanupRules {
    /** [openIds] are the documents open in a viewer, which are left alone. */
    fun plan(entries: List<RecentFile>, nowMillis: Long, openIds: Set<String>, limits: CleanupLimits): Cleanup {
        val newestFirst = entries.sortedByDescending { it.lastOpenedMillis }
        val removable = newestFirst.filter { it.id !in openIds }

        // Not opened for too long.
        val removed = removable
            .filter { nowMillis - it.lastOpenedMillis > limits.keepUnused.millis }
            .mapTo(mutableSetOf()) { it.id }

        // Above the storage limit, least recently opened first.
        val dropped = mutableSetOf<String>()
        fun isStored(entry: RecentFile) = entry.id !in removed && entry.id !in dropped && entry.storedBytes > 0
        var storedBytes = newestFirst.filter(::isStored).sumOf { it.storedBytes }
        for (entry in removable.asReversed()) {
            if (storedBytes <= limits.storageLimit.bytes) break
            if (isStored(entry)) {
                if (entry.isCopy) {
                    storedBytes -= entry.storedBytes
                    removed += entry.id
                } else {
                    storedBytes -= entry.filledBytes
                    dropped += entry.id
                }
            }
        }

        return Cleanup(removed, dropped)
    }
}

private const val DAY_MILLIS = 24L * 60 * 60 * 1000
