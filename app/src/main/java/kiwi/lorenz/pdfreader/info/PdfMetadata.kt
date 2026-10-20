package kiwi.lorenz.pdfreader.info

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * What a PDF says about itself in its info dictionary, the size of its first page and its table
 * of contents. `androidx.pdf` doesn't read any of them, see [PdfMetadataReader].
 */
data class PdfMetadata(
    val title: String? = null,
    val author: String? = null,
    val subject: String? = null,
    /** The app the document was made in, e.g. a word processor. */
    val creator: String? = null,
    /** The app that turned it into a PDF. */
    val producer: String? = null,
    val created: Instant? = null,
    val modified: Instant? = null,
    val pageCount: Int? = null,
    /** The first page as shown, in points (1/72 inch), rotation applied. */
    val pageSize: PageSize? = null,
    /** Encrypted, and neither the password given nor the empty one opens it: the strings are left out. */
    val isLocked: Boolean = false,
    /** The table of contents, see [PdfMetadataReader.read]. */
    val outline: List<OutlineItem> = emptyList(),
)

/** An entry in a document's table of contents, which PDFs call its outline. */
data class OutlineItem(
    val title: String,
    /** Where it leads, counted from 0; null if nowhere in this document. */
    val page: Int?,
    /** How far below the top of [page] it leads, in points; null for the top. */
    val top: Float?,
    /** Shows its [children] at first, as the author left it. */
    val isOpen: Boolean,
    val children: List<OutlineItem>,
)

data class PageSize(val width: Double, val height: Double)

/** Random access to a file's bytes. */
interface ByteSource {
    val size: Long

    /** Reads up to [length] bytes at [offset]; fewer only at the end of the file. */
    fun read(offset: Long, length: Int): ByteArray
}

class ByteArraySource(private val bytes: ByteArray) : ByteSource {
    override val size get() = bytes.size.toLong()
    override fun read(offset: Long, length: Int): ByteArray {
        val start = offset.coerceIn(0, size).toInt()
        return bytes.copyOfRange(start, minOf(bytes.size, start + length))
    }
}

/** Parses a PDF date, `D:YYYYMMDDHHmmSSOHH'mm'`, where everything after the year is optional. */
fun parsePdfDate(value: String, zone: ZoneId = ZoneId.systemDefault()): Instant? {
    val match = PDF_DATE.matchEntire(value.trim()) ?: return null
    val g = match.groupValues
    fun part(index: Int, default: Int) = g[index].takeIf { it.isNotEmpty() }?.toInt() ?: default
    return try {
        val local = LocalDateTime.of(part(1, 0), part(2, 1), part(3, 1), part(4, 0), part(5, 0), part(6, 0))
        val offset = when (g[7]) {
            "" -> return local.atZone(zone).toInstant()
            "Z" -> ZoneOffset.UTC
            else -> {
                val seconds = part(8, 0) * 3600 + part(9, 0) * 60
                ZoneOffset.ofTotalSeconds(if (g[7] == "-") -seconds else seconds)
            }
        }
        local.toInstant(offset)
    } catch (_: Exception) {
        null
    }
}

// Some writers leave out the "D:", or put apostrophes in odd places.
private val PDF_DATE = Regex(
    """(?:D:)?(\d{4})(\d{2})?(\d{2})?(\d{2})?(\d{2})?(\d{2})?(?:([+\-Z])(?:(\d{2})'?(?:(\d{2})'?)?)?)?.*"""
)
