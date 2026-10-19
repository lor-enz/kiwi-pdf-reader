package kiwi.lorenz.pdfreader.recent

import android.app.ActivityManager
import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.AtomicFile
import androidx.core.util.readText
import androidx.core.util.writeText
import kiwi.lorenz.pdfreader.recent.TestFilesProvider.Companion.provide
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAppTask
import java.io.File

@RunWith(RobolectricTestRunner::class)
class RecentFilesStoreTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private var store = RecentFilesStore(context)

    private val root = File(context.filesDir, "recent")
    private val copiesDir = File(root, "copies")
    private val thumbnailsDir = File(root, "thumbnails")
    private val filledDir = File(root, "filled")
    private val index = AtomicFile(File(root, "index.json"))

    @Before
    fun setUp() {
        TestFilesProvider.dir = File(context.cacheDir, "provided").apply { mkdirs() }
        Robolectric.setupContentProvider(TestFilesProvider::class.java, TestFilesProvider.AUTHORITY)
    }

    @Test
    fun `entries survive a restart`() = runBlocking {
        val copy = store.importCopy(provide("a.pdf", "content a"), "a.pdf")!!
        val picked = store.addPicked(provide("b.pdf", "content b"), "b.pdf")!!
        store.setPageCount(picked.id, 12)
        store.setLastPage(picked.id, 4)
        store.setPassword(picked.id, "encrypted")
        store.hashPicked(picked.id)
        val before = store.entries.value

        restart()

        assertEquals(before, listed())
        assertEquals(setOf(copy.id, picked.id), before.map { it.id }.toSet())
        assertEquals(4, store.get(picked.id)!!.lastPage)
        assertNotNull(store.get(picked.id)!!.modifiedMillis)
    }

    @Test
    fun `an unreadable entry is dropped on its own`() = runBlocking {
        store.importCopy(provide("a.pdf", "content a"), "a.pdf")!!
        store.importCopy(provide("b.pdf", "content b"), "b.pdf")!!
        val entries = JSONArray(index.readText())
        entries.getJSONObject(0).remove("name")
        index.writeText(entries.toString())

        restart()

        assertEquals(1, listed().size)
    }

    @Test
    fun `the same content from other apps gets one entry, under its latest name`() = runBlocking {
        val first = store.importCopy(provide("a.pdf", "same"), "a.pdf")!!
        val second = store.importCopy(provide("b.pdf", "same"), "b.pdf")!!

        assertEquals(first.id, second.id)
        assertEquals(listOf("b.pdf"), store.entries.value.map { it.name })
        assertEquals(1, copiesDir.listFiles()!!.size)
    }

    @Test
    fun `a file from another app opens through the unchanged picked entry`() = runBlocking {
        val picked = store.addPicked(provide("picked.pdf", "same"), "picked.pdf")!!
        store.hashPicked(picked.id)

        val opened = store.importCopy(provide("attachment.pdf", "same"), "attachment.pdf")!!

        assertEquals(picked.id, opened.id)
        assertFalse(opened.isCopy)
        assertEquals(0, copiesDir.listFiles()!!.size)
    }

    @Test
    fun `hashing a picked file removes its copies`() = runBlocking {
        val copy = store.importCopy(provide("attachment.pdf", "same"), "attachment.pdf")!!
        val picked = store.addPicked(provide("picked.pdf", "same"), "picked.pdf")!!

        store.hashPicked(picked.id)

        assertEquals(listOf(picked.id), store.entries.value.map { it.id })
        assertFalse(copyFile(copy.id).exists())
    }

    @Test
    fun `hashing a picked file keeps a copy that is open in a card`() = runBlocking {
        val copy = store.importCopy(provide("attachment.pdf", "same"), "attachment.pdf")!!
        runningTasks(5)
        store.setOpen(5, copy.id)
        val picked = store.addPicked(provide("picked.pdf", "same"), "picked.pdf")!!

        store.hashPicked(picked.id)

        assertEquals(setOf(copy.id, picked.id), store.entries.value.map { it.id }.toSet())
    }

    @Test
    fun `cleanup spares a document open in a card until the card is gone`() = runBlocking {
        val open = store.importCopy(provide("open.pdf", "open"), "open.pdf")!!
        val other = store.importCopy(provide("other.pdf", "other"), "other.pdf")!!
        runningTasks(5)
        store.setOpen(5, open.id)
        age(open.id, other.id, days = 31)

        store.cleanup()
        assertEquals(listOf(open.id), store.entries.value.map { it.id })
        assertFalse(copyFile(other.id).exists())

        runningTasks()
        store.cleanup()
        assertEquals(emptyList<RecentFile>(), store.entries.value)
        assertFalse(copyFile(open.id).exists())
    }

    @Test
    fun `which document a card shows survives a restart`() = runBlocking {
        val entry = store.importCopy(provide("a.pdf", "a"), "a.pdf")!!
        runningTasks(5, 6)
        store.setOpen(5, entry.id)

        restart()

        assertEquals(entry.id, store.openIn(5)?.id)
        assertEquals(5, store.taskShowing(entry.id, exceptTaskId = 6))
        assertNull(store.taskShowing(entry.id, exceptTaskId = 5))
    }

    @Test
    fun `a card that's gone no longer counts as showing its document`() = runBlocking {
        val entry = store.importCopy(provide("a.pdf", "a"), "a.pdf")!!
        runningTasks(5)
        store.setOpen(5, entry.id)

        runningTasks()

        assertNull(store.taskShowing(entry.id, exceptTaskId = 6))
    }

    @Test
    fun `a removal can be undone until it's final`() = runBlocking {
        val entry = store.importCopy(provide("a.pdf", "a"), "a.pdf")!!

        store.hide(entry.id)
        assertEquals(emptyList<RecentFile>(), store.entries.value)
        assertTrue(copyFile(entry.id).exists())

        store.restore(entry)
        assertEquals(listOf(entry.id), store.entries.value.map { it.id })

        store.hide(entry.id)
        store.discard(entry)
        waitUntil { !copyFile(entry.id).exists() }
        assertEquals(emptyList<RecentFile>(), store.entries.value)
    }

    @Test
    fun `cleanup keeps the files of a hidden entry`() = runBlocking {
        val entry = store.importCopy(provide("a.pdf", "a"), "a.pdf")!!
        store.hide(entry.id)

        store.cleanup()

        assertTrue(copyFile(entry.id).exists())
    }

    @Test
    fun `forgetting the password drops the thumbnail`() = runBlocking {
        val entry = store.importCopy(provide("a.pdf", "a"), "a.pdf")!!
        store.saveThumbnail(entry.id, Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888))
        store.setPassword(entry.id, "encrypted")
        assertTrue(store.get(entry.id)!!.hasThumbnail)

        store.setPassword(entry.id, null)

        val forgotten = store.get(entry.id)!!
        assertNull(forgotten.encryptedPassword)
        assertFalse(forgotten.hasThumbnail)
        assertFalse(File(thumbnailsDir, "${entry.id}.webp").exists())
    }

    @Test
    fun `a password marks the entry as protected until it opens without one`() = runBlocking {
        val entry = store.importCopy(provide("a.pdf", "a"), "a.pdf")!!
        assertFalse(entry.isProtected)

        store.setPassword(entry.id, null)
        assertTrue(store.get(entry.id)!!.isProtected)
        store.setPassword(entry.id, "encrypted")
        store.setPassword(entry.id, null)
        restart()
        assertTrue(store.get(entry.id)!!.isProtected)

        store.setUnprotected(entry.id)
        assertFalse(store.get(entry.id)!!.isProtected)
    }

    @Test
    fun `entries saved before the protected flag are protected if their password is remembered`() = runBlocking {
        val remembered = store.importCopy(provide("a.pdf", "a"), "a.pdf")!!
        val plain = store.importCopy(provide("b.pdf", "b"), "b.pdf")!!
        store.setPassword(remembered.id, "encrypted")
        val entries = JSONArray(index.readText())
        for (i in 0 until entries.length()) entries.getJSONObject(i).remove("isProtected")
        index.writeText(entries.toString())

        restart()

        assertTrue(store.get(remembered.id)!!.isProtected)
        assertFalse(store.get(plain.id)!!.isProtected)
    }

    @Test
    fun `removing a picked file releases its permission`() = runBlocking {
        val uri = provide("picked.pdf", "picked")
        val entry = store.addPicked(uri, "picked.pdf")!!
        assertEquals(listOf(uri), persistedUris())

        store.remove(entry.id)

        assertEquals(emptyList<Uri>(), persistedUris())
    }

    @Test
    fun `cleanup releases permissions no entry uses`() = runBlocking {
        val uri = provide("picked.pdf", "picked")
        val entry = store.addPicked(uri, "picked.pdf")!!
        store.hide(entry.id)
        // The app was killed while the removal could still be undone.
        restart()

        store.cleanup()

        assertEquals(emptyList<Uri>(), persistedUris())
        assertEquals(emptyList<RecentFile>(), listed())
    }

    @Test
    fun `a filled-in version is stored and opened instead of the original`() = runBlocking {
        val entry = store.importCopy(provide("form.pdf", "form"), "form.pdf")!!

        assertTrue(store.saveFilled(entry.id) { it.writeText("filled in") })

        assertEquals("filled in", store.filledVersion(entry.id)!!.readText())
        assertEquals("filled in".length.toLong(), store.get(entry.id)!!.filledBytes)
        restart()
        assertNotNull(store.filledVersion(entry.id))
    }

    @Test
    fun `a failed save stores nothing`() = runBlocking {
        val entry = store.importCopy(provide("form.pdf", "form"), "form.pdf")!!

        assertFalse(store.saveFilled(entry.id) { error("storage full") })

        assertNull(store.filledVersion(entry.id))
        assertEquals(emptyList<File>(), filledDir.listFiles().orEmpty().toList())
    }

    @Test
    fun `a form filled in from a changed original is dropped`() = runBlocking {
        val uri = provide("form.pdf", "form")
        val entry = store.addPicked(uri, "form.pdf")!!
        store.saveFilled(entry.id) { it.writeText("filled in") }

        // A newer download replaced the file.
        File(TestFilesProvider.dir, "form.pdf").apply {
            writeText("new form")
            setLastModified(lastModified() + 1000)
        }

        assertNull(store.filledVersion(entry.id))
        assertFalse(store.get(entry.id)!!.hasFilledVersion)
        assertFalse(File(filledDir, "${entry.id}.pdf").exists())
    }

    @Test
    fun `clearing and removing drop the filled-in version`() = runBlocking {
        val cleared = store.importCopy(provide("a.pdf", "a"), "a.pdf")!!
        val removed = store.importCopy(provide("b.pdf", "b"), "b.pdf")!!
        store.saveFilled(cleared.id) { it.writeText("filled in") }
        store.saveFilled(removed.id) { it.writeText("filled in") }

        store.clearFilled(cleared.id)
        store.remove(removed.id)

        assertNull(store.filledVersion(cleared.id))
        assertEquals(emptyList<File>(), filledDir.listFiles().orEmpty().toList())
    }

    @Test
    fun `an expired filled-in version goes, the picked file stays`() = runBlocking {
        val entry = store.addPicked(provide("form.pdf", "form"), "form.pdf")!!
        store.saveFilled(entry.id) { it.writeText("filled in") }
        age(entry.id, days = 31)

        store.cleanup()

        assertEquals(listOf(entry.id), listed().map { it.id })
        assertNull(store.filledVersion(entry.id))
    }

    @Test
    fun `a form filled in through a copy moves to the picked file`() = runBlocking {
        val copy = store.importCopy(provide("attachment.pdf", "same"), "attachment.pdf")!!
        store.saveFilled(copy.id) { it.writeText("filled in") }
        val picked = store.addPicked(provide("picked.pdf", "same"), "picked.pdf")!!

        store.hashPicked(picked.id)

        assertEquals(listOf(picked.id), listed().map { it.id })
        assertEquals("filled in", store.filledVersion(picked.id)!!.readText())
    }

    /** The entries, after loading them if the store hasn't yet. */
    private suspend fun listed(): List<RecentFile> {
        store.get("")
        return store.entries.value
    }

    /** A new store reading what the old one saved, like after process death. */
    private fun restart() {
        store = RecentFilesStore(context)
    }

    /** Makes entries look last opened [days] ago, then restarts to read the change. */
    private fun age(vararg ids: String, days: Int) {
        val entries = JSONArray(index.readText())
        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            if (entry.getString("id") in ids) {
                entry.put("lastOpenedMillis", System.currentTimeMillis() - days * 24L * 60 * 60 * 1000)
            }
        }
        index.writeText(entries.toString())
        restart()
    }

    /** The app's tasks (cards in the app switcher) as the store sees them. */
    private fun runningTasks(vararg ids: Int) {
        val tasks = ids.map { id ->
            ShadowAppTask.newInstance().also { task ->
                shadowOf(task).setTaskInfo(ActivityManager.RecentTaskInfo().apply { taskId = id })
            }
        }
        shadowOf(context.getSystemService(ActivityManager::class.java)).setAppTasks(tasks)
    }

    private fun persistedUris() = context.contentResolver.persistedUriPermissions.map { it.uri }

    private fun copyFile(id: String) = File(copiesDir, "$id.pdf")

    private fun waitUntil(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 2000
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "Timed out" }
            Thread.sleep(10)
        }
    }
}
