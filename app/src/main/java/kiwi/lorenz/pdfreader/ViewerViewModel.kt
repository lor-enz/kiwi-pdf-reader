package kiwi.lorenz.pdfreader

import android.app.Application
import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Size
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import androidx.core.util.size
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.pdf.EditablePdfDocument
import androidx.pdf.PdfDocument
import androidx.pdf.models.FormEditInfo
import androidx.pdf.PdfPasswordException
import androidx.pdf.SandboxedPdfLoader
import kiwi.lorenz.pdfreader.recent.PasswordCipher
import kiwi.lorenz.pdfreader.recent.RecentFile
import kiwi.lorenz.pdfreader.recent.RecentFilesStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

sealed interface DocumentState {
    data object Empty : DocumentState
    data object Loading : DocumentState
    data class Loaded(
        val document: PdfDocument,
        val name: String,
        /** Opened with a password; other apps can't read the file on their own. */
        val isProtected: Boolean,
    ) : DocumentState
    /** [isWrongPassword] is true after the user entered a password that didn't work. */
    data class NeedsPassword(
        val name: String,
        val isWrongPassword: Boolean,
        val isChecking: Boolean = false,
    ) : DocumentState
    data class Failed(val reason: FailureReason) : DocumentState
}

enum class FailureReason {
    /** Has a PDF header but couldn't be read, e.g. an interrupted download. */
    DAMAGED,
    /** Some other kind of file. */
    NOT_PDF,
    /** The app that provides the file didn't allow us to read it. */
    NO_PERMISSION,
    /** Moved or deleted. */
    UNAVAILABLE,
}

/** A single search hit. A match spanning several lines has one rect per line. */
data class SearchMatch(val pageNum: Int, val bounds: List<RectF>)

data class SearchState(
    val isActive: Boolean = false,
    val query: String = "",
    val matches: List<SearchMatch> = emptyList(),
    val currentIndex: Int = -1,
    val isSearching: Boolean = false,
    /** Nothing found because the document has no text at all, e.g. a scan. */
    val hasNoText: Boolean = false,
) {
    val currentMatch: SearchMatch? get() = matches.getOrNull(currentIndex)
}

