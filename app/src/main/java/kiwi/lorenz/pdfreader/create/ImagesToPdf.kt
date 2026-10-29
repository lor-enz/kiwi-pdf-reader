package kiwi.lorenz.pdfreader.create

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.media.ExifInterface
import android.net.Uri
import kiwi.lorenz.pdfreader.util.runCatchingCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.time.ZonedDateTime
import java.util.zip.DeflaterOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** What [createPdfFromImages] made: its pages, and how many images couldn't be read. */
data class ImagesPdf(val pageCount: Int, val skipped: Int)

/**
 * Writes the images at [uris] to [file] as a PDF, one per page, in the order given. JPEG photos
 * are embedded as they are; other formats are decoded, lossless ones (PNG, GIF, BMP) staying
 * lossless, others (HEIC, WebP) becoming JPEGs. Images that can't be read are left out; if none
 * can, nothing is written.
 */
suspend fun Context.createPdfFromImages(uris: List<Uri>, file: File): ImagesPdf = withContext(Dispatchers.IO) {
    var skipped = 0
    val pageCount = file.outputStream().buffered().use { output ->
        val writer = ImagePdfWriter(output)
        for (uri in uris) {
            ensureActive()
            val image = runCatchingCancellable { readImage(uri) }.getOrNull()
            if (image == null) skipped++ else writer.addPage(image)
        }
        if (writer.pageCount > 0) writer.finish(ZonedDateTime.now())
        writer.pageCount
    }
    if (pageCount == 0) file.delete()
    ImagesPdf(pageCount, skipped)
}

private fun Context.readImage(uri: Uri): PdfImage {
    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Cannot read $uri")
    val header = JpegHeader.read(bytes) ?: return decodeImage(bytes)
    // The camera stores photos as the sensor saw them and says in EXIF how to turn them.
    val orientation = runCatchingCancellable {
        ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
    }.getOrDefault(1)
    return PdfImage(header.width, header.height, header.components, PdfImage.Encoding.JPEG, bytes, orientation)
}

/** Already turned as EXIF says, by the decoder. Transparent areas become white, like paper. */
private fun decodeImage(bytes: ByteArray): PdfImage {
    var isLossless = false
    val decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        isLossless = info.mimeType in LOSSLESS_TYPES
        val longSide = max(info.size.width, info.size.height)
        if (longSide > MAX_DECODED_SIDE) {
            val scale = MAX_DECODED_SIDE.toDouble() / longSide
            decoder.setTargetSize(
                max(1, (info.size.width * scale).roundToInt()),
                max(1, (info.size.height * scale).roundToInt()),
            )
        }
    }
    // Also brings wide-color and HDR images to 8-bit sRGB, as the PDF expects.
    val bitmap = Bitmap.createBitmap(decoded.width, decoded.height, Bitmap.Config.ARGB_8888)
    Canvas(bitmap).apply {
        drawColor(Color.WHITE)
        drawBitmap(decoded, 0f, 0f, null)
    }
    decoded.recycle()
    try {
        return if (isLossless) bitmap.toFlate() else bitmap.toJpeg()
    } finally {
        bitmap.recycle()
    }
}

private fun Bitmap.toJpeg(): PdfImage {
    val buffer = ByteArrayOutputStream()
    compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, buffer)
    return PdfImage(width, height, 3, PdfImage.Encoding.JPEG, buffer.toByteArray())
}

private fun Bitmap.toFlate(): PdfImage {
    val buffer = ByteArrayOutputStream()
    DeflaterOutputStream(buffer).use { output ->
        val pixels = IntArray(width)
        val row = ByteArray(width * 3)
        for (y in 0 until height) {
            getPixels(pixels, 0, width, 0, y, width, 1)
            for (x in 0 until width) {
                val pixel = pixels[x]
                row[x * 3] = (pixel shr 16).toByte()
                row[x * 3 + 1] = (pixel shr 8).toByte()
                row[x * 3 + 2] = pixel.toByte()
            }
            output.write(row)
        }
    }
    return PdfImage(width, height, 3, PdfImage.Encoding.FLATE, buffer.toByteArray())
}

private val LOSSLESS_TYPES = setOf("image/png", "image/gif", "image/bmp", "image/x-ms-bmp")

/** Larger images are scaled down, so decoding one fits in memory; that's still sharp printed on A4. */
private const val MAX_DECODED_SIDE = 4096

private const val JPEG_QUALITY = 90
