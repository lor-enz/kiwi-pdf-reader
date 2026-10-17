package kiwi.lorenz.pdfreader

import android.app.Application
import android.content.Intent
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
import androidx.lifecycle.viewModelScope
import androidx.pdf.PdfDocument
import androidx.pdf.PdfFeature
import androidx.pdf.PdfPasswordException
import androidx.pdf.SandboxedPdfLoader
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
        val isSearchSupported: Boolean,
    ) : DocumentState
    data class Failed(val reason: FailureReason) : DocumentState
}

enum class FailureReason { PASSWORD_PROTECTED, UNREADABLE, UNAVAILABLE }

/** A single search hit. A match spanning several lines has one rect per line. */
data class SearchMatch(val pageNum: Int, val bounds: List<RectF>)

data class SearchState(
    val isActive: Boolean = false,
    val query: String = "",
    val matches: List<SearchMatch> = emptyList(),
    val currentIndex: Int = -1,
    val isSearching: Boolean = false,
) {
    val currentMatch: SearchMatch? get() = matches.getOrNull(currentIndex)
}

class ReaderViewModel(application: Application) : AndroidViewModel(application) {
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
    var openedFromOtherApp = false
        private set

    /** The recent entry of the open document. */
    private var openEntryId: String? = null

    private var loadJob: Job? = null
    private var searchJob: Job? = null

    init {
        viewModelScope.launch { store.cleanup(openId = null) }
    }

    /** A file chosen with the app's own picker. */
    fun openPicked(uri: Uri) = launchOpen(fromOtherApp = false) {
        val name = displayName(uri)
        if (takePersistablePermission(uri)) Target(store.addPicked(uri, name)) else Target(null, uri, name)
    }

    /** A file handed over by another app; it is copied so it can be reopened later. */
    fun openFromOtherApp(uri: Uri) = launchOpen(fromOtherApp = true) {
        val name = displayName(uri)
        store.importCopy(uri, name)?.let { Target(it) } ?: Target(null, uri, name)
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
        viewModelScope.launch { store.cleanup(openId = null) }
    }

    fun thumbnailFile(entry: RecentFile): File = store.thumbnailFile(entry.id)

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
            searchState = searchState.copy(query = query, matches = emptyList(), currentIndex = -1, isSearching = false)
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
            searchState = searchState.copy(
                matches = matches,
                currentIndex = if (matches.isEmpty()) -1 else 0,
                isSearching = false,
            )
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

    private fun launchOpen(fromOtherApp: Boolean, resolve: suspend () -> Target) {
        closeDocument()
        openedFromOtherApp = fromOtherApp
        openEntryId = null
        pendingRestorePage = null
        documentState = DocumentState.Loading
        loadJob = viewModelScope.launch {
            val target = resolve()
            val entry = target.entry
            documentState = try {
                val document = loader.openDocument(target.uri)
                if (entry != null) onOpened(entry, document)
                DocumentState.Loaded(
                    document = document,
                    name = target.name,
                    isSearchSupported = document.isFeatureSupported(PdfFeature.SEARCH),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Never list a file that can't be opened.
                entry?.let { store.remove(it.id) }
                DocumentState.Failed(
                    when {
                        e is PdfPasswordException -> FailureReason.PASSWORD_PROTECTED
                        // It opened before, so it was moved, deleted or its permission revoked.
                        entry != null && entry.pageCount > 0 -> FailureReason.UNAVAILABLE
                        else -> FailureReason.UNREADABLE
                    }
                )
            }
        }
    }

    private fun onOpened(entry: RecentFile, document: PdfDocument) {
        openEntryId = entry.id
        pendingRestorePage = entry.lastPage.takeIf { it in 1 until document.pageCount }
        viewModelScope.launch {
            store.setPageCount(entry.id, document.pageCount)
            if (!entry.hasThumbnail) renderThumbnail(entry.id, document)
            store.cleanup(openId = entry.id)
        }
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

    private fun takePersistablePermission(uri: Uri): Boolean = try {
        getApplication<Application>().contentResolver
            .takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        true
    } catch (_: SecurityException) {
        false
    }

    private fun closeDocument() {
        loadJob?.cancel()
        closeSearch()
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
    }
}
