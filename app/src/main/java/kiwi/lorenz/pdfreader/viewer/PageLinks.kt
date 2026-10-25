package kiwi.lorenz.pdfreader.viewer

import android.graphics.RectF
import android.net.Uri
import androidx.pdf.PdfDocument
import kiwi.lorenz.pdfreader.util.runCatchingCancellable

/** Where a link on a page leads. */
internal sealed interface PageLink {
    /** Another page of the document. */
    data class ToPage(val page: Int) : PageLink

    /** A website, mail address or phone number; whether it may be opened is checked on opening. */
    data class ToUri(val uri: Uri) : PageLink
}

/**
 * The link at ([x], [y]) on [page], in points from the top left. Links are often thin lines of
 * text, so one within [tolerance] points counts too, unless the point is right on another.
 * Links with a bare `/Dest` aren't found: the library doesn't report them (as of beta01).
 */
internal suspend fun PdfDocument.linkAt(page: Int, x: Float, y: Float, tolerance: Float): PageLink? {
    val links = runCatchingCancellable { getPageLinks(page) }.getOrNull() ?: return null
    for (margin in listOf(0f, tolerance)) {
        fun hits(bounds: List<RectF>) = bounds.any { RectF(it).apply { inset(-margin, -margin) }.contains(x, y) }
        links.gotoLinks.firstOrNull { hits(it.bounds) }?.let { return PageLink.ToPage(it.destination.pageNumber) }
        links.externalLinks.firstOrNull { hits(it.bounds) }?.let { return PageLink.ToUri(it.uri) }
    }
    return null
}
