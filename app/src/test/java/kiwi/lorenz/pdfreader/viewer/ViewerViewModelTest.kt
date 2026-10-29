package kiwi.lorenz.pdfreader.viewer

import android.app.ActivityManager
import android.app.Application
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.lifecycle.SavedStateHandle
import androidx.pdf.PdfDocument
import androidx.pdf.PdfLoader
import androidx.pdf.PdfPasswordException
import androidx.pdf.RenderParams
import kiwi.lorenz.pdfreader.pdf.ByteArraySource
import kiwi.lorenz.pdfreader.pdf.OutlineItem
import kiwi.lorenz.pdfreader.pdf.PdfMetadataReader
import kiwi.lorenz.pdfreader.recent.PasswordEncryption
import kiwi.lorenz.pdfreader.recent.RecentFile
import kiwi.lorenz.pdfreader.recent.RecentFilesStore
import kiwi.lorenz.pdfreader.recent.TestFilesProvider
import kiwi.lorenz.pdfreader.recent.TestFilesProvider.Companion.provide
import kotlinx.coroutines.runBlocking
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
import org.robolectric.annotation.GraphicsMode
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAppTask
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy
import java.time.Duration
import javax.imageio.ImageIO

/**
 * How the viewer opens documents, with a fake PDF library ([FakeLoader]) and the real store.
 * Documents are recent entries made from [TestFilesProvider] files; what they contain only
 * matters when opening fails, as that's diagnosed from the file's first bytes.
 */
