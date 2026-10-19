package kiwi.lorenz.pdfreader.info

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** What the document info sheet shows. [sizeBytes] is null if the file couldn't be read. */
data class DocumentInfo(val metadata: PdfMetadata, val sizeBytes: Long?)

/** Never fails: a file that can't be read gives empty info. */
suspend fun Context.readDocumentInfo(uri: Uri): DocumentInfo = withContext(Dispatchers.IO) {
    try {
        val descriptor = checkNotNull(contentResolver.openFileDescriptor(uri, "r"))
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
            val channel = stream.channel
            val size = channel.size()
            DocumentInfo(PdfMetadataReader(ChannelSource(channel, size)).read(), size)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        DocumentInfo(PdfMetadata(), null)
    }
}

private class ChannelSource(private val channel: FileChannel, override val size: Long) : ByteSource {
    override fun read(offset: Long, length: Int): ByteArray {
        val buffer = ByteBuffer.allocate(minOf(length.toLong(), maxOf(0, size - offset)).toInt())
        while (buffer.hasRemaining()) {
            if (channel.read(buffer, offset + buffer.position()) < 0) break
        }
        return buffer.array().copyOf(buffer.position())
    }
}
