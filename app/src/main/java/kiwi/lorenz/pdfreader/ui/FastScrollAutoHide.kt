package kiwi.lorenz.pdfreader.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.pdf.view.PdfView

/**
 * Lets the fast scroller fade out unless a screen reader is running.
 *
 * PdfView keeps the scroller visible whenever any accessibility service is enabled, which
 * includes password managers and similar apps many people run. Only touch exploration
 * (TalkBack and the like) needs it permanently visible. PdfView resets the visibility when a
 * document loads and when the accessibility state changes, so it's applied again after both.
 * The setting is restricted API and the view isn't exposed by `PdfViewer`; drop this once
 * `androidx.pdf` makes that distinction itself.
 *
 * @param contentLoaded changes once the document has loaded, re-applying the setting.
 */
@Composable
fun FastScrollAutoHide(contentLoaded: Boolean) {
    val root = LocalView.current
    DisposableEffect(root, contentLoaded) {
        val accessibilityManager =
            root.context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        // Posted, so it runs after PdfView's own listener has reset the visibility.
        val apply = Runnable { root.findPdfView()?.allowAutoHide(accessibilityManager) }
        val stateListener = AccessibilityManager.AccessibilityStateChangeListener { root.post(apply) }
        val touchListener = AccessibilityManager.TouchExplorationStateChangeListener { root.post(apply) }
        accessibilityManager.addAccessibilityStateChangeListener(stateListener)
        accessibilityManager.addTouchExplorationStateChangeListener(touchListener)
        if (contentLoaded) root.post(apply)
        onDispose {
            root.removeCallbacks(apply)
            accessibilityManager.removeAccessibilityStateChangeListener(stateListener)
            accessibilityManager.removeTouchExplorationStateChangeListener(touchListener)
        }
    }
}

@SuppressLint("RestrictedApi")
private fun PdfView.allowAutoHide(accessibilityManager: AccessibilityManager) {
    if (accessibilityManager.isTouchExplorationEnabled) return
    // Setting it shows the scroller once, so only touch it when the library forced it visible.
    if (fastScrollVisibility == PdfView.FastScrollVisibility.ALWAYS_SHOW) {
        fastScrollVisibility = PdfView.FastScrollVisibility.AUTO_HIDE
    }
}

private fun View.findPdfView(): PdfView? = when (this) {
    is PdfView -> this
    is ViewGroup -> (0 until childCount).firstNotNullOfOrNull { getChildAt(it).findPdfView() }
    else -> null
}
