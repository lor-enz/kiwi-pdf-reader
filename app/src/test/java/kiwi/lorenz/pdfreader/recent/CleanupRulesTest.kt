package kiwi.lorenz.pdfreader.recent

import org.junit.Assert.assertEquals
import org.junit.Test

class CleanupRulesTest {
    private val now = 1_000L * 24 * 60 * 60 * 1000
    private val mb = 1_000_000L
    private val limits = CleanupLimits(KeepUnused.THREE_MONTHS, StorageLimit.MB_500)
    private val maxAge = limits.keepUnused.millis
    private val budget = limits.storageLimit.bytes

    private fun entry(
        id: String,
        ageMillis: Long = 0,
        isCopy: Boolean = true,
        sizeBytes: Long = mb,
        filledBytes: Long = 0,
    ) = RecentFile(
        id = id,
        uri = "uri-$id",
        name = id,
        isCopy = isCopy,
        sizeBytes = sizeBytes,
        lastOpenedMillis = now - ageMillis,
        filledFromHash = if (filledBytes > 0) "hash" else null,
        filledBytes = filledBytes,
    )

    private fun plan(entries: List<RecentFile>, openIds: Set<String> = emptySet(), limits: CleanupLimits = this.limits) =
        CleanupRules.plan(entries, now, openIds, limits)

    private fun entriesToRemove(entries: List<RecentFile>, now: Long, openIds: Set<String>) =
        CleanupRules.plan(entries, now, openIds, limits).removeEntries

    @Test
    fun `keeps recent copies within budget`() {
        val entries = listOf(entry("a"), entry("b", ageMillis = maxAge))
        assertEquals(emptySet<String>(), entriesToRemove(entries, now, openIds = emptySet()))
    }

    @Test
    fun `removes copies not opened for too long`() {
        val entries = listOf(entry("fresh"), entry("old", ageMillis = maxAge + 1))
        assertEquals(setOf("old"), entriesToRemove(entries, now, openIds = emptySet()))
    }

    @Test
    fun `removes picked files not opened for too long`() {
        val entries = listOf(
            entry("picked", ageMillis = maxAge, isCopy = false),
            entry("old picked", ageMillis = maxAge + 1, isCopy = false),
        )
        assertEquals(setOf("old picked"), entriesToRemove(entries, now, openIds = emptySet()))
    }

    @Test
    fun `follows the chosen limits`() {
        val entries = listOf(
            entry("this week", ageMillis = KeepUnused.WEEK.millis, sizeBytes = 60 * mb),
            entry("last week", ageMillis = KeepUnused.WEEK.millis + 1),
            entry("older", ageMillis = KeepUnused.WEEK.millis + 2, isCopy = false),
            entry("today", sizeBytes = 60 * mb),
        )
        val strict = CleanupLimits(KeepUnused.WEEK, StorageLimit.MB_100)
        assertEquals(setOf("last week", "older", "this week"), plan(entries, limits = strict).removeEntries)
        val generous = CleanupLimits(KeepUnused.YEAR, StorageLimit.GB_2)
        assertEquals(Cleanup(emptySet(), emptySet()), plan(entries, limits = generous))
    }

    @Test
    fun `keeps any number of entries`() {
        val entries = (0 until 100).map { entry("e$it", ageMillis = it.toLong(), isCopy = it % 2 == 0) }
        assertEquals(emptySet<String>(), entriesToRemove(entries, now, openIds = emptySet()))
    }

    @Test
    fun `removes least recently opened copies above the storage limit`() {
        val entries = listOf(
            entry("newest", ageMillis = 1, sizeBytes = 200 * mb),
            entry("middle", ageMillis = 2, sizeBytes = 200 * mb),
            entry("oldest", ageMillis = 3, sizeBytes = 200 * mb),
        )
        assertEquals(setOf("oldest"), entriesToRemove(entries, now, openIds = emptySet()))
    }

    @Test
    fun `picked files do not count towards the storage limit`() {
        val entries = listOf(
            entry("copy", sizeBytes = budget),
            entry("picked", ageMillis = 1, isCopy = false, sizeBytes = 500 * mb),
        )
        assertEquals(emptySet<String>(), entriesToRemove(entries, now, openIds = emptySet()))
    }

    @Test
    fun `never removes open documents, even when expired or too large`() {
        val entries = listOf(
            entry("open", ageMillis = maxAge + 1, sizeBytes = 490 * mb),
            entry("open in another card", ageMillis = maxAge + 2, sizeBytes = 10 * mb),
            entry("other", ageMillis = 1, sizeBytes = 10 * mb),
        )
        assertEquals(setOf("other"), entriesToRemove(entries, now, openIds = setOf("open", "open in another card")))
    }

    @Test
    fun `a picked file only loses its filled-in version above the storage limit`() {
        val entries = listOf(
            entry("new copy", ageMillis = 1, sizeBytes = 450 * mb),
            entry("picked", ageMillis = 2, isCopy = false, filledBytes = 100 * mb),
        )
        assertEquals(Cleanup(emptySet(), setOf("picked")), plan(entries))
    }

    @Test
    fun `filled-in versions count toward the storage limit`() {
        val entries = listOf(
            entry("new copy", ageMillis = 1, sizeBytes = 400 * mb, filledBytes = 50 * mb),
            entry("old picked", ageMillis = 2, isCopy = false, sizeBytes = 900 * mb, filledBytes = 120 * mb),
        )
        // 570 MB stored; dropping the picked file's filled-in version is enough.
        assertEquals(Cleanup(emptySet(), setOf("old picked")), plan(entries))
    }

    @Test
    fun `an open document keeps its filled-in version`() {
        val entries = listOf(entry("open", ageMillis = maxAge + 1, isCopy = false, filledBytes = mb))
        assertEquals(Cleanup(emptySet(), emptySet()), plan(entries, openIds = setOf("open")))
    }
}
