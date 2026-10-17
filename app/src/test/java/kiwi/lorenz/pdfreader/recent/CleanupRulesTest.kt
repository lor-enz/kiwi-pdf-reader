package kiwi.lorenz.pdfreader.recent

import kiwi.lorenz.pdfreader.recent.CleanupRules.COPIES_MAX_TOTAL_BYTES
import kiwi.lorenz.pdfreader.recent.CleanupRules.COPY_MAX_AGE_MILLIS
import kiwi.lorenz.pdfreader.recent.CleanupRules.MAX_ENTRIES
import kiwi.lorenz.pdfreader.recent.CleanupRules.entriesToRemove
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
    ) = RecentFile(
        id = id,
        uri = "uri-$id",
        name = id,
        isCopy = isCopy,
        sizeBytes = sizeBytes,
        lastOpenedMillis = now - ageMillis,
    )

    @Test
    fun `keeps recent copies within budget`() {
        val entries = listOf(entry("a"), entry("b", ageMillis = COPY_MAX_AGE_MILLIS))
        assertEquals(emptySet<String>(), entriesToRemove(entries, now, openId = null))
    }

    @Test
    fun `removes copies not opened for 30 days`() {
        val entries = listOf(entry("fresh"), entry("old", ageMillis = COPY_MAX_AGE_MILLIS + 1))
        assertEquals(setOf("old"), entriesToRemove(entries, now, openId = null))
    }

    @Test
    fun `old picked files are not removed by age`() {
        val entries = listOf(entry("picked", ageMillis = 10 * COPY_MAX_AGE_MILLIS, isCopy = false))
        assertEquals(emptySet<String>(), entriesToRemove(entries, now, openId = null))
    }

    @Test
    fun `removes least recently opened copies above the size budget`() {
        val entries = listOf(
            entry("newest", ageMillis = 1, sizeBytes = 100 * mb),
            entry("middle", ageMillis = 2, sizeBytes = 100 * mb),
            entry("oldest", ageMillis = 3, sizeBytes = 100 * mb),
        )
        assertEquals(setOf("oldest"), entriesToRemove(entries, now, openId = null))
    }

    @Test
    fun `picked files do not count towards the size budget`() {
        val entries = listOf(
            entry("copy", sizeBytes = COPIES_MAX_TOTAL_BYTES),
            entry("picked", ageMillis = 1, isCopy = false, sizeBytes = 500 * mb),
        )
        assertEquals(emptySet<String>(), entriesToRemove(entries, now, openId = null))
    }

    @Test
    fun `never removes the open document, even when expired or too large`() {
        val entries = listOf(
            entry("open", ageMillis = COPY_MAX_AGE_MILLIS + 1, sizeBytes = 400 * mb),
            entry("other", ageMillis = 1, sizeBytes = 10 * mb),
        )
        assertEquals(setOf("other"), entriesToRemove(entries, now, openId = "open"))
    }

    @Test
    fun `limits the list to the newest entries`() {
        val entries = (0 until MAX_ENTRIES + 3).map { entry("e$it", ageMillis = it.toLong(), isCopy = false) }
        val expected = (MAX_ENTRIES until MAX_ENTRIES + 3).map { "e$it" }.toSet()
        assertEquals(expected, entriesToRemove(entries, now, openId = null))
    }
}
