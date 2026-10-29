package kiwi.lorenz.pdfreader.pdf

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

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

/** Runs [block] with random access to the file at [uri]; throws if it can't be opened. */
internal fun <T> Context.readSource(uri: Uri, block: (ByteSource) -> T): T {
    val descriptor = checkNotNull(contentResolver.openFileDescriptor(uri, "r"))
    return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
        block(ChannelSource(stream.channel, stream.channel.size()))
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
