package kiwi.lorenz.pdfreader.viewer

import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
}

/**
 * The overlay's state. It hides [OverlayState.timeout] after it showed, or after the last swipe,
 * as long as [isScrolling] is false: while swiping, the page number changes in front of the user.
 */
@Composable
internal fun rememberOverlayState(isScrolling: Boolean): OverlayState {
    val state = remember { OverlayState() }
    state.isTouchExploring = rememberIsTouchExploring()
    val accessibilityManager = LocalAccessibilityManager.current
    LaunchedEffect(state, state.isVisible, state.shownCount, isScrolling) {
        if (!state.isVisible || state.isTouchExploring || isScrolling) return@LaunchedEffect
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
 * document's [title] (if it has one) and [fileName] at the top, the page number at the bottom,
 * each on a scrim that fades into the page. Taps beside its controls go through to the page.
 */
@OptIn(ExperimentalLayoutApi::class) // systemBarsIgnoringVisibility
@Composable
internal fun PresentationOverlay(
    isVisible: Boolean,
    title: String?,
    fileName: String,
    page: Int,
    pageCount: Int,
    onExit: () -> Unit,
) {
    AnimatedVisibility(
        visible = isVisible,
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
            val description = stringResource(R.string.page_of, page + 1, pageCount)
            Row(
                horizontalArrangement = Arrangement.Start,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .scrim(isTop = false)
                    .windowInsetsPadding(insets.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                    .padding(start = 24.dp, end = 24.dp, top = ScrimFade, bottom = 16.dp),
            ) {
                Text(
                    text = stringResource(R.string.page_counter, page + 1, pageCount),
                    style = MaterialTheme.typography.titleMedium,
                    color = NightText,
                    modifier = Modifier
                        .semantics { contentDescription = description }
                        .testTag(TestTags.PRESENTATION_PAGE_NUMBER),
                )
            }
        }
    }
}
