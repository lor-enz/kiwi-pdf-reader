package kiwi.lorenz.pdfreader.viewer

import android.content.Context
import android.view.accessibility.AccessibilityManager
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.stateDescription
import kiwi.lorenz.pdfreader.ui.theme.PenguinBlue
import kotlin.math.roundToInt
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kiwi.lorenz.pdfreader.R
import kiwi.lorenz.pdfreader.ui.TestTags
import kiwi.lorenz.pdfreader.ui.theme.NightBackground
import kiwi.lorenz.pdfreader.ui.theme.NightSlateGrey
import kiwi.lorenz.pdfreader.ui.theme.NightText
import kotlinx.coroutines.delay

/** How long the overlay stays after presentation mode starts: long enough to notice it's there. */
private const val ENTRY_TIMEOUT_MS = 1_000L

/** How long the overlay stays after a tap or a swipe, unless the user asked for longer. */
private const val TIMEOUT_MS = 3_000L

/** How dark the scrim behind the overlay's rows is at the screen's edge. */
private const val SCRIM_ALPHA = 0.9f

/** The slider's track beyond the thumb: visible on the scrim, without competing with the page. */
private const val INACTIVE_TRACK_ALPHA = 0.4f

/** The slider's track: slim, so it doesn't compete with the page. */
private val TrackHeight = 4.dp

/** How far the scrim reaches beyond the row's text, toward the page. */
private val ScrimFade = 160.dp

/**
 * The scrim behind a row, at the top of the screen if [isTop]: darkest at the screen's edge and
 * fading out toward the page, gently at both ends (smoothstep), so it has no visible edge. Tall
 * enough that the text, near the edge, stays readable on a white page.
 */
private fun Modifier.scrim(isTop: Boolean): Modifier = drawBehind {
    val stops = (0..8).map { step ->
        val t = step / 8f
        val alpha = SCRIM_ALPHA * (1 - t * t * (3 - 2 * t))
        (if (isTop) t else 1 - t) to NightBackground.copy(alpha = alpha)
    }.sortedBy { it.first }
    drawRect(Brush.verticalGradient(*stops.toTypedArray()))
}

/**
 * Whether presentation mode's overlay shows. It comes up for a moment when the mode starts and
 * hides on its own a while after the last tap, or right away when a key turns the page. With
 * touch exploration on, it always shows: a screen reader can't tap to bring it up.
 */
@Stable
internal class OverlayState {
    private var isShown by mutableStateOf(true)
    internal var timeout by mutableStateOf(ENTRY_TIMEOUT_MS)
        private set
    /** Counts the taps that showed it, so each starts the timeout anew. */
    internal var shownCount by mutableIntStateOf(0)
        private set
    internal var isTouchExploring by mutableStateOf(false)
    /** Whether the slider is touched: the overlay stays until it's let go. */
    internal var isHeld by mutableStateOf(false)
        private set

    val isVisible get() = isShown || isTouchExploring

    fun toggle() {
        if (isShown) {
            isShown = false
        } else {
            isShown = true
            timeout = TIMEOUT_MS
            shownCount++
        }
    }

    fun hide() {
        isShown = false
    }

    /**
     * The slider is touched, or let go. Touched while fading out (just after the mode started),
     * the overlay comes back; once let go, it stays as long as after a tap.
     */
    fun hold(isHeld: Boolean) {
        this.isHeld = isHeld
        if (isHeld) {
            isShown = true
            timeout = TIMEOUT_MS
        }
    }
}

/**
 * The overlay's state. It hides [OverlayState.timeout] after it showed, or after the last swipe
 * or slide, as long as neither [isScrolling] nor the slider is held: meanwhile the page number
 * changes in front of the user.
 */
@Composable
internal fun rememberOverlayState(isScrolling: Boolean): OverlayState {
    val state = remember { OverlayState() }
    state.isTouchExploring = rememberIsTouchExploring()
    val accessibilityManager = LocalAccessibilityManager.current
    LaunchedEffect(state, state.isVisible, state.shownCount, isScrolling, state.isHeld) {
        if (!state.isVisible || state.isTouchExploring || isScrolling || state.isHeld) return@LaunchedEffect
        val timeout = accessibilityManager?.calculateRecommendedTimeoutMillis(
            state.timeout,
            containsIcons = true,
            containsText = true,
            containsControls = true,
        ) ?: state.timeout
        delay(timeout)
        state.hide()
    }
    return state
}

/** Whether touch exploration (TalkBack and the like) is on, kept up to date. */
@Composable
private fun rememberIsTouchExploring(): Boolean {
    val context = LocalContext.current
    val accessibilityManager = remember(context) {
        context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    }
    var isEnabled by remember(accessibilityManager) { mutableStateOf(accessibilityManager.isTouchExplorationEnabled) }
    DisposableEffect(accessibilityManager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { isEnabled = it }
        accessibilityManager.addTouchExplorationStateChangeListener(listener)
        onDispose { accessibilityManager.removeTouchExplorationStateChangeListener(listener) }
    }
    return isEnabled
}

