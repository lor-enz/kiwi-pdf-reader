package kiwi.lorenz.pdfreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FailureDiagnosisTest {
    private val pdf = "%PDF-1.7\n".toByteArray()

    @Test
    fun `a PDF that opened before failed for now`() {
        assertEquals(FailureReason.TEMPORARY, diagnoseHead(pdf, openedBefore = true))
        assertEquals(FailureReason.TEMPORARY, diagnoseHead(ByteArray(0), openedBefore = true))
    }

    @Test
    fun `a PDF that never opened is damaged`() {
        assertEquals(FailureReason.DAMAGED, diagnoseHead(pdf, openedBefore = false))
        assertEquals(FailureReason.DAMAGED, diagnoseHead(ByteArray(0), openedBefore = false))
    }

    @Test
    fun `other files aren't PDFs, also if they opened before`() {
        assertEquals(FailureReason.NOT_PDF, diagnoseHead("<html>".toByteArray(), openedBefore = true))
    }

    @Test
    fun `no file is unavailable`() {
        assertEquals(FailureReason.UNAVAILABLE, diagnoseHead(null, openedBefore = true))
    }

    @Test
    fun `keeps the entry only after a passing failure`() {
        assertTrue(FailureReason.TEMPORARY.keepsEntry)
        (FailureReason.entries - FailureReason.TEMPORARY).forEach { assertFalse(it.name, it.keepsEntry) }
    }
}
