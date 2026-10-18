package kiwi.lorenz.pdfreader.recent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HashCurrentTest {
    private val hashed = RecentFile(
        id = "a",
        uri = "content://a",
        name = "a.pdf",
        isCopy = false,
        contentHash = "abc",
        sizeBytes = 100,
        modifiedMillis = 5,
        lastOpenedMillis = 0,
    )

    @Test
    fun `current while size and modification time match`() {
        assertTrue(hashed.isHashCurrent(size = 100, modified = 5))
    }

    @Test
    fun `stale when the file changed`() {
        assertFalse(hashed.isHashCurrent(size = 101, modified = 5))
        assertFalse(hashed.isHashCurrent(size = 100, modified = 6))
    }

    @Test
    fun `relies on the size when no modification time is reported`() {
        val withoutTime = hashed.copy(modifiedMillis = null)
        assertTrue(withoutTime.isHashCurrent(size = 100, modified = null))
        assertFalse(withoutTime.isHashCurrent(size = 100, modified = 5))
    }

    @Test
    fun `never current without a hash or a size`() {
        assertFalse(hashed.copy(contentHash = null).isHashCurrent(size = 100, modified = 5))
        assertFalse(hashed.isHashCurrent(size = null, modified = 5))
    }
}
