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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.pdf.PdfDocument
import androidx.pdf.PdfPasswordException
import androidx.pdf.SandboxedPdfLoader
import kiwi.lorenz.pdfreader.recent.PasswordCipher
import kiwi.lorenz.pdfreader.recent.RecentFile
import kiwi.lorenz.pdfreader.recent.RecentFilesStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
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

class ReaderViewModel(
    application: Application,
    private val savedState: SavedStateHandle,
) : AndroidViewModel(application) {
    private val loader = SandboxedPdfLoader(application)
    private val store = RecentFilesStore.get(application)

    /** Newest first. */
    val recentFiles: StateFlow<List<RecentFile>> = store.entries

    var documentState by mutableStateOf<DocumentState>(DocumentState.Empty)
        private set

    var searchState by mutableStateOf(SearchState())
        private set

    /** The page to scroll to once the document is shown, or null. Consumed by the viewer. */
    var pendingRestorePage by mutableStateOf<Int?>(null)
        private set

    /** True when another app handed us the PDF; closing it should return to that app. */
    var openedFromOtherApp: Boolean
        get() = savedState[KEY_FROM_OTHER_APP] ?: false
        private set(value) {
            savedState[KEY_FROM_OTHER_APP] = value
        }

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

    init {
        val restoreId = openEntryId
        viewModelScope.launch {
            val entry = restoreId?.let { store.get(it) }
            if (entry != null && documentState == DocumentState.Empty) {
                launchOpen(fromOtherApp = openedFromOtherApp) { Target(entry) }
            }
            store.cleanup(openId = restoreId)
            application.deleteOldSharedCopies()
        }
    }

    /** A file chosen with the app's own picker. */
    fun openPicked(uri: Uri) = launchOpen(fromOtherApp = false) {
        val name = displayName(uri)
        store.addPicked(uri, name)?.let { Target(it) } ?: Target(null, uri, name)
    }

    /** A file handed over by another app; it is copied so it can be reopened later. */
    fun openFromOtherApp(uri: Uri) {
        // A file URI would be read with this app's own rights, which reach its private files.
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
            resetForOpen(fromOtherApp = true)
            documentState = DocumentState.Failed(FailureReason.NO_PERMISSION)
            return
        }
        launchOpen(fromOtherApp = true) {
            val name = displayName(uri)
            store.importCopy(uri, name)?.let { Target(it) } ?: Target(null, uri, name)
        }
    }

    fun openRecent(entry: RecentFile) = launchOpen(fromOtherApp = false) {
        store.touch(entry.id)
        Target(entry)
    }

    fun close() {
        closeDocument()
        documentState = DocumentState.Empty
        openedFromOtherApp = false
        openEntryId = null
        pendingRestorePage = null
        passwordTarget = null
        viewModelScope.launch { store.cleanup(openId = null) }
    }

    fun thumbnailFile(entry: RecentFile): File = store.thumbnailFile(entry.id)

    /** Removed from the list, but its files are kept while the removal can be undone. */
    var pendingRemoval by mutableStateOf<RecentFile?>(null)
        private set

    fun removeRecent(entry: RecentFile) {
        // One undo at a time: an earlier removal becomes final.
        pendingRemoval?.let(store::discard)
        pendingRemoval = entry
        viewModelScope.launch { store.hide(entry.id) }
    }

    fun undoRemoval(entry: RecentFile) {
        if (pendingRemoval == entry) pendingRemoval = null
        viewModelScope.launch { store.restore(entry) }
    }

    /** The thumbnail goes too, as it shows protected content. */
    fun forgetPassword(entry: RecentFile) {
        viewModelScope.launch { store.setPassword(entry.id, null) }
    }

    fun finishRemoval(entry: RecentFile) {
        if (pendingRemoval == entry) pendingRemoval = null
        store.discard(entry)
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
            val results = try {
                document.searchDocument(query.trim(), 0 until document.pageCount)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            val matches = buildList {
                // SparseArray keys are sorted, so matches come out in reading order.
                for (i in 0 until (results?.size() ?: 0)) {
                    val pageNum = results!!.keyAt(i)
                    results.valueAt(i).forEach { add(SearchMatch(pageNum, it.bounds)) }
                }
            }
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
        return try {
            (0 until document.pageCount).any { page ->
                document.getPageContent(page)?.textContents.orEmpty().any { it.text.isNotBlank() }
            }.also { documentHasText = it }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Unknown; "no matches" is never wrong.
            true
        }
    }

    fun nextMatch() = moveMatch(+1)

    fun previousMatch() = moveMatch(-1)

    private fun moveMatch(step: Int) {
        val count = searchState.matches.size
        if (count == 0) return
        searchState = searchState.copy(currentIndex = (searchState.currentIndex + step).mod(count))
    }

    /** What to open: a recent entry, or a bare URI when it couldn't be added to the list. */
    private class Target(val entry: RecentFile?, val uri: Uri, val name: String) {
        constructor(entry: RecentFile) : this(entry, entry.uri.toUri(), entry.name)
    }

    private fun resetForOpen(fromOtherApp: Boolean) {
        closeDocument()
        openedFromOtherApp = fromOtherApp
        openEntryId = null
        pendingRestorePage = null
        passwordTarget = null
    }

    private fun launchOpen(fromOtherApp: Boolean, resolve: suspend () -> Target) {
        resetForOpen(fromOtherApp)
        documentState = DocumentState.Loading
        loadJob = viewModelScope.launch {
            val target = resolve()
            val remembered = target.entry?.encryptedPassword?.let(PasswordCipher::decrypt)
            documentState = open(target, remembered, rememberPassword = remembered != null, isTypedByUser = false)
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
            if (entry != null && !reason.keepsEntry(openedBefore)) store.remove(entry.id)
            DocumentState.Failed(reason)
        }
    }

    private fun onOpened(entry: RecentFile, document: PdfDocument, password: String?, rememberPassword: Boolean) {
        openEntryId = entry.id
        pendingRestorePage = entry.lastPage.takeIf { it in 1 until document.pageCount }
        viewModelScope.launch {
            store.setPageCount(entry.id, document.pageCount)
            if (password != null) {
                store.setPassword(entry.id, if (rememberPassword) PasswordCipher.encrypt(password) else null)
            }
            // Thumbnails of protected documents only when the password is remembered.
            val showsThumbnail = password == null || rememberPassword
            if (showsThumbnail && !entry.hasThumbnail) renderThumbnail(entry.id, document)
            store.cleanup(openId = entry.id)
        }
        // On its own, as hashing a large file takes a while.
        if (!entry.isCopy) viewModelScope.launch { store.hashPicked(entry.id) }
    }

    private suspend fun renderThumbnail(id: String, document: PdfDocument) {
        try {
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
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // No thumbnail is fine; the list shows a placeholder.
        }
    }

    private fun closeDocument() {
        loadJob?.cancel()
        closeSearch()
        documentHasText = null
        (documentState as? DocumentState.Loaded)?.document?.close()
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

    override fun onCleared() {
        closeDocument()
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 300L
        const val THUMBNAIL_WIDTH_PX = 240
        const val KEY_OPEN_ENTRY = "openEntryId"
        const val KEY_FROM_OTHER_APP = "openedFromOtherApp"
    }
}
