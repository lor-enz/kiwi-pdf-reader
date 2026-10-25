package kiwi.lorenz.pdfreader.viewer

import android.util.Size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize
import androidx.pdf.PdfDocument
import kiwi.lorenz.pdfreader.util.runCatchingCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import me.saket.telephoto.zoomable.DoubleClickToZoomListener
import me.saket.telephoto.zoomable.HardwareShortcutsSpec
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.ZoomableContentLocation
import me.saket.telephoto.zoomable.ZoomableState
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** As far as a page zooms in: enough for footnotes and small print. */
private const val MAX_ZOOM = 5f

/** Double-tapping zooms in this far, and the next double tap back out. */
private const val DOUBLE_TAP_ZOOM = 2.5f

/** How long a zoomed page has to stay still before its visible part is rendered sharp. */
private const val SHARPEN_DELAY_MS = 150L

/** Below this, the page counts as not zoomed in (the fit isn't always exactly 1). */
private const val ZOOMED_IN = 1.01f

/**
 * The zoom of the page shown in presentation mode, for a page of [size] (pixels, as it fits the
 * screen). Not saved, so rotation and process death show the whole page again.
 */
@Composable
internal fun rememberPageZoom(size: IntSize): ZoomableState {
    lateinit var state: ZoomableState
    // Telephoto saves its state (rememberSaveable), which a missing registry turns off.
    CompositionLocalProvider(LocalSaveableStateRegistry provides null) {
        state = rememberZoomableState(
            zoomSpec = ZoomSpec(maxZoomFactor = MAX_ZOOM),
            // Keys turn pages, whether zoomed in or not.
            hardwareShortcutsSpec = HardwareShortcutsSpec.Disabled,
        )
    }
    LaunchedEffect(state, size) {
        state.setContentLocation(ZoomableContentLocation.scaledInsideAndCenterAligned(size.toSize()))
    }
    return state
}

/**
 * Pinch, double-tap, and double-tap and hold to zoom; a zoomed page pans. A drag that starts at
 * the page's edge (or on a page that isn't zoomed) turns the page instead; one that starts
 * elsewhere stops at the edge, so panning doesn't turn the page by accident.
 */
internal fun Modifier.pageZoom(state: ZoomableState): Modifier =
    zoomable(state, onDoubleClick = DoubleClickToZoomListener.cycle(DOUBLE_TAP_ZOOM))

/** The visible part of a zoomed page, rendered at that zoom; [bounds] in the page's own pixels. */
internal class SharpPart(val bitmap: ImageBitmap, val bounds: Rect, val zoom: Float)

/**
 * The page is drawn at the size it fits the screen, and stretched when zoomed in. Once it stays
 * still, the part in view is rendered again at the zoom, so it's sharp; one screen's worth,
 * as the whole page at five times the size would take far too much memory. [viewport] is the
 * size of the zoomable area, the screen.
 */
@Composable
internal fun rememberSharpPart(
    state: ZoomableState,
    document: PdfDocument,
    index: Int,
    size: IntSize,
    viewport: IntSize,
): SharpPart? {
    val part by produceState<SharpPart?>(null, state, document, index, size, viewport) {
        snapshotFlow { state.contentTransformation.takeIf { it.isSpecified }?.scale?.scaleX to state.transformedContentBounds }
            .collectLatest { (zoom, bounds) ->
                if (zoom == null || zoom < ZOOMED_IN || bounds.isEmpty) {
                    value = null
                    return@collectLatest
                }
                delay(SHARPEN_DELAY_MS)
                val visible = bounds.intersect(Rect(Offset.Zero, viewport.toSize()))
                if (visible.isEmpty) return@collectLatest
                val zoomedSize = Size((size.width * zoom).roundToInt(), (size.height * zoom).roundToInt())
                // In the zoomed page's pixels.
                val region = android.graphics.Rect(
                    floor(visible.left - bounds.left).toInt().coerceAtLeast(0),
                    floor(visible.top - bounds.top).toInt().coerceAtLeast(0),
                    ceil(visible.right - bounds.left).toInt().coerceAtMost(zoomedSize.width),
                    ceil(visible.bottom - bounds.top).toInt().coerceAtMost(zoomedSize.height),
                )
                if (region.isEmpty) return@collectLatest
                runCatchingCancellable {
                    document.getPageBitmapSource(index).use { it.getBitmap(zoomedSize, region) }
                }.onSuccess {
                    val inPage = Rect(region.left / zoom, region.top / zoom, region.right / zoom, region.bottom / zoom)
                    value = SharpPart(it.asImageBitmap(), inPage, zoom)
                }
            }
    }
    return part
}

/**
 * Draws [part] over the stretched page. Scaled down by its zoom, and up again by the page's, so
 * its pixels land exactly on the screen's.
 */
internal fun Modifier.drawSharpPart(part: () -> SharpPart?): Modifier = drawWithContent {
    drawContent()
    val sharp = part() ?: return@drawWithContent
    translate(sharp.bounds.left, sharp.bounds.top) {
        scale(1 / sharp.zoom, pivot = Offset.Zero) {
            drawImage(sharp.bitmap)
        }
    }
}