@RunWith(RobolectricTestRunner::class)
class ViewerViewModelTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val store = RecentFilesStore(context)
    private val loader = FakeLoader()
    private val savedState = SavedStateHandle()
    private val viewModel = viewModel(savedState)

    @Before
    fun setUp() {
        TestFilesProvider.dir = File(context.cacheDir, "provided").apply { mkdirs() }
        Robolectric.setupContentProvider(TestFilesProvider::class.java, TestFilesProvider.AUTHORITY)
        // The viewer's own card; the store forgets what cards that are gone showed.
        runningTasks(TASK)
    }

    @Test
    fun `a recent entry opens at its last page`() {
        val entry = copy("manual.pdf")
        runBlocking {
            store.setPageCount(entry.id, 10)
            store.setLastPage(entry.id, 4)
        }

        viewModel.openRecent(entry.id)

        val loaded = awaitLoaded()
        assertEquals("manual.pdf", loaded.name)
        assertEquals(entry.uri, loaded.document.uri.toString())
        assertEquals(4, viewModel.pendingRestorePage)
        assertEquals(entry.id, runBlocking { store.openIn(TASK) }?.id)
    }

    @Test
    fun `a document opens with its title and table of contents`() {
        val pdf = """
            %PDF-1.4
            1 0 obj << /Type /Catalog /Pages 2 0 R /Outlines 4 0 R >> endobj
            2 0 obj << /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 612 792] >> endobj
            3 0 obj << /Type /Page /Parent 2 0 R >> endobj
            4 0 obj << /First 5 0 R >> endobj
            5 0 obj << /Title (Introduction) /Dest [3 0 R /Fit] >> endobj
            6 0 obj << /Title (Manual) >> endobj
            trailer << /Root 1 0 R /Info 6 0 R >>
            %%EOF
        """.trimIndent()
        viewModel.openRecent(copy("manual.pdf", pdf).id)

        val loaded = awaitLoaded()
        assertEquals("Manual", loaded.title)
        assertEquals(listOf(OutlineItem("Introduction", 0, null, false, emptyList())), loaded.outline)
    }

    @Test
    fun `a file from another app opens from its copy`() {
        val uri = provide("attachment.pdf", PDF)

        viewModel.openFromOtherApp(uri)

        val loaded = awaitLoaded()
        val entry = store.entries.value.single()
        assertTrue(entry.isCopy)
        assertEquals(entry.uri, loaded.document.uri.toString())
        assertEquals("attachment.pdf", loaded.name)
    }

    @Test
    fun `a file URI from another app is refused`() {
        viewModel.openFromOtherApp(Uri.fromFile(File(context.filesDir, "recent/index.json")))

        assertEquals(DocumentState.Failed(FailureReason.NO_PERMISSION), viewModel.documentState)
        assertEquals(emptyList<Uri>(), loader.attempts.map { it.first })
    }

    @Test
    fun `shared images are made into a PDF that's kept like a copy`() {
        viewModel.openImages(listOf(provide("IMG_1.jpg", jpeg()), provide("IMG_2.jpg", jpeg())))

        val loaded = awaitLoaded()
        val entry = store.entries.value.single()
        assertTrue(entry.isCopy)
        assertEquals(entry.uri, loaded.document.uri.toString())
        assertTrue(loaded.name, loaded.name.startsWith("Images ") && loaded.name.endsWith(".pdf"))
        assertEquals(2, PdfMetadataReader(ByteArraySource(File(loaded.document.uri.path!!).readBytes())).read().pageCount)
        // Offered to be saved, as it exists only in the app.
        assertEquals(0, viewModel.createdNotice)
    }

    @Test
    fun `a PDF of one image is named after it`() {
        viewModel.openImages(listOf(provide("IMG_1.jpg", jpeg())))

        assertEquals("IMG_1.pdf", awaitLoaded().name)
    }

    // Robolectric's default graphics decode anything.
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test
    fun `images that can't be read are left out, and without any there's no PDF`() {
        val notes = provide("notes.jpg", "Just some notes")
        viewModel.openImages(listOf(provide("IMG_1.jpg", jpeg()), notes))
        awaitLoaded()
        assertEquals(1, viewModel.createdNotice)

        // Each share gets a card of its own.
        runningTasks(TASK, OTHER_TASK)
        val other = viewModel(SavedStateHandle()).apply { taskId = OTHER_TASK }
        other.openImages(listOf(notes))
        assertEquals(DocumentState.Failed(FailureReason.NO_IMAGES), awaitState(other) { it is DocumentState.Failed })
        assertNull(other.createdNotice)
        assertEquals(1, store.entries.value.size)
    }

    @Test
    fun `an entry that's gone can't be opened`() {
        viewModel.openRecent("gone")

        assertEquals(DocumentState.Failed(FailureReason.UNAVAILABLE), awaitState { it is DocumentState.Failed })
    }

    @Test
    fun `a stored filled-in form opens instead of the original`() {
        val entry = copy("form.pdf")
        fillIn(entry)

        viewModel.openRecent(entry.id)

        val loaded = awaitLoaded()
        assertEquals(Uri.fromFile(store.filledFile(entry.id)), loaded.document.uri)
        assertTrue(viewModel.isFilledIn)
    }

    @Test
    fun `a damaged filled-in form is dropped and the original opens`() {
        val entry = copy("form.pdf")
        fillIn(entry)
        loader.fails = { it.path!!.contains("/filled/") }

        viewModel.openRecent(entry.id)

        val loaded = awaitLoaded()
        assertEquals(entry.uri, loaded.document.uri.toString())
        assertFalse(viewModel.isFilledIn)
        assertFalse(runBlocking { store.get(entry.id) }!!.hasFilledVersion)
    }

    @Test
    fun `a wrong password counts, the right one shows the document once the lock has opened`() {
        val entry = copy("secret.pdf")
        loader.password = "kiwi"

        viewModel.openRecent(entry.id)
        assertEquals(DocumentState.NeedsPassword("secret.pdf"), awaitState { it is DocumentState.NeedsPassword })

        viewModel.submitPassword("wrong", remember = false)
        val wrong = awaitState { it is DocumentState.NeedsPassword && !it.isChecking }
        assertEquals(1, (wrong as DocumentState.NeedsPassword).wrongAttempts)

        viewModel.submitPassword("kiwi", remember = false)
        val unlocked = awaitState { it is DocumentState.NeedsPassword && it.unlocked != null }
        assertTrue((unlocked as DocumentState.NeedsPassword).unlocked!!.isProtected)

        viewModel.unlockFinished()
        assertTrue(awaitLoaded().isProtected)
        waitUntil { runBlocking { store.get(entry.id) }!!.isProtected }
        assertNull(runBlocking { store.get(entry.id) }!!.encryptedPassword)
    }

    @Test
    fun `a password is remembered when the user asks`() {
        val entry = copy("secret.pdf")
        loader.password = "kiwi"
        viewModel.openRecent(entry.id)
        awaitState { it is DocumentState.NeedsPassword }

        viewModel.submitPassword("kiwi", remember = true)

        awaitState { it is DocumentState.NeedsPassword && it.unlocked != null }
        waitUntil { runBlocking { store.get(entry.id)!!.encryptedPassword == FakeEncryption.encrypt("kiwi") } }
    }

    @Test
    fun `a remembered password opens the document without asking`() {
        val entry = copy("secret.pdf")
        loader.password = "kiwi"
        runBlocking { store.setPassword(entry.id, FakeEncryption.encrypt("kiwi")) }

        viewModel.openRecent(entry.id)

        assertTrue(awaitLoaded().isProtected)
        assertEquals("kiwi", loader.attempts.single().second)
    }

    @Test
    fun `a remembered password that stopped working is forgotten`() {
        val entry = copy("secret.pdf")
        loader.password = "changed"
        runBlocking { store.setPassword(entry.id, FakeEncryption.encrypt("kiwi")) }

        viewModel.openRecent(entry.id)

        awaitState { it is DocumentState.NeedsPassword }
        assertNull(runBlocking { store.get(entry.id) }!!.encryptedPassword)
    }

    @Test
    fun `a passing failure is retried once, then offers to try again and keeps the entry`() {
        val entry = copy("manual.pdf")
        runBlocking { store.setPageCount(entry.id, 10) }
        loader.fails = { true }

        viewModel.openRecent(entry.id)

        assertEquals(DocumentState.Failed(FailureReason.TEMPORARY), awaitState { it is DocumentState.Failed })
        assertEquals(2, loader.attempts.size)
        assertNotNull(runBlocking { store.get(entry.id) })

        loader.fails = { false }
        viewModel.retry()
        awaitLoaded()
    }

    @Test
    fun `a file that isn't a PDF leaves the list`() {
        val entry = copy("notes.pdf", content = "Just some notes")
        loader.fails = { true }

        viewModel.openRecent(entry.id)

        assertEquals(DocumentState.Failed(FailureReason.NOT_PDF), awaitState { it is DocumentState.Failed })
        assertEquals(1, loader.attempts.size)
        assertNull(runBlocking { store.get(entry.id) })
    }

    @Test
    fun `a document open in another card is shown there`() {
        val entry = copy("manual.pdf")
        runningTasks(OTHER_TASK, TASK)
        runBlocking { store.setOpen(OTHER_TASK, entry.id) }

        viewModel.openRecent(entry.id)

        waitUntil { viewModel.shownInTask == OTHER_TASK }
        assertEquals(emptyList<Uri>(), loader.attempts.map { it.first })
    }

    @Test
    fun `after process death the document opens again`() {
        val entry = copy("manual.pdf")
        viewModel.openRecent(entry.id)
        awaitLoaded()

        val recreated = viewModel(savedState)

        assertTrue(recreated.restore())
        awaitState(recreated) { it is DocumentState.Loaded }
        assertFalse(viewModel(SavedStateHandle()).restore())
    }

    @Test
    fun `presentation mode ends where it was, also after process death`() {
        val entry = copy("slides.pdf")
        viewModel.openRecent(entry.id)
        awaitLoaded()

        viewModel.startPresentation(3)
        viewModel.onPresentationPageChanged(5)
        val recreated = viewModel(savedState)
        assertTrue(recreated.restore())
        awaitState(recreated) { it is DocumentState.Loaded }
        assertEquals(5, recreated.presentationPage)

        recreated.endPresentation()
        assertNull(recreated.presentationPage)
        assertEquals(5, recreated.pendingRestorePage)
        waitUntil { runBlocking { store.get(entry.id) }?.lastPage == 5 }

        // Another document opens outside presentation mode.
        recreated.startPresentation(2)
        recreated.openRecent(copy("other.pdf").id)
        assertNull(recreated.presentationPage)
        assertNull(viewModel(savedState).presentationPage)
    }

    private fun viewModel(savedState: SavedStateHandle) =
        ViewerViewModel(context, savedState, store, loader, FakeEncryption).apply { taskId = TASK }

    /** A recent entry for a file handed over by another app. */
    private fun copy(name: String, content: String = PDF): RecentFile =
        runBlocking { store.importCopy(provide(name, content), name)!! }

    private fun jpeg(): ByteArray {
        val output = ByteArrayOutputStream()
        ImageIO.write(BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB), "jpg", output)
        return output.toByteArray()
    }

    /** Stores a filled-in version of [entry]'s form. */
    private fun fillIn(entry: RecentFile) = runBlocking {
        assertTrue(store.saveFilled(entry.id) { it.writeText("$PDF filled in") })
    }

    private fun awaitLoaded(): DocumentState.Loaded =
        awaitState { it is DocumentState.Loaded } as DocumentState.Loaded

    private fun awaitState(
        viewModel: ViewerViewModel = this.viewModel,
        condition: (DocumentState) -> Boolean,
    ): DocumentState {
        waitUntil { condition(viewModel.documentState) }
        return viewModel.documentState
    }

    /**
     * Runs the main thread, where the view model works, until [condition] holds. Its clock moves
     * on with each step, so delays (the retry) pass.
     */
    private fun waitUntil(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 5000
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "Timed out in ${viewModel.documentState}" }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            Thread.sleep(2)
        }
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

    /**
     * Opens every document unless [fails] says so, and protected ones ([password] set) only with
     * that password. Records each attempt.
     */
    private class FakeLoader : PdfLoader {
        val attempts = mutableListOf<Pair<Uri, String?>>()
        var password: String? = null
        var fails: (Uri) -> Boolean = { false }

        override suspend fun openDocument(uri: Uri, password: String?): PdfDocument {
            attempts += uri to password
            if (fails(uri)) throw IOException("Can't open $uri")
            if (this.password != null && password != this.password) throw PdfPasswordException("Wrong password")
            return fakeDocument(uri)
        }

        override suspend fun openDocument(
            uri: Uri,
            fileDescriptor: ParcelFileDescriptor,
            password: String?,
            renderParams: RenderParams,
        ): PdfDocument = throw UnsupportedOperationException()
    }

    /** Keeps passwords readable, as Robolectric has no Android Keystore. */
    private object FakeEncryption : PasswordEncryption {
        override suspend fun encrypt(password: String) = "encrypted:$password"
        override suspend fun decrypt(value: String) = value.removePrefix("encrypted:")
    }

    private companion object {
        const val TASK = 6
        const val OTHER_TASK = 5
        const val PDF = "%PDF-1.4"
        const val PAGE_COUNT = 10

        /**
         * A document with a URI and pages, but nothing to render or read: those calls fail, as
         * a broken page would (no thumbnail, no page size). A proxy, as [PdfDocument] has many
         * methods the viewer model doesn't use.
         */
        fun fakeDocument(uri: Uri): PdfDocument = Proxy.newProxyInstance(
            PdfDocument::class.java.classLoader,
            arrayOf(PdfDocument::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "getUri" -> uri
                "getPageCount" -> PAGE_COUNT
                "close" -> null
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "FakeDocument($uri)"
                else -> throw UnsupportedOperationException(method.name)
            }
        } as PdfDocument
    }
}
