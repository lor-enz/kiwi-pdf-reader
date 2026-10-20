package kiwi.lorenz.pdfreader.viewer

import android.annotation.SuppressLint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.pdf.view.PdfView

/**
 * The pages whose bitmaps the document view has rendered, so the viewer can wait for the visible
 * ones instead of showing white pages. `PdfViewer` only reports the first bitmap of all
 * (`onFirstContentLoad`); the per-page listener is restricted API on the view it hosts.
 */
@SuppressLint("RestrictedApi")
@Composable
internal fun rememberReadyPages(): State<Set<Int>> {
    val root = LocalView.current
    val readyPages = remember { mutableStateOf(emptySet<Int>()) }
    DisposableEffect(root) {
        val listener = object : PdfView.OnBitmapUpdatedListener {
            override fun onBitmapFetched(pageNum: Int) {
                readyPages.value += pageNum
            }

            override fun onBitmapCleared(pageNum: Int) {
                readyPages.value -= pageNum
            }
        }
        // Posted, so PdfViewer has created its view. The first bitmap takes longer than that.
        val attach = Runnable { root.findPdfView()?.setOnBitmapUpdatedListener(listener) }
        root.post(attach)
        onDispose {
            root.removeCallbacks(attach)
            root.findPdfView()?.setOnBitmapUpdatedListener(null)
        }
    }
    return readyPages
}
