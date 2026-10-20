package kiwi.lorenz.pdfreader.viewer

import android.graphics.RectF
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.util.size
import androidx.pdf.PdfDocument
import kiwi.lorenz.pdfreader.runCatchingCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

/**
 * Search in the open [document]: shortly after typing stops, with matches in reading order. A
 * document without any text says so instead of finding nothing.
 */
class DocumentSearch(private val scope: CoroutineScope, private val document: () -> PdfDocument?) {
    var state by mutableStateOf(SearchState())
        private set

    private var job: Job? = null

    /** Whether the document has any text; found out on the first search without matches. */
    private var documentHasText: Boolean? = null

    fun open() {
        state = state.copy(isActive = true)
    }

    fun close() {
        job?.cancel()
        state = SearchState()
    }

    /** Another document opens: the search starts over. */
    fun reset() {
        close()
        documentHasText = null
    }

    fun changeQuery(query: String) {
        val document = document() ?: return
        job?.cancel()
        if (query.isBlank()) {
            state = state.copy(
                query = query, matches = emptyList(), currentIndex = -1, isSearching = false, hasNoText = false,
            )
            return
        }
        state = state.copy(query = query, isSearching = true)
        job = scope.launch {
            delay(DEBOUNCE_MS)
            val results = runCatchingCancellable {
                document.searchDocument(query.trim(), 0 until document.pageCount)
            }.getOrNull()
            // SparseArray keys are sorted, so matches come out in reading order.
            val matches = results?.let { pages ->
                (0 until pages.size).flatMap { i -> pages.valueAt(i).map { SearchMatch(pages.keyAt(i), it.bounds) } }
            }.orEmpty()
            val hasNoText = matches.isEmpty() && results != null && !hasText(document)
            state = state.copy(
                matches = matches,
                currentIndex = if (matches.isEmpty()) -1 else 0,
                isSearching = false,
                hasNoText = hasNoText,
            )
        }
    }

    fun next() = move(+1)

    fun previous() = move(-1)

    private fun move(step: Int) {
        val count = state.matches.size
        if (count == 0) return
        state = state.copy(currentIndex = (state.currentIndex + step).mod(count))
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

    private companion object {
        const val DEBOUNCE_MS = 300L
    }
}
