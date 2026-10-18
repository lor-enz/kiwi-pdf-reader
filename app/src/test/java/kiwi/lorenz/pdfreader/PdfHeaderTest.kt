package kiwi.lorenz.pdfreader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfHeaderTest {
    @Test
    fun `finds header at the start`() {
        assertTrue(hasPdfHeader("%PDF-1.7\n%âãÏÓ".toByteArray()))
    }

    @Test
    fun `finds header after junk`() {
        assertTrue(hasPdfHeader(ByteArray(500) { ' '.code.toByte() } + "%PDF-1.4".toByteArray()))
    }

    @Test
    fun `ignores header not within the first 1024 bytes`() {
        assertFalse(hasPdfHeader(ByteArray(1020) + "%PDF-1.4".toByteArray()))
    }

    @Test
    fun `rejects other files`() {
        assertFalse(hasPdfHeader("Hello, this is plain text.".toByteArray()))
        assertFalse(hasPdfHeader("%PD".toByteArray()))
        assertFalse(hasPdfHeader(ByteArray(0)))
    }
}