/**
 * Presentation mode's overlay, laid over the page so it doesn't move: a way out and the
 * document's [title] (if it has one) and [fileName] at the top, the page number and a slider to
 * go to another page ([onPageSelected], while sliding) at the bottom, each on a scrim that fades
 * into the page. Taps beside its controls go through to the page.
 */
@OptIn(ExperimentalLayoutApi::class) // systemBarsIgnoringVisibility
@Composable
internal fun PresentationOverlay(
    state: OverlayState,
    title: String?,
    fileName: String,
    page: Int,
    pageCount: Int,
    onPageSelected: (Int) -> Unit,
    onExit: () -> Unit,
) {
    AnimatedVisibility(
        visible = state.isVisible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.testTag(TestTags.PRESENTATION_OVERLAY),
    ) {
        // Padded as if the system bars were there, so nothing moves while they come and go.
        val insets = WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout)
        Box(Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .scrim(isTop = true)
                    .windowInsetsPadding(insets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = ScrimFade),
            ) {
                IconButton(
                    onClick = onExit,
                    colors = IconButtonDefaults.iconButtonColors(contentColor = NightText),
                    modifier = Modifier.testTag(TestTags.PRESENTATION_EXIT),
                ) {
                    Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back))
                }
                // As in the viewer's top bar: the title, if there is one, with the file name below.
                Column(Modifier.padding(start = 12.dp)) {
                    Text(
                        text = title ?: fileName,
                        style = if (title != null) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                        color = NightText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag(TestTags.PRESENTATION_TITLE),
                    )
                    if (title != null) {
                        Text(
                            text = fileName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = NightSlateGrey,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag(TestTags.PRESENTATION_FILE_NAME),
                        )
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .scrim(isTop = false)
                    .windowInsetsPadding(insets.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                    // Away from the edges, where a drag would be the system's back gesture.
                    .padding(start = 24.dp, end = 24.dp, top = ScrimFade, bottom = 8.dp),
            ) {
                PageNumber(page, pageCount)
                if (pageCount > 1) {
                    PageSlider(
                        page = page,
                        pageCount = pageCount,
                        onHeldChange = state::hold,
                        onPageSelected = onPageSelected,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 16.dp),
                    )
                }
            }
        }
    }
}

/**
 * "3 / 11", as wide as the widest number it can show and with digits of the same width, so the
 * slider beside it doesn't change its length.
 */
@Composable
private fun PageNumber(page: Int, pageCount: Int) {
    val style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum")
    val description = stringResource(R.string.page_of, page + 1, pageCount)
    Box(contentAlignment = Alignment.CenterEnd) {
        Text(
            text = stringResource(R.string.page_counter, pageCount, pageCount),
            style = style,
            modifier = Modifier
                .alpha(0f)
                .clearAndSetSemantics {},
        )
        Text(
            text = stringResource(R.string.page_counter, page + 1, pageCount),
            style = style,
            color = NightText,
            modifier = Modifier
                .semantics { contentDescription = description }
                .testTag(TestTags.PRESENTATION_PAGE_NUMBER),
        )
    }
}

/**
 * Goes to another page: while it's dragged, the pages turn with it ([onPageSelected]), each with
 * a light tick, so a page is found by looking at it. Follows [page] when it's turned otherwise.
 * It never takes the focus, or the keys of a presentation clicker would move it instead of
 * turning the page. [onHeldChange] reports whether it's touched.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PageSlider(
    page: Int,
    pageCount: Int,
    onHeldChange: (Boolean) -> Unit,
    onPageSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // A step per page, so screen readers move by one page; the ticks aren't drawn.
    val state = remember(pageCount) {
        SliderState(page.toFloat(), steps = pageCount - 2, valueRange = 0f..(pageCount - 1).toFloat())
    }
    val interactionSource = remember { MutableInteractionSource() }
    val isDragged by interactionSource.collectIsDraggedAsState()
    val isPressed by interactionSource.collectIsPressedAsState()
    val isHeld = isDragged || isPressed
    LaunchedEffect(isHeld) { onHeldChange(isHeld) }
    SideEffect {
        if (!isHeld && state.value != page.toFloat()) state.value = page.toFloat()
    }
    val view = LocalView.current
    state.onValueChange = { value ->
        val previous = state.value.roundToInt()
        state.value = value
        val target = value.roundToInt()
        if (target != previous) {
            view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK)
            onPageSelected(target)
        }
    }
    val colors = SliderDefaults.colors(
        thumbColor = PenguinBlue,
        activeTrackColor = PenguinBlue,
        inactiveTrackColor = NightSlateGrey.copy(alpha = INACTIVE_TRACK_ALPHA),
    )
    val stateDescription = stringResource(R.string.page_of, state.value.roundToInt() + 1, pageCount)
    Slider(
        state = state,
        colors = colors,
        interactionSource = interactionSource,
        track = {
            SliderDefaults.Track(
                sliderState = it,
                colors = colors,
                modifier = Modifier.height(TrackHeight),
                drawStopIndicator = null,
                drawTick = { _, _ -> },
            )
        },
        modifier = modifier
            .focusProperties { canFocus = false }
            .semantics { this.stateDescription = stateDescription }
            .testTag(TestTags.PRESENTATION_SLIDER),
    )
}
