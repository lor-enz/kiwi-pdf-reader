package kiwi.lorenz.pdfreader.pdf

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the document info sheet shows. [sizeBytes] is null if the file couldn't be read. */
data class DocumentInfo(val metadata: PdfMetadata, val sizeBytes: Long?)

/**
 * Never fails: a file that can't be read gives empty info. [password] is the one to open a
 * protected file; without it, its title and such are left out. See [PdfMetadataReader.read] for
 * [withOutline].
 */
suspend fun Context.readDocumentInfo(
    uri: Uri,
    password: String? = null,
    withOutline: Boolean = false,
): DocumentInfo = withContext(Dispatchers.IO) {
    try {
        readSource(uri) { source -> DocumentInfo(PdfMetadataReader(source, password).read(withOutline), source.size) }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        DocumentInfo(PdfMetadata(), null)
    }
}
