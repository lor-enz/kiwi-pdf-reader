package kiwi.lorenz.pdfreader

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.pdf.EditablePdfDocument
import androidx.pdf.PdfDocument
import androidx.pdf.PdfPasswordException
import androidx.pdf.SandboxedPdfLoader
import androidx.pdf.models.FormEditInfo
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

    val search = DocumentSearch(viewModelScope) { loadedDocument()?.document }

    /** The page to scroll to once the document is shown, or null. Consumed by the viewer. */
    var pendingRestorePage by mutableStateOf<Int?>(null)
        private set

    /**
     * True when the document shows filled-in form fields: stored ones it was opened with, or
     * edits since. Share, print and save a copy then use them, and they can be cleared.
     */
    var isFilledIn by mutableStateOf(false)
        private set

    /** A short message for the viewer's snackbar (a string resource), cleared once shown. */
    var message by mutableStateOf<Int?>(null)
        private set

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

    private var form: FormAutosave? = null
    private var loadJob: Job? = null

    /** The document waiting for a password, see [DocumentState.NeedsPassword]. */
    private var passwordTarget: Target? = null

    /** The entry that failed to open for now, see [FailureReason.TEMPORARY]. */
    private var retryEntryId: String? = null

    // Opening

    /**
     * After process death the activity is recreated with its saved state, but not the document.
     * False if no document was open.
     */
    fun restore(): Boolean {
        val id = openEntryId ?: return false
        launchOpen { store.get(id)?.also { store.touch(it.id) }?.let { Target(it) } }
        return true
    }

    fun openRecent(id: String) = launchOpen {
        store.touch(id)
        store.get(id)?.let { Target(it) }
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

    /** Opens the document again after a failure that's most likely passing. */
    fun retry() {
        val id = retryEntryId ?: return
        launchOpen { store.get(id)?.let { Target(it) } }
    }

    /** Retries the document that asked for a password. */
    fun submitPassword(password: String, remember: Boolean) {
        val target = passwordTarget ?: return
        val prompt = documentState as? DocumentState.NeedsPassword ?: return
        if (prompt.unlocked != null) return
        loadJob?.cancel()
        // Keep the prompt on screen while checking, so typed text and checkbox survive.
        documentState = prompt.copy(isChecking = true)
        loadJob = viewModelScope.launch {
            val state = open(target, password, remember, isTypedByUser = true)
            // The document shows once the prompt's lock has opened, see unlockFinished.
            documentState = when (state) {
                is DocumentState.Loaded -> prompt.copy(unlocked = state)
                is DocumentState.NeedsPassword -> prompt.copy(wrongAttempts = prompt.wrongAttempts + 1)
                else -> state
            }
        }
    }

    /** The prompt's lock has opened: the document it held back shows. */
    fun unlockFinished() {
        val prompt = documentState as? DocumentState.NeedsPassword
        documentState = prompt?.unlocked ?: return
    }

    // The open document

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

    /**
     * The file that has the document as shown, filled-in fields included: the original, the
     * stored filled-in version, or a temporary file when edits can't be stored with an entry.
     */
    suspend fun currentFile(): Uri? {
        val loaded = loadedDocument() ?: return null
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

    /** Writes the document to [to], a file the user created with the system's "Save to" dialog. */
    fun saveCopy(to: Uri) {
        viewModelScope.launch {
            val saved = runCatchingCancellable {
                getApplication<Application>().savePdfCopy(checkNotNull(currentFile()), to)
            }
            message = if (saved.isSuccess) R.string.copy_saved else R.string.save_failed
        }
    }

    fun showMessage(@StringRes message: Int) {
        this.message = message
    }

    fun onMessageShown() {
        message = null
    }

    // Forms

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

    /** Only when the viewer is closed for good, not on rotation or process death. */
    override fun onCleared() {
        closeDocument()
        store.closed(taskId)
    }

    // Loading

    /** What to open: a recent entry, or a bare URI when it couldn't be added to the list. */
    private class Target(val entry: RecentFile?, val uri: Uri, val name: String, val isFilledIn: Boolean = false) {
        constructor(entry: RecentFile) : this(entry, entry.uri.toUri(), entry.name)
    }

    private fun loadedDocument() = documentState as? DocumentState.Loaded

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
            suspend fun openWithRemembered(target: Target) =
                open(target, remembered, rememberPassword = remembered != null, isTypedByUser = false)

            // A form filled in before opens as it was left.
            val filled = target.entry?.let { store.filledVersion(it.id) }
                ?.let { Target(target.entry, Uri.fromFile(it), target.name, isFilledIn = true) }
            var state = openWithRemembered(filled ?: target)
            if (filled != null && state is DocumentState.Failed) {
                // Our own file, so it's damaged; the original still opens, without the edits.
                store.clearFilled(target.entry.id)
                state = openWithRemembered(target)
            }
            documentState = state
        }
    }

    private suspend fun open(
        target: Target,
        password: String?,
        rememberPassword: Boolean,
        isTypedByUser: Boolean,
        isRetry: Boolean = false,
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
            DocumentState.NeedsPassword(target.name)
        } catch (_: Exception) {
            val openedBefore = entry != null && entry.pageCount > 0
            val reason = withContext(Dispatchers.IO) {
                getApplication<Application>().contentResolver.diagnoseFailure(target.uri, openedBefore)
            }
            // Retried once on its own (the PDF service may have to restart), so the user only
            // sees failures that last.
            if (reason == FailureReason.TEMPORARY && !isRetry) {
                delay(RETRY_DELAY_MS)
                return open(target, password, rememberPassword, isTypedByUser, isRetry = true)
            }
            if (reason == FailureReason.TEMPORARY) retryEntryId = entry?.id
            // A failing filled-in version says nothing about the original, see launchOpen.
            if (entry != null && !target.isFilledIn && !reason.keepsEntry) store.remove(entry.id)
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
            } else if (entry.isProtected) {
                store.setUnprotected(entry.id)
            }
            // Thumbnails of protected documents only when the password is remembered. No
            // thumbnail is fine if rendering fails; the list shows a placeholder.
            val showsThumbnail = password == null || rememberPassword
            if (showsThumbnail && !entry.hasThumbnail) {
                runCatchingCancellable { store.saveThumbnail(entry.id, document.renderThumbnail()) }
            }
            store.cleanup()
        }
        // On its own, as hashing a large file takes a while.
        if (!entry.isCopy) viewModelScope.launch { store.hashPicked(entry.id) }
    }

    private fun resetForOpen() {
        closeDocument()
        isFilledIn = false
        openEntryId = null
        pendingRestorePage = null
        passwordTarget = null
        retryEntryId = null
    }

    private fun closeDocument() {
        loadJob?.cancel()
        search.reset()
        val document = loadedDocument()?.document ?: return
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

    private companion object {
        const val KEY_OPEN_ENTRY = "openEntryId"
        const val RETRY_DELAY_MS = 500L
    }
}
