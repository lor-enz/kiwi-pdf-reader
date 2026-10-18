package kiwi.lorenz.pdfreader.recent

import kiwi.lorenz.pdfreader.recent.CleanupRules.STORED_MAX_TOTAL_BYTES
import kiwi.lorenz.pdfreader.recent.CleanupRules.STORED_MAX_AGE_MILLIS
import kiwi.lorenz.pdfreader.recent.CleanupRules.MAX_ENTRIES
import org.junit.Assert.assertEquals
import org.junit.Test

class CleanupRulesTest {
    private val now = 1_000L * 24 * 60 * 60 * 1000
    private val mb = 1024L * 1024

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

    private fun entriesToRemove(entries: List<RecentFile>, now: Long, openIds: Set<String>) =
        CleanupRules.plan(entries, now, openIds).removeEntries

    @Test
    fun `keeps recent copies within budget`() {
        val entries = listOf(entry("a"), entry("b", ageMillis = STORED_MAX_AGE_MILLIS))
        assertEquals(emptySet<String>(), entriesToRemove(entries, now, openIds = emptySet()))
    }

    @Test
    fun `removes copies not opened for 30 days`() {
        val entries = listOf(entry("fresh"), entry("old", ageMillis = STORED_MAX_AGE_MILLIS + 1))
        assertEquals(setOf("old"), entriesToRemove(entries, now, openIds = emptySet()))
    }

    @Test
    fun `old picked files are not removed by age`() {
        val entries = listOf(entry("picked", ageMillis = 10 * STORED_MAX_AGE_MILLIS, isCopy = false))
        assertEquals(emptySet<String>(), entriesToRemove(entries, now, openIds = emptySet()))
    }

    @Test
    fun `removes least recently opened copies above the size budget`() {
        val entries = listOf(
            entry("newest", ageMillis = 1, sizeBytes = 100 * mb),
            entry("middle", ageMillis = 2, sizeBytes = 100 * mb),
            entry("oldest", ageMillis = 3, sizeBytes = 100 * mb),
        )
        assertEquals(setOf("oldest"), entriesToRemove(entries, now, openIds = emptySet()))
    }

    @Test
    fun `picked files do not count towards the size budget`() {
        val entries = listOf(
            entry("copy", sizeBytes = STORED_MAX_TOTAL_BYTES),
            entry("picked", ageMillis = 1, isCopy = false, sizeBytes = 500 * mb),
        )
        assertEquals(emptySet<String>(), entriesToRemove(entries, now, openIds = emptySet()))
    }

    @Test
    fun `never removes open documents, even when expired or too large`() {
        val entries = listOf(
            entry("open", ageMillis = STORED_MAX_AGE_MILLIS + 1, sizeBytes = 400 * mb),
            entry("open in another card", ageMillis = STORED_MAX_AGE_MILLIS + 2, sizeBytes = 10 * mb),
            entry("other", ageMillis = 1, sizeBytes = 10 * mb),
        )
        assertEquals(setOf("other"), entriesToRemove(entries, now, openIds = setOf("open", "open in another card")))
    }

    @Test
    fun `limits the list to the newest entries`() {
        val entries = (0 until MAX_ENTRIES + 3).map { entry("e$it", ageMillis = it.toLong(), isCopy = false) }
        val expected = (MAX_ENTRIES until MAX_ENTRIES + 3).map { "e$it" }.toSet()
        assertEquals(expected, entriesToRemove(entries, now, openIds = emptySet()))
    }

    @Test
    fun `a picked file only loses its filled-in version when it expires`() {
        val entries = listOf(
            entry("picked", ageMillis = STORED_MAX_AGE_MILLIS + 1, isCopy = false, filledBytes = mb),
            entry("picked, recent", ageMillis = 1, isCopy = false, filledBytes = mb),
        )
        assertEquals(Cleanup(emptySet(), setOf("picked")), CleanupRules.plan(entries, now, emptySet()))
    }

    @Test
    fun `filled-in versions count toward the budget`() {
        val entries = listOf(
            entry("new copy", ageMillis = 1, sizeBytes = 100 * mb, filledBytes = 50 * mb),
            entry("old picked", ageMillis = 2, isCopy = false, sizeBytes = 900 * mb, filledBytes = 120 * mb),
        )
        // 270 MB stored; dropping the picked file's filled-in version is enough.
        assertEquals(Cleanup(emptySet(), setOf("old picked")), CleanupRules.plan(entries, now, emptySet()))
    }

    @Test
    fun `an open document keeps its filled-in version`() {
        val entries = listOf(entry("open", ageMillis = STORED_MAX_AGE_MILLIS + 1, isCopy = false, filledBytes = mb))
        assertEquals(Cleanup(emptySet(), emptySet()), CleanupRules.plan(entries, now, setOf("open")))
    }
}
