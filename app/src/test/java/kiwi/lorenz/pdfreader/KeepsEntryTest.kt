package kiwi.lorenz.pdfreader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepsEntryTest {
    @Test
    fun `keeps a PDF that opened before`() {
        assertTrue(FailureReason.DAMAGED.keepsEntry(openedBefore = true))
    }

    @Test
    fun `removes a damaged PDF that never opened`() {
        assertFalse(FailureReason.DAMAGED.keepsEntry(openedBefore = false))
    }

    @Test
    fun `removes files that are gone, not PDFs or not accessible`() {
        val permanent = FailureReason.entries - FailureReason.DAMAGED
        permanent.forEach { assertFalse(it.name, it.keepsEntry(openedBefore = true)) }
    }
}
