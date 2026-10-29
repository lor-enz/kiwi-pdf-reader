package kiwi.lorenz.pdfreader.create

import java.io.OutputStream
import java.time.ZonedDateTime
import java.util.Locale

/** An image as a PDF embeds it: 8 bits per component, gray or RGB. */
class PdfImage(
    val width: Int,
    val height: Int,
    /** 1 for gray, 3 for RGB. */
    val components: Int,
    val encoding: Encoding,
    val data: ByteArray,
    /** How to turn the image to show it, as EXIF says it (1 to 8, 1 for none). */
    val orientation: Int = 1,
) {
    enum class Encoding {
        /** A JPEG file as it is, so photos keep their quality and size. */
        JPEG,
        /** The samples row by row, deflated: lossless, for screenshots and the like. */
        FLATE,
    }

    /** Turned by a quarter, so width and height trade places when shown. */
    val isTransposed get() = orientation in 5..8
}

/**
 * Writes a PDF with one image per page. Each page has its image's proportions and A4's width:
 * the viewer fits the widest page to the screen, so with equal widths a tall screenshot shows as
 * wide as a photo. Images are written as they come, so only one is in memory at a time. Call
 * [finish] once all pages are added.
 */
class ImagePdfWriter(output: OutputStream) {
    private val writer = PdfObjectWriter(output, "1.4")

    /** 1 to 3 are written last, as they refer to all pages. */
    private var nextNumber = FIRST_PAGE_OBJECT
    private val pages = mutableListOf<Int>()

    val pageCount get() = pages.size

    fun addPage(image: PdfImage) {
        val (shownWidth, shownHeight) = if (image.isTransposed) image.height to image.width else image.width to image.height
        val width = PAGE_WIDTH
        val height = shownHeight * PAGE_WIDTH / shownWidth

        val imageNumber = nextNumber++
        val colorSpace = if (image.components == 1) "/DeviceGray" else "/DeviceRGB"
        val filter = when (image.encoding) {
            PdfImage.Encoding.JPEG -> "/DCTDecode"
            PdfImage.Encoding.FLATE -> "/FlateDecode"
        }
        writer.writeStream(
            imageNumber,
            "/Type /XObject /Subtype /Image /Width ${image.width} /Height ${image.height} " +
                "/ColorSpace $colorSpace /BitsPerComponent 8 /Filter $filter",
            image.data,
        )

        val contentsNumber = nextNumber++
        val matrix = placement(image.orientation, width, height).joinToString(" ") { number(it) }
        writer.writeStream(contentsNumber, "", "q $matrix cm /Im0 Do Q".toByteArray(Charsets.US_ASCII))

        val pageNumber = nextNumber++
        writer.writeObject(
            pageNumber,
            "<< /Type /Page /Parent $PAGES 0 R /MediaBox [0 0 ${number(width)} ${number(height)}] " +
                "/Resources << /XObject << /Im0 $imageNumber 0 R >> >> /Contents $contentsNumber 0 R >>",
        )
        pages += pageNumber
    }

    /** Writes what refers to the pages, and the file's index. Needs at least one page. */
    fun finish(created: ZonedDateTime) {
        check(pages.isNotEmpty()) { "A PDF needs a page" }
        writer.writeObject(PAGES, "<< /Type /Pages /Kids [${pages.joinToString(" ") { "$it 0 R" }}] /Count ${pages.size} >>")
        writer.writeObject(CATALOG, "<< /Type /Catalog /Pages $PAGES 0 R >>")
        writer.writeObject(INFO, "<< /Creator ($APP_NAME) /Producer ($APP_NAME) /CreationDate (${pdfDate(created)}) >>")
        writer.finish(nextNumber, "/Root $CATALOG 0 R /Info $INFO 0 R")
    }

    companion object {
        private const val CATALOG = 1
        private const val PAGES = 2
        private const val INFO = 3
        private const val FIRST_PAGE_OBJECT = 4

        /** A4's width, in points. */
        private const val PAGE_WIDTH = 595.28

        /**
         * The matrix that draws the image (a unit square, its first row at the top) on a page
         * of [width] × [height], turned and mirrored as EXIF's [orientation] says.
         */
        fun placement(orientation: Int, width: Double, height: Double): List<Double> = when (orientation) {
            2 -> listOf(-width, 0.0, 0.0, height, width, 0.0)
            3 -> listOf(-width, 0.0, 0.0, -height, width, height)
            4 -> listOf(width, 0.0, 0.0, -height, 0.0, height)
            5 -> listOf(0.0, -height, -width, 0.0, width, height)
            6 -> listOf(0.0, -height, width, 0.0, 0.0, height)
            7 -> listOf(0.0, height, width, 0.0, 0.0, 0.0)
            8 -> listOf(0.0, height, -width, 0.0, width, 0.0)
            else -> listOf(width, 0.0, 0.0, height, 0.0, 0.0)
        }

        private fun number(value: Double): String =
            "%.2f".format(Locale.ROOT, value).trimEnd('0').trimEnd('.').let { if (it == "-0") "0" else it }
    }
}
