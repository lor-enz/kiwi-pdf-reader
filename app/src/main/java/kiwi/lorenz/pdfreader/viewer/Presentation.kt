package kiwi.lorenz.pdfreader.viewer

import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.pdf.PdfDocument
import kiwi.lorenz.pdfreader.R
import kiwi.lorenz.pdfreader.ui.TestTags
import kiwi.lorenz.pdfreader.ui.theme.NightBackground
import kiwi.lorenz.pdfreader.util.runCatchingCancellable
import kotlinx.coroutines.launch
import kotlin.math.floor
import kotlin.math.roundToInt

/** The space between two pages while swiping from one to the other. */
private val PageGap = 16.dp

/**
 * Presentation mode: the document one page at a time, full screen. Swiping left and right turns
 * the pages, which settle centered as in a photo gallery; so do the keyboard and presentation
 * clickers, which send its keys. Each page fits the screen and the space around it stays empty:
 * the neighbors wait right behind the screen's edges, so they slide in as soon as a swipe starts.
 * The page shown zooms (see [pageZoom]), and shows whole again once it's turned. Starts at [page]
 * and reports each page it settles on.
 */
@Composable
internal fun Presentation(
    document: PdfDocument,
    page: Int,
    onPageChanged: (Int) -> Unit,
    onExit: () -> Unit,
) {
    BackHandler(onBack = onExit)
    FullScreen()

    val pagerState = rememberPagerState(initialPage = page) { document.pageCount }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect(onPageChanged)
    }

    // Focused, so it gets the keys.
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(focusRequester) { focusRequester.requestFocus() }
    val scope = rememberCoroutineScope()

    // Page sizes in points, for the pages around the current one.
    val pageSizes = remember(document) { mutableStateMapOf<Int, IntSize>() }
    LaunchedEffect(document, pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { current ->
            for (index in (current - 2..current + 2).filter { it in 0 until document.pageCount }) {
                if (index in pageSizes) continue
                runCatchingCancellable { document.getPageInfo(index) }
                    .onSuccess { pageSizes[index] = IntSize(it.width, it.height) }
            }
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            // The app's dark background, whatever the theme: the page stands out the most.
            .background(NightBackground)
            .windowInsetsPadding(WindowInsets.displayCutout),
    ) {
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val density = LocalDensity.current
        val gap = with(density) { PageGap.roundToPx() }
        val layout = PresentationLayout(width, gap) { index ->
            pageSizes[index]?.fitInto(width, height)?.width
        }
        // Half the screen and half the page: centered, its neighbors are right behind the edges.
        // Fixed while scrolling: changed under a swipe (settling on a page of another width as the
        // next swipe starts), it would move the pages under the finger and throw off the snapping.
        var centeredSlot by remember { mutableIntStateOf(layout.slotWidth(pagerState.settledPage)) }
        val slot = if (pagerState.isScrollInProgress) centeredSlot else layout.slotWidth(pagerState.settledPage)
        // The pager keeps its offset in pixels, which no longer centers the page once its slot
        // changes. Before layout, so nothing jumps.
        SideEffect {
            if (slot != centeredSlot) {
                centeredSlot = slot
                pagerState.requestScrollToPage(pagerState.settledPage)
            }
        }
        // The page that zooms. The pager settles on the next page as soon as it starts snapping
        // to it, so until it has stopped, the one it left keeps its zoom while it slides away.
        var restingPage by remember { mutableIntStateOf(pagerState.settledPage) }
        val zoomablePage = if (pagerState.isScrollInProgress) restingPage else pagerState.settledPage
        SideEffect { restingPage = zoomablePage }
        // So the first and last page can be centered too.
        val margin = with(density) { ((width - slot) / 2).toDp() }
        val pageSize = remember(slot) {
            object : PageSize {
                override fun Density.calculateMainAxisPageSize(availableSpace: Int, pageSpacing: Int) = slot
            }
        }

        HorizontalPager(
            state = pagerState,
            pageSize = pageSize,
            contentPadding = PaddingValues(horizontal = margin),
            pageSpacing = PageGap,
            // Rendered ahead, so the next page is there when it slides in.
            beyondViewportPageCount = 1,
            modifier = Modifier
                .fillMaxSize()
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    val target = pagerState.keyTarget(event.key) ?: return@onKeyEvent false
                    scope.launch { pagerState.animateScrollToPage(target) }
                    true
                }
                .focusRequester(focusRequester)
                .focusable()
                .testTag(TestTags.PRESENTATION),
        ) { index ->
            PresentationPage(
                document = document,
                index = index,
                size = pageSizes[index]?.fitInto(width, height),
                viewport = IntSize(width, height),
                // Only the page shown: the others' zoom would cover its edges.
                isZoomable = index == zoomablePage,
                modifier = Modifier.graphicsLayer {
                    translationX = layout.shift(index, pagerState.currentPage + pagerState.currentPageOffsetFraction, slot)
                },
            )
        }
    }
}

