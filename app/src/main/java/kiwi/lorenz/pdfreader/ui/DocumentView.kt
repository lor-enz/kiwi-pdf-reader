package kiwi.lorenz.pdfreader.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
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
import kiwi.lorenz.pdfreader.R
import kiwi.lorenz.pdfreader.SearchState
import kiwi.lorenz.pdfreader.openLink
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

// PdfView draws highlights with a MULTIPLY blend, so these must be opaque and light:
// a pale tint of Penguin Blue, and the emperor penguin's yellow-orange for the current match, that
// keep the text underneath readable.
private const val MATCH_COLOR = 0xFFBFE6FE.toInt()
private const val CURRENT_MATCH_COLOR = 0xFFFFCF7A.toInt()

private const val PAGE_SAVE_DELAY_MS = 500L

/** How many of the visible pages have to be rendered before the document shows. */
private const val PAGES_BEFORE_REVEAL = 2

/** How long the cover takes to wipe away, top to bottom. */
private const val REVEAL_MS = 400

/** The soft edge of the wipe, as a share of the view's height. */
private const val REVEAL_EDGE = 0.4f

/**
 * Shows the document even if its pages are still rendering, counted from opening and again from
 * the jump to the saved page. A safety net: if the pages never report ready (the listener is
 * restricted API), white pages are better than a document hidden for good.
 */
private const val REVEAL_TIMEOUT_MS = 3000L

private val PlaceholderIconSize = 96.dp

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
    var isRevealed by remember { mutableStateOf(false) }
    val readyPages by rememberReadyPages()
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
            // The view may scroll later (it needs the page sizes first) and reports its visible
            // pages after that. Until then they're still the first pages, which have rendered,
            // and the document would show before the saved page has.
            withTimeoutOrNull(REVEAL_TIMEOUT_MS) {
                snapshotFlow { viewerState.showsPage(pendingRestorePage) }.first { it }
            }
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

    // The document shows once the pages in view are rendered (after jumping to the saved page),
    // instead of white pages filling in one by one.
    val arePagesShown = !isRevealed && isContentLoaded && pendingRestorePage == null &&
        viewerState.visiblePagesCount > 0 &&
        (0 until minOf(viewerState.visiblePagesCount, PAGES_BEFORE_REVEAL))
            .all { viewerState.firstVisiblePage + it in readyPages }
    LaunchedEffect(arePagesShown) {
        if (arePagesShown) isRevealed = true
    }
    LaunchedEffect(pendingRestorePage) {
        delay(REVEAL_TIMEOUT_MS)
        isRevealed = true
    }

    FastScrollAutoHide(contentLoaded = isContentLoaded)

    Box(Modifier.fillMaxSize()) {
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
        // Continues the placeholder of the loading state, and wipes away from the top down, so
        // the start of the page shows first.
        val revealProgress by animateFloatAsState(
            targetValue = if (isRevealed) 1f else 0f,
            animationSpec = tween(REVEAL_MS, easing = FastOutSlowInEasing),
            label = "reveal",
        )
        if (revealProgress < 1f) {
            DocumentPlaceholder(
                contentPadding = contentPadding,
                modifier = Modifier
                    .wipeAway { revealProgress }
                    .background(MaterialTheme.colorScheme.background),
            )
        }
    }
}

/**
 * Hides the content above a soft edge that moves from the top ([progress] 0) past the bottom
 * ([progress] 1).
 */
private fun Modifier.wipeAway(progress: () -> Float): Modifier = this
    // Offscreen, so the mask only cuts out this content, not what's drawn below it.
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val edge = size.height * REVEAL_EDGE
        val start = progress() * (size.height + edge) - edge
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.Transparent, Color.Black),
                startY = start,
                endY = start + edge,
            ),
            blendMode = BlendMode.DstIn,
        )
    }

/**
 * Shown while a document opens, until its first pages are rendered. Opening takes a moment at
 * most, too short for a spinner to read as progress, so it's a calm PDF symbol instead.
 */
@Composable
internal fun DocumentPlaceholder(contentPadding: PaddingValues, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding)
            .testTag(TestTags.DOCUMENT_LOADING),
        contentAlignment = Alignment.Center,
    ) {
        // Decorative: the top bar names the document.
        Icon(
            painter = painterResource(R.drawable.pdf_box),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(PlaceholderIconSize),
        )
    }
}

@OptIn(ExperimentalPdfApi::class)
private fun PdfViewerState.showsPage(page: Int): Boolean =
    page in firstVisiblePage until firstVisiblePage + visiblePagesCount

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