/** One open document, see [ViewerActivity]. */
class ViewerViewModel(
    application: Application,
    private val savedState: SavedStateHandle,
) : AndroidViewModel(application) {
    private val loader = SandboxedPdfLoader(application)
    private val store = RecentFilesStore.get(application)

    /** The task (card in the app switcher) this viewer is in. Set by the activity before opening. */
    var taskId = -1

    var documentState by mutableStateOf<DocumentState>(DocumentState.Empty)
        private set

    var searchState by mutableStateOf(SearchState())
        private set

    /** The page to scroll to once the document is shown, or null. Consumed by the viewer. */
    var pendingRestorePage by mutableStateOf<Int?>(null)
        private set

    /** A short message for the viewer's snackbar (a string resource), cleared once shown. */
    var message by mutableStateOf<Int?>(null)
        private set

    fun showMessage(@StringRes message: Int) {
        this.message = message
    }

    fun onMessageShown() {
        message = null
    }

    /** Writes the document to [to], a file the user created with the system's "Save to" dialog. */
    fun saveCopy(to: Uri) {
        viewModelScope.launch {
            val saved = runCatchingCancellable {
                getApplication<Application>().savePdfCopy(checkNotNull(currentFile()), to)
            }
            message = if (saved.isSuccess) R.string.copy_saved else R.string.save_failed
        }
    }

    /**
     * True when the document shows filled-in form fields: stored ones it was opened with, or
     * edits since. Share, print and save a copy then use them, and they can be cleared.
     */
    var isFilledIn by mutableStateOf(false)
        private set

    private var form: FormAutosave? = null

    /** The viewer reports edits to form fields; the document only changes once they're applied. */
    fun applyFormEdit(edit: FormEditInfo) {
        val form = form ?: return
        form.apply(edit)
        isFilledIn = true
    }

    /** Going to the background: the app may be killed there, so the form is stored now. */
    fun onBackground() {
        viewModelScope.launch { form?.save() }
    }

    /**
     * The file that has the document as shown, filled-in fields included: the original, the
     * stored filled-in version, or a temporary file when edits can't be stored with an entry.
     */
    suspend fun currentFile(): Uri? {
        val loaded = documentState as? DocumentState.Loaded ?: return null
        val form = form
        if (form == null || !isFilledIn) return loaded.document.uri
        // Also once autosaved: the document may have been opened from the original.
        val id = openEntryId
        if (id != null && form.save()) return Uri.fromFile(store.filledFile(id))
        val app = getApplication<Application>()
        val file = File(File(app.cacheDir, UNSAVED_FORMS_DIR).apply { mkdirs() }, "$taskId.pdf")
        form.writeTo(file)
        return Uri.fromFile(file)
    }

    /** Back to the form as it was before anything was filled in. */
    fun clearFilledFields() {
        val id = openEntryId ?: return
        val form = form ?: return
        form.discard()
        launchOpen {
            form.awaitIdle()
            store.clearFilled(id)
            store.get(id)?.let { Target(it) }
        }
    }

    /**
     * Another task whose viewer already shows the requested document. A document is open in one
     * viewer only, so the activity switches to that one and closes.
     */
    var shownInTask by mutableStateOf<Int?>(null)
        private set

    /** The recent entry of the open document. Saved so it can be reopened after process death. */
    private var openEntryId: String?
        get() = savedState[KEY_OPEN_ENTRY]
        set(value) {
            savedState[KEY_OPEN_ENTRY] = value
        }

    private var loadJob: Job? = null
    private var searchJob: Job? = null

    /** Whether the open document has any text; found out on the first search without matches. */
    private var documentHasText: Boolean? = null

    /** The document waiting for a password, see [DocumentState.NeedsPassword]. */
    private var passwordTarget: Target? = null

    /**
     * After process death the activity is recreated with its saved state, but not the document.
     * False if no document was open.
     */
    fun restore(): Boolean {
        val id = openEntryId ?: return false
        launchOpen { store.get(id)?.also { store.touch(it.id) }?.let { Target(it) } }
        return true
    }

    /** A file chosen with the app's own picker. */
    fun openPicked(uri: Uri) = launchOpen {
        val name = displayName(uri)
        store.addPicked(uri, name)?.let { Target(it) } ?: Target(null, uri, name)
    }

    /** A file handed over by another app; it is copied so it can be reopened later. */
    fun openFromOtherApp(uri: Uri) {
        // A file URI would be read with this app's own rights, which reach its private files.
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
            resetForOpen()
            documentState = DocumentState.Failed(FailureReason.NO_PERMISSION)
            return
        }
        launchOpen {
            // A card the app was force-stopped in starts over with its first intent, whose
            // permission is gone by then; its copy is still there.
            store.openIn(taskId)?.also { store.touch(it.id) }?.let { return@launchOpen Target(it) }
            val name = displayName(uri)
            store.importCopy(uri, name)?.let { Target(it) } ?: Target(null, uri, name)
        }
    }

    fun openRecent(id: String) = launchOpen {
        store.touch(id)
        store.get(id)?.let { Target(it) }
    }

    /** Back to this card from elsewhere: counts as opening the document again. */
    fun onReturned() {
        val id = openEntryId ?: return
        viewModelScope.launch { store.touch(id) }
    }

    fun onPageRestored() {
        pendingRestorePage = null
    }

    fun onPageChanged(page: Int) {
        val id = openEntryId ?: return
        viewModelScope.launch { store.setLastPage(id, page) }
    }

    fun openSearch() {
        searchState = searchState.copy(isActive = true)
    }

    fun closeSearch() {
        searchJob?.cancel()
        searchState = SearchState()
    }

    fun updateQuery(query: String) {
        val document = (documentState as? DocumentState.Loaded)?.document ?: return
        searchJob?.cancel()
        if (query.isBlank()) {
            searchState = searchState.copy(
                query = query, matches = emptyList(), currentIndex = -1, isSearching = false, hasNoText = false,
            )
            return
        }
        searchState = searchState.copy(query = query, isSearching = true)
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            val results = runCatchingCancellable {
                document.searchDocument(query.trim(), 0 until document.pageCount)
            }.getOrNull()
            // SparseArray keys are sorted, so matches come out in reading order.
            val matches = results?.let { pages ->
                (0 until pages.size).flatMap { i -> pages.valueAt(i).map { SearchMatch(pages.keyAt(i), it.bounds) } }
            }.orEmpty()
            val hasNoText = matches.isEmpty() && results != null && !hasText(document)
            searchState = searchState.copy(
                matches = matches,
                currentIndex = if (matches.isEmpty()) -1 else 0,
                isSearching = false,
                hasNoText = hasNoText,
            )
        }
    }

    /** Stops at the first page with text, so only documents without any are read completely. */
    private suspend fun hasText(document: PdfDocument): Boolean {
        documentHasText?.let { return it }
        return runCatchingCancellable {
            (0 until document.pageCount).any { page ->
                document.getPageContent(page)?.textContents.orEmpty().any { it.text.isNotBlank() }
            }
        }
            .onSuccess { documentHasText = it }
            // Unknown; "no matches" is never wrong.
            .getOrDefault(true)
    }

    fun nextMatch() = moveMatch(+1)

    fun previousMatch() = moveMatch(-1)

    private fun moveMatch(step: Int) {
        val count = searchState.matches.size
        if (count == 0) return
        searchState = searchState.copy(currentIndex = (searchState.currentIndex + step).mod(count))
    }

    /** What to open: a recent entry, or a bare URI when it couldn't be added to the list. */
    private class Target(val entry: RecentFile?, val uri: Uri, val name: String, val isFilledIn: Boolean = false) {
        constructor(entry: RecentFile) : this(entry, entry.uri.toUri(), entry.name)
    }

    private fun resetForOpen() {
        closeDocument()
        isFilledIn = false
        openEntryId = null
        pendingRestorePage = null
        passwordTarget = null
    }

    /** [resolve] returns null if there's nothing to open (anymore). */
    private fun launchOpen(resolve: suspend () -> Target?) {
        resetForOpen()
        documentState = DocumentState.Loading
        loadJob = viewModelScope.launch {
            val target = resolve()
            if (target == null) {
                documentState = DocumentState.Failed(FailureReason.UNAVAILABLE)
                return@launch
            }
            target.entry?.let { entry ->
                store.taskShowing(entry.id, exceptTaskId = taskId)?.let {
                    shownInTask = it
                    return@launch
                }
            }
            // Before loading, so cleanup leaves the file alone while it opens.
            store.setOpen(taskId, target.entry?.id)
            // The keystore can be slow, so not on the main thread.
            val remembered = target.entry?.encryptedPassword?.let {
                withContext(Dispatchers.Default) { PasswordCipher.decrypt(it) }
            }
            // A form filled in before opens as it was left.
            val filled = target.entry?.let { store.filledVersion(it.id) }
                ?.let { Target(target.entry, Uri.fromFile(it), target.name, isFilledIn = true) }
            var state = open(filled ?: target, remembered, rememberPassword = remembered != null, isTypedByUser = false)
            if (filled != null && state is DocumentState.Failed) {
                // Our own file, so it's damaged; the original still opens, without the edits.
                store.clearFilled(target.entry.id)
                state = open(target, remembered, rememberPassword = remembered != null, isTypedByUser = false)
            }
            documentState = state
        }
    }

    /** Retries the document that asked for a password. */
    fun submitPassword(password: String, remember: Boolean) {
        val target = passwordTarget ?: return
        val prompt = documentState as? DocumentState.NeedsPassword ?: return
        loadJob?.cancel()
        // Keep the prompt on screen while checking, so typed text and checkbox survive.
        documentState = prompt.copy(isChecking = true)
        loadJob = viewModelScope.launch {
            documentState = open(target, password, remember, isTypedByUser = true)
        }
    }

    private suspend fun open(
        target: Target,
        password: String?,
        rememberPassword: Boolean,
        isTypedByUser: Boolean,
    ): DocumentState {
        val entry = target.entry
        return try {
            val document = loader.openDocument(target.uri, password)
            passwordTarget = null
            if (entry != null) onOpened(entry, document, password, rememberPassword)
            form = (document as? EditablePdfDocument)?.let { FormAutosave(it, entry?.id, store, viewModelScope) }
            isFilledIn = target.isFilledIn
            DocumentState.Loaded(
                document = document,
                name = target.name,
                isProtected = password != null,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: PdfPasswordException) {
            // A remembered password that stopped working is forgotten; the user is asked instead.
            if (!isTypedByUser && password != null && entry != null) store.setPassword(entry.id, null)
            passwordTarget = target
            DocumentState.NeedsPassword(target.name, isWrongPassword = isTypedByUser)
        } catch (_: Exception) {
            val openedBefore = entry != null && entry.pageCount > 0
            val reason = withContext(Dispatchers.IO) {
                getApplication<Application>().contentResolver.diagnoseFailure(target.uri, openedBefore)
            }
            // A failing filled-in version says nothing about the original, see launchOpen.
            if (entry != null && !target.isFilledIn && !reason.keepsEntry(openedBefore)) store.remove(entry.id)
            DocumentState.Failed(reason)
        }
    }

    private fun onOpened(entry: RecentFile, document: PdfDocument, password: String?, rememberPassword: Boolean) {
        openEntryId = entry.id
        pendingRestorePage = entry.lastPage.takeIf { it in 1 until document.pageCount }
        viewModelScope.launch {
            store.setPageCount(entry.id, document.pageCount)
            if (password != null) {
                val encrypted = if (rememberPassword) withContext(Dispatchers.Default) { PasswordCipher.encrypt(password) } else null
                store.setPassword(entry.id, encrypted)
            }
            // Thumbnails of protected documents only when the password is remembered.
            val showsThumbnail = password == null || rememberPassword
            if (showsThumbnail && !entry.hasThumbnail) renderThumbnail(entry.id, document)
            store.cleanup()
        }
        // On its own, as hashing a large file takes a while.
        if (!entry.isCopy) viewModelScope.launch { store.hashPicked(entry.id) }
    }

    /** No thumbnail is fine if this fails; the list shows a placeholder. */
    private suspend fun renderThumbnail(id: String, document: PdfDocument) {
        runCatchingCancellable {
            val page = document.getPageInfo(0)
            val width = THUMBNAIL_WIDTH_PX
            val height = (width * page.height.toFloat() / page.width).roundToInt().coerceIn(1, 3 * width)
            val rendered = document.getPageBitmapSource(0).use { it.getBitmap(Size(width, height)) }
            // Pages without a background render transparent; thumbnails should look like paper.
            val thumbnail = createBitmap(width, height, Bitmap.Config.ARGB_8888)
            Canvas(thumbnail).apply {
                drawColor(Color.WHITE)
                drawBitmap(rendered, null, android.graphics.Rect(0, 0, width, height), null)
            }
            store.saveThumbnail(id, thumbnail)
        }
    }

    private fun closeDocument() {
        loadJob?.cancel()
        closeSearch()
        documentHasText = null
        val document = (documentState as? DocumentState.Loaded)?.document ?: return
        val form = form
        this.form = null
        if (form?.hasUnsavedEdits == true) {
            // The last edits are stored before the document goes, even when the viewer is closed.
            AppScope.launch {
                form.save()
                document.close()
            }
        } else {
            document.close()
        }
    }

    private suspend fun displayName(uri: Uri): String = withContext(Dispatchers.IO) {
        val name = try {
            getApplication<Application>().contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        } catch (_: Exception) {
            null
        }
        name ?: uri.lastPathSegment ?: "PDF"
    }

    /** Only when the viewer is closed for good, not on rotation or process death. */
    override fun onCleared() {
        closeDocument()
        store.closed(taskId)
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 300L
        const val THUMBNAIL_WIDTH_PX = 240
        const val KEY_OPEN_ENTRY = "openEntryId"
    }
}
