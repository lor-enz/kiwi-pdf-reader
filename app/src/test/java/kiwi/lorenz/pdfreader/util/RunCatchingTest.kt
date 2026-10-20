package kiwi.lorenz.pdfreader.util

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RunCatchingTest {
    @Test
    fun `returns the result`() {
        assertEquals(42, runCatchingCancellable { 42 }.getOrNull())
    }

    @Test
    fun `catches exceptions`() {
        val result = runCatchingCancellable { error("broken") }
        assertTrue(result.exceptionOrNull() is IllegalStateException)
    }

    @Test(expected = CancellationException::class)
    fun `lets cancellation through`() {
        runCatchingCancellable { throw CancellationException("cancelled") }
    }

    @Test(expected = OutOfMemoryError::class)
    fun `lets errors through`() {
        runCatchingCancellable { throw OutOfMemoryError() }
    }
}
