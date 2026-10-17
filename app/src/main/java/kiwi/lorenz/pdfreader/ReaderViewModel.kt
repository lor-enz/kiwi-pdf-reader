package kiwi.lorenz.pdfreader

import android.app.Application
import android.graphics.RectF
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.pdf.PdfDocument
import androidx.pdf.PdfFeature
import androidx.pdf.PdfPasswordException
import androidx.pdf.SandboxedPdfLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

enum class FailureReason { PASSWORD_PROTECTED, UNREADABLE }

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

    var documentState by mutableStateOf<DocumentState>(DocumentState.Empty)
        private set

    var searchState by mutableStateOf(SearchState())
        private set

    /** True when another app handed us the PDF; closing it should return to that app. */
    var openedFromOtherApp = false
        private set

    private var loadJob: Job? = null
    private var searchJob: Job? = null

    fun open(uri: Uri, fromOtherApp: Boolean = false) {
        openedFromOtherApp = fromOtherApp
        closeDocument()
        documentState = DocumentState.Loading
        loadJob = viewModelScope.launch {
            documentState = try {
                val document = loader.openDocument(uri)
                DocumentState.Loaded(
                    document = document,
                    name = displayName(uri),
                    isSearchSupported = document.isFeatureSupported(PdfFeature.SEARCH),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: PdfPasswordException) {
                DocumentState.Failed(FailureReason.PASSWORD_PROTECTED)
            } catch (_: Exception) {
                // IOException, SecurityException (permission lost), corrupt files, ...
                DocumentState.Failed(FailureReason.UNREADABLE)
            }
        }
    }

    fun close() {
        closeDocument()
        documentState = DocumentState.Empty
        openedFromOtherApp = false
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
    }
}
