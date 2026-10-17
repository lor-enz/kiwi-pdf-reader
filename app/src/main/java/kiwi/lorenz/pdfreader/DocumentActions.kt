package kiwi.lorenz.pdfreader

import android.content.ContentResolver
import android.content.Context
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.print.pdf.PrintedPdfDocument
import android.util.Size
import androidx.pdf.PdfDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import kotlin.concurrent.thread
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Opens the system print dialog for the PDF. Needs an activity context.
 *
 * The print system reads the file itself, which fails for password-protected ones, so their
 * pages are rendered here instead (as images, which is why other files are passed through).
 * That happens in [scope], which should live as long as the document, not the activity.
 */
fun Context.printPdf(document: PdfDocument, name: String, isProtected: Boolean, scope: CoroutineScope) {
    val printManager = getSystemService(PrintManager::class.java)
    val adapter = if (isProtected) {
        RenderedPrintAdapter(this, document, pdfFileName(name), scope)
    } else {
        PassThroughPrintAdapter(contentResolver, document.uri, pdfFileName(name))
    }
    printManager.print(name, adapter, null)
}

/** Passes the PDF through unchanged; the print system renders it itself. */
private class PassThroughPrintAdapter(
    private val resolver: ContentResolver,
    private val uri: Uri,
    private val fileName: String,
) : PrintDocumentAdapter() {
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal,
        callback: LayoutResultCallback,
        extras: Bundle?,
    ) {
        if (cancellationSignal.isCanceled) {
            callback.onLayoutCancelled()
            return
        }
        val info = PrintDocumentInfo.Builder(fileName)
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .build()
        // The content doesn't depend on paper size or margins, so it only changes once.
        callback.onLayoutFinished(info, oldAttributes == null)
    }

    override fun onWrite(
        pages: Array<out PageRange>,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal,
        callback: WriteResultCallback,
    ) {
        thread(name = "print-copy") {
            val error = try {
                resolver.openInputStream(uri)!!.use { input ->
                    // The print system owns and closes the destination.
                    input.copyTo(FileOutputStream(destination.fileDescriptor))
                }
                null
            } catch (e: Exception) {
                e
            }
            mainHandler.post {
                when {
                    cancellationSignal.isCanceled -> callback.onWriteCancelled()
                    error != null -> callback.onWriteFailed(error.message)
                    else -> callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                }
            }
        }
    }
}

/** Draws each page as an image onto the chosen paper, fitted and centered. */
private class RenderedPrintAdapter(
    private val context: Context,
    private val document: PdfDocument,
    private val fileName: String,
    private val scope: CoroutineScope,
) : PrintDocumentAdapter() {
    private var attributes: PrintAttributes? = null
    private var writeJob: Job? = null

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal,
        callback: LayoutResultCallback,
        extras: Bundle?,
    ) {
        if (cancellationSignal.isCanceled) {
            callback.onLayoutCancelled()
            return
        }
        attributes = newAttributes
        val info = PrintDocumentInfo.Builder(fileName)
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(document.pageCount)
            .build()
        // Paper size, orientation and margins change how the pages are drawn.
        callback.onLayoutFinished(info, newAttributes != oldAttributes)
    }

    override fun onWrite(
        pages: Array<out PageRange>,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal,
        callback: WriteResultCallback,
    ) {
        val attributes = attributes ?: return callback.onWriteFailed(null)
        writeJob?.cancel()
        val job = scope.launch {
            try {
                write(attributes, destination)
                callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: CancellationException) {
                callback.onWriteCancelled()
                throw e
            } catch (e: Exception) {
                callback.onWriteFailed(e.message)
            }
        }
        writeJob = job
        cancellationSignal.setOnCancelListener { job.cancel() }
    }

    override fun onFinish() {
        writeJob?.cancel()
    }

    private suspend fun write(attributes: PrintAttributes, destination: ParcelFileDescriptor) {
        val printed = PrintedPdfDocument(context, attributes)
        try {
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            for (pageNum in 0 until document.pageCount) {
                val pageInfo = document.getPageInfo(pageNum)
                val page = printed.startPage(pageNum)
                // Points (1/72 inch), like the PDF page size.
                val area = RectF(page.info.contentRect)
                val scale = min(area.width() / pageInfo.width, area.height() / pageInfo.height)
                val width = pageInfo.width * scale
                val height = pageInfo.height * scale
                val pixelsPerPoint = PRINT_DPI / POINTS_PER_INCH
                val bitmap = document.getPageBitmapSource(pageNum).use {
                    it.getBitmap(Size((width * pixelsPerPoint).roundToInt(), (height * pixelsPerPoint).roundToInt()))
                }
                val left = area.left + (area.width() - width) / 2
                val top = area.top + (area.height() - height) / 2
                page.canvas.drawBitmap(bitmap, null, RectF(left, top, left + width, top + height), paint)
                bitmap.recycle()
                printed.finishPage(page)
            }
            withContext(Dispatchers.IO) {
                // The print system owns and closes the destination.
                printed.writeTo(FileOutputStream(destination.fileDescriptor))
            }
        } finally {
            printed.close()
        }
    }
}

/** A safe file name ending in ".pdf". */
private fun pdfFileName(name: String): String {
    val safe = name.replace(Regex("""[/\\:*?"<>|\u0000-\u001f]"""), "_").trim().ifEmpty { "document" }
    return if (safe.endsWith(".pdf", ignoreCase = true)) safe else "$safe.pdf"
}

/** Sharp enough for text, small enough to keep one page's bitmap in memory (~15 MB on A4). */
private const val PRINT_DPI = 200f
private const val POINTS_PER_INCH = 72f
