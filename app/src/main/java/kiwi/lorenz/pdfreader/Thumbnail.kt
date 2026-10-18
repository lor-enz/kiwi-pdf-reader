package kiwi.lorenz.pdfreader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.util.Size
import androidx.core.graphics.createBitmap
import androidx.pdf.PdfDocument
import kotlin.math.roundToInt

private const val THUMBNAIL_WIDTH_PX = 240

/** The first page as a small picture for the recent files list, on white like paper. */
suspend fun PdfDocument.renderThumbnail(): Bitmap {
    val page = getPageInfo(0)
    val width = THUMBNAIL_WIDTH_PX
    val height = (width * page.height.toFloat() / page.width).roundToInt().coerceIn(1, 3 * width)
    val rendered = getPageBitmapSource(0).use { it.getBitmap(Size(width, height)) }
    // Pages without a background render transparent.
    return createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
        Canvas(this).apply {
            drawColor(Color.WHITE)
            drawBitmap(rendered, null, Rect(0, 0, width, height), null)
        }
    }
}
