package kiwi.lorenz.pdfreader.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.pdf.ExperimentalPdfApi
import androidx.pdf.Highlight
import androidx.pdf.PdfPoint
import androidx.pdf.PdfRect
import androidx.pdf.compose.PdfViewer
import androidx.pdf.compose.PdfViewerState
import androidx.pdf.compose.rememberPdfViewerState
import androidx.pdf.models.FormEditInfo
import kiwi.lorenz.pdfreader.DocumentState
import kiwi.lorenz.pdfreader.SearchState
import kiwi.lorenz.pdfreader.openLink
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.abs

// PdfView draws highlights with a MULTIPLY blend, so these must be opaque and light:
// pale tints of the brand green / brown that keep the text underneath readable.
private const val MATCH_COLOR = 0xFFDCE3A6.toInt()
private const val CURRENT_MATCH_COLOR = 0xFFF2B48C.toInt()

private const val PAGE_SAVE_DELAY_MS = 500L

/** How far the user has to scroll in one direction before the top bar hides or shows. */
private val BarToggleDistance = 32.dp

@OptIn(ExperimentalPdfApi::class)
@Composable
internal fun DocumentView(
    documentState: DocumentState.Loaded,
    searchState: SearchState,
    pendingRestorePage: Int?,
    onPageRestored: () -> Unit,
    onPageChanged: (Int) -> Unit,
    contentPadding: PaddingValues,
    onBarHiddenChange: (Boolean) -> Unit,
    onFormEdit: (FormEditInfo) -> Unit,
    onLinkFailed: () -> Unit,
) {
    val context = LocalContext.current
    val viewerState = rememberPdfViewerState()
    var isContentLoaded by remember { mutableStateOf(false) }
    var viewportHeight by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val topPaddingPx = with(density) { contentPadding.calculateTopPadding().toPx() }
    val toggleDistancePx = with(density) { BarToggleDistance.toPx() }

    LaunchedEffect(viewerState, searchState.matches, searchState.currentIndex) {
        showMatches(viewerState, searchState)
    }

    LaunchedEffect(viewerState, isContentLoaded, pendingRestorePage) {
        if (!isContentLoaded) return@LaunchedEffect
        if (pendingRestorePage != null) {
            viewerState.scrollToPage(pendingRestorePage)
            onPageRestored()
            return@LaunchedEffect
        }
        // Only track after restoring, or the initial page 0 would overwrite the saved page.
        snapshotFlow { viewerState.centerPage(viewportHeight) }
            .distinctUntilChanged()
            .collectLatest { page ->
                delay(PAGE_SAVE_DELAY_MS)
                onPageChanged(page)
            }
    }

    LaunchedEffect(viewerState) {
        var previous: Pair<Int, Float>? = null
        var travelled = 0f
        snapshotFlow { viewerState.firstVisiblePage to viewerState.firstVisiblePageOffset.y }
            .collect { (page, top) ->
                val last = previous
                previous = page to top
                if (page == 0 && top >= topPaddingPx - 1) {
                    // Back at the start of the document.
                    travelled = 0f
                    onBarHiddenChange(false)
                    return@collect
                }
                // Only the user's own scrolling counts, not jumps to search matches or saved pages.
                if (last == null || viewerState.gestureState == PdfViewerState.GESTURE_STATE_IDLE) {
                    return@collect
                }
                // Positive when scrolling down through the document.
                val delta = when {
                    page > last.first -> toggleDistancePx
                    page < last.first -> -toggleDistancePx
                    else -> last.second - top
                }
                if (delta * travelled < 0) travelled = 0f
                travelled += delta
                if (abs(travelled) >= toggleDistancePx) onBarHiddenChange(travelled > 0)
            }
    }

    FastScrollAutoHide(contentLoaded = isContentLoaded)

    PdfViewer(
        pdfDocument = documentState.document,
        state = viewerState,
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { viewportHeight = it.height }
            .testTag(TestTags.DOCUMENT),
        // Pages start below the bar and scroll behind it and the navigation bar.
        contentPadding = contentPadding,
        onFirstContentLoad = { isContentLoaded = true },
        // Not from the start: PdfViewer sets the document before enabling form filling, so the
        // first pages load without their form fields, and taps on them do nothing (beta01).
        // Enabling it once they're loaded makes the view fetch their fields again.
        isFormFillingEnabled = isContentLoaded,
        onFormWidgetInfoUpdated = onFormEdit,
        // Handled here, so links with no app to open them don't fail silently.
        onUrlLinkClicked = { uri ->
            if (!context.openLink(uri)) onLinkFailed()
            true
        },
    )
}

/** The page covering the middle of the viewport: what the user is reading. */
@OptIn(ExperimentalPdfApi::class)
private fun PdfViewerState.centerPage(viewportHeight: Int): Int {
    // Read on every scroll, so snapshotFlow re-evaluates while scrolling within a page.
    firstVisiblePageOffset
    var page = firstVisiblePage
    for (candidate in firstVisiblePage until firstVisiblePage + visiblePagesCount) {
        val top = getVisiblePageOffset(candidate)?.y ?: continue
        if (top <= viewportHeight / 2f) page = candidate
    }
    return page
}

@OptIn(ExperimentalPdfApi::class)
private suspend fun showMatches(viewerState: PdfViewerState, searchState: SearchState) {
    viewerState.setHighlights(
        searchState.matches.flatMapIndexed { index, match ->
            val color = if (index == searchState.currentIndex) CURRENT_MATCH_COLOR else MATCH_COLOR
            match.bounds.map { rect ->
                Highlight(PdfRect(match.pageNum, rect.left, rect.top, rect.right, rect.bottom), color)
            }
        }
    )
    val current = searchState.currentMatch ?: return
    val rect = current.bounds.firstOrNull() ?: return
    viewerState.scrollToPosition(PdfPoint(current.pageNum, rect.centerX(), rect.centerY()))
}