/**
 * Where the pages go: as if on a strip, each pair of neighbors only as far apart as it takes to
 * hide one while the other is centered. The pager's slots are all as wide as the settled page's
 * ([slotWidth]); [shift] moves each page from its slot to its place on the strip. Without it, a
 * neighbor wider than the settled page would show at the edge.
 * [pageWidth] is a page's width on screen, or null while unknown.
 */
private class PresentationLayout(
    private val screenWidth: Int,
    private val gap: Int,
    private val pageWidth: (Int) -> Int?,
) {
    private fun width(index: Int) = pageWidth(index) ?: screenWidth

    fun slotWidth(index: Int) = (screenWidth + width(index)) / 2

    /** Between the centers of [index] and the page after it: the wider one hides behind the edge. */
    private fun distance(index: Int) = (screenWidth + maxOf(width(index), width(index + 1))) / 2f + gap

    /** From [index]'s slot to its place on the strip, with the pager at [position] (in pages). */
    fun shift(index: Int, position: Float, slot: Int): Float {
        val base = floor(position).toInt()
        var onStrip = -(position - base) * distance(base)
        for (between in base until index) onStrip += distance(between)
        for (between in index until base) onStrip -= distance(between)
        val inSlot = (index - position) * (slot + gap)
        return onStrip - inSlot
    }
}

/**
 * The page a key leads to, or null if it doesn't turn pages. Counted from the page being turned
 * to, so pressing a key repeatedly goes on from there.
 */
private fun PagerState.keyTarget(key: Key): Int? {
    val last = pageCount - 1
    return when (key) {
        Key.DirectionRight, Key.DirectionDown, Key.PageDown, Key.Spacebar -> targetPage + 1
        Key.DirectionLeft, Key.DirectionUp, Key.PageUp -> targetPage - 1
        Key.MoveHome -> 0
        Key.MoveEnd -> last
        else -> null
    }?.coerceIn(0, last)
}

/**
 * Hides the system bars (a swipe from the edge shows them for a moment) and keeps the screen on,
 * as nobody touches it while presenting.
 */
@Composable
private fun FullScreen() {
    val view = LocalView.current
    val window = LocalActivity.current?.window
    DisposableEffect(view, window) {
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        // Light icons on the dark background, when the bars come up for a moment.
        val wereLightBars = controller?.isAppearanceLightStatusBars
        controller?.apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
            hide(WindowInsetsCompat.Type.systemBars())
        }
        view.keepScreenOn = true
        onDispose {
            controller?.apply {
                show(WindowInsetsCompat.Type.systemBars())
                if (wereLightBars != null) {
                    isAppearanceLightStatusBars = wereLightBars
                    isAppearanceLightNavigationBars = wereLightBars
                }
            }
            view.keepScreenOn = false
        }
    }
}

/**
 * One page at [size] (null while unknown), on white like paper (pages without a background are
 * transparent). A page wider than its slot overflows it, centered. When [isZoomable], the whole
 * [viewport] (the screen) takes the zoom gestures, also beside the page.
 */
@Composable
private fun PresentationPage(
    document: PdfDocument,
    index: Int,
    size: IntSize?,
    viewport: IntSize,
    isZoomable: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (size == null) return@Box
        // Keeps the previous rendering (stretched) while the page renders anew, e.g. on rotation.
        val bitmap by produceState<ImageBitmap?>(null, document, index, size) {
            runCatchingCancellable {
                document.getPageBitmapSource(index).use { it.getBitmap(Size(size.width, size.height)) }
            }.onSuccess { value = it.asImageBitmap() }
        }
        // Dropped once the page isn't shown anymore, so it's whole again when it comes back.
        val zoom = if (isZoomable) rememberPageZoom(size) else null
        val sharpPart = zoom?.let { rememberSharpPart(it, document, index, size, viewport) }
        val description = stringResource(R.string.page_of, index + 1, document.pageCount)
        val density = LocalDensity.current
        Box(
            Modifier
                .requiredSize(with(density) { viewport.width.toDp() }, with(density) { viewport.height.toDp() })
                .then(if (zoom != null) Modifier.pageZoom(zoom) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .requiredSize(with(density) { size.width.toDp() }, with(density) { size.height.toDp() })
                    .background(Color.White)
                    .drawSharpPart { sharpPart }
                    .semantics { contentDescription = description }
                    .testTag(TestTags.PRESENTATION_PAGE),
            ) {
                bitmap?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize()) }
            }
        }
    }
}

/** The largest size with this aspect ratio that fits into [width] × [height] pixels. */
private fun IntSize.fitInto(width: Int, height: Int): IntSize? {
    if (this.width <= 0 || this.height <= 0 || width <= 0 || height <= 0) return null
    val scale = minOf(width.toFloat() / this.width, height.toFloat() / this.height)
    return IntSize(
        (this.width * scale).roundToInt().coerceIn(1, width),
        (this.height * scale).roundToInt().coerceIn(1, height),
    )
}
