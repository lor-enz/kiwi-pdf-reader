package kiwi.lorenz.pdfreader

import android.content.ContentResolver
import android.net.Uri

/** The header may follow some junk, but must lie within the first 1024 bytes. */
private const val HEADER_SEARCH_BYTES = 1024
private val PDF_HEADER = "%PDF-".toByteArray()

/**
 * Why [uri] couldn't be opened as a PDF. Only meaningful after opening it failed, so a readable
 * file with a PDF header counts as damaged. [openedBefore] tells whether we ever had access.
 */
fun ContentResolver.diagnoseFailure(uri: Uri, openedBefore: Boolean): FailureReason = try {
    val head = openInputStream(uri)?.use { it.readNBytes(HEADER_SEARCH_BYTES) }
    when {
        head == null -> FailureReason.UNAVAILABLE
        // An empty file is most likely an interrupted download, not some other kind of file.
        head.isEmpty() || hasPdfHeader(head) -> FailureReason.DAMAGED
        else -> FailureReason.NOT_PDF
    }
} catch (_: SecurityException) {
    // Deleting a document revokes its grants, so for a file we could read before this most
    // likely means it's gone, not that the permission was taken away.
    if (openedBefore) FailureReason.UNAVAILABLE else FailureReason.NO_PERMISSION
} catch (_: Exception) {
    // Usually a FileNotFoundException: moved, deleted, or the app holding it is gone.
    FailureReason.UNAVAILABLE
}

/**
 * Whether the recent entry of a file that failed to open stays in the list. A file that opened
 * before and still looks like a PDF most likely failed for a passing reason (e.g. the PDF service
 * crashed), and for a copy it's the only one there is. Other failures won't go away on a retry.
 */
internal fun FailureReason.keepsEntry(openedBefore: Boolean) = this == FailureReason.DAMAGED && openedBefore

internal fun hasPdfHeader(head: ByteArray): Boolean {
    val end = minOf(head.size, HEADER_SEARCH_BYTES) - PDF_HEADER.size
    return (0..end).any { start -> PDF_HEADER.indices.all { head[start + it] == PDF_HEADER[it] } }
}
