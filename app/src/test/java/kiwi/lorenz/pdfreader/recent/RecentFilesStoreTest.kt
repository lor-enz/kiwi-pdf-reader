package kiwi.lorenz.pdfreader.recent

import android.app.ActivityManager
import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.AtomicFile
import androidx.core.util.readText
import androidx.core.util.writeText
import kiwi.lorenz.pdfreader.recent.TestFilesProvider.Companion.provide
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
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
    fun `forgetting the password drops the title, others keep theirs`() = runBlocking {
        val remembered = store.importCopy(provide("a.pdf", "a"), "a.pdf")!!
        val plain = store.importCopy(provide("b.pdf", "b"), "b.pdf")!!
        store.setPassword(remembered.id, "encrypted")
        store.setTitle(remembered.id, "Statement")
        store.setTitle(plain.id, "Invoice")

        store.setPassword(remembered.id, null)

        assertNull(store.get(remembered.id)!!.title)
        assertEquals("Invoice", store.get(plain.id)!!.title)
    }

    @Test
    fun `entries saved before titles get theirs read once, protected ones only with their password`() = runBlocking {
        val titled = store.importCopy(provide("a.pdf", pdfWithTitle("Electricity bill")), "a.pdf")!!
        val protected = store.importCopy(provide("b.pdf", pdfWithTitle("Secret")), "b.pdf")!!
        val untitled = store.importCopy(provide("c.pdf", "not really a PDF"), "c.pdf")!!
        store.setPassword(protected.id, null)
        val entries = JSONArray(index.readText())
        for (i in 0 until entries.length()) entries.getJSONObject(i).remove("isTitleRead")
        index.writeText(entries.toString())
        restart()
        assertFalse(store.get(titled.id)!!.isTitleRead)

        store.readMissingTitles()

        assertEquals("Electricity bill", store.get(titled.id)!!.title)
        assertNull(store.get(protected.id)!!.title)
        assertNull(store.get(untitled.id)!!.title)
        assertTrue(listed().all { it.isTitleRead })
    }

    @Test
    fun `forgetting all passwords drops their thumbnails and keeps the other ones`() = runBlocking {
        val remembered = store.importCopy(provide("a.pdf", "a"), "a.pdf")!!
        val plain = store.importCopy(provide("b.pdf", "b"), "b.pdf")!!
        val hidden = store.importCopy(provide("c.pdf", "c"), "c.pdf")!!
        for (entry in listOf(remembered, plain, hidden)) {
            store.saveThumbnail(entry.id, Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888))
        }
        store.setPassword(remembered.id, "encrypted")
        store.setPassword(hidden.id, "encrypted")
        store.hide(hidden.id)

        store.forgetAllPasswords()
        restart()

        val forgotten = store.get(remembered.id)!!
        assertNull(forgotten.encryptedPassword)
        assertTrue(forgotten.isProtected)
        assertFalse(forgotten.hasThumbnail)
        assertFalse(File(thumbnailsDir, "${remembered.id}.webp").exists())
        assertTrue(store.get(plain.id)!!.hasThumbnail)
        assertTrue(File(thumbnailsDir, "${plain.id}.webp").exists())
        assertFalse(File(thumbnailsDir, "${hidden.id}.webp").exists())
    }

    @Test
    fun `undoing a removal after forgetting all passwords brings no password back`() = runBlocking {
        val entry = store.importCopy(provide("a.pdf", "a"), "a.pdf")!!
        store.setPassword(entry.id, "encrypted")
        val hidden = store.hide(entry.id)!!

        store.forgetAllPasswords()
        store.restore(hidden)

        assertNull(store.get(entry.id)!!.encryptedPassword)
    }

    @Test
    fun `clearing removes every entry with its files except open documents`() = runBlocking {
        val open = store.importCopy(provide("open.pdf", "open"), "open.pdf")!!
        val copy = store.importCopy(provide("copy.pdf", "copy"), "copy.pdf")!!
        val pickedUri = provide("picked.pdf", "picked")
        val picked = store.addPicked(pickedUri, "picked.pdf")!!
        store.saveThumbnail(copy.id, Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888))
        store.saveFilled(picked.id) { it.writeText("filled") }
        runningTasks(5)
        store.setOpen(5, open.id)

        assertEquals(1, store.clear())

        assertEquals(listOf(open.id), store.entries.value.map { it.id })
        assertTrue(copyFile(open.id).exists())
        assertFalse(copyFile(copy.id).exists())
        assertFalse(File(thumbnailsDir, "${copy.id}.webp").exists())
        assertFalse(File(filledDir, "${picked.id}.pdf").exists())
        assertEquals(emptyList<Uri>(), persistedUris())
        restart()
        assertEquals(listOf(open.id), listed().map { it.id })
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
    @Test
    fun `loaded entries wait for the list to be read`() = runBlocking {
        val picked = store.addPicked(provide("a.pdf", "content a"), "a.pdf")!!
        restart()

        // Launcher shortcuts mustn't take the empty list before loading for an emptied one.
        assertNull(withTimeoutOrNull(100) { store.loadedEntries.first() })
        listed()
        assertEquals(listOf(picked.id), store.loadedEntries.first().map { it.id })
    }

    private suspend fun listed(): List<RecentFile> {
        store.get("")
        return store.entries.value
    }

    /** Just enough of a PDF for its title to be read. */
    private fun pdfWithTitle(title: String) = """
        %PDF-1.4
        1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj
        2 0 obj << /Type /Pages /Kids [] /Count 0 >> endobj
        3 0 obj << /Title ($title) >> endobj
        trailer << /Root 1 0 R /Info 3 0 R >>
        %%EOF
    """.trimIndent()

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
