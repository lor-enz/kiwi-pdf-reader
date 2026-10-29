package kiwi.lorenz.pdfreader.create

import java.io.OutputStream
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Writes a PDF object by object, as they come, and at the end the cross-reference table that
 * finds them. Objects are numbered by the caller and may be written in any order.
 */
internal class PdfObjectWriter(output: OutputStream, version: String) {
    private val out = CountingOutputStream(output)

    /** Byte offsets by object number. */
    private val offsets = mutableMapOf<Int, Long>()

    init {
        // The comment with bytes above 127 marks the file as binary for tools that guess.
        out.write("%PDF-$version\n%".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte()))
    }

    fun writeObject(number: Int, value: String) {
        offsets[number] = out.count
        out.write("$number 0 obj\n$value\nendobj\n".toByteArray(Charsets.US_ASCII))
    }

    fun writeStream(number: Int, entries: String, data: ByteArray) =
        writeStream(number, entries, data.size.toLong()) { it.write(data) }

    /** [entries] of the stream's dictionary, without its length; [writeData] writes [length] bytes. */
    fun writeStream(number: Int, entries: String, length: Long, writeData: (OutputStream) -> Unit) {
        offsets[number] = out.count
        val dictionary = listOf(entries, "/Length $length").filter { it.isNotEmpty() }.joinToString(" ")
        out.write("$number 0 obj\n<< $dictionary >>\nstream\n".toByteArray(Charsets.US_ASCII))
        val start = out.count
        writeData(out)
        check(out.count - start == length) { "Stream $number isn't $length bytes long" }
        out.write("\nendstream\nendobj\n".toByteArray(Charsets.US_ASCII))
    }

    /**
     * Writes the cross-reference table for objects 1 until [size] and the trailer with
     * [entries] (besides its size). Numbers that weren't written are free.
     */
    fun finish(size: Int, entries: String) {
        val xref = out.count
        val table = StringBuilder("xref\n0 $size\n0000000000 65535 f \n")
        for (number in 1 until size) {
            val offset = offsets[number]
            table.append(if (offset == null) "0000000000 65535 f \n" else "%010d 00000 n \n".format(Locale.ROOT, offset))
        }
        table.append("trailer\n<< /Size $size $entries >>\nstartxref\n$xref\n%%EOF\n")
        out.write(table.toString().toByteArray(Charsets.US_ASCII))
        out.flush()
    }

    private class CountingOutputStream(private val output: OutputStream) : OutputStream() {
        var count = 0L
            private set

        override fun write(b: Int) {
            output.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            output.write(b, off, len)
            count += len
        }

        override fun flush() = output.flush()
    }
}

internal fun pdfDate(time: ZonedDateTime): String {
    val offset = time.offset.totalSeconds
    val zone = if (offset == 0) {
        "Z"
    } else {
        val sign = if (offset < 0) "-" else "+"
        "%s%02d'%02d'".format(Locale.ROOT, sign, Math.abs(offset) / 3600, Math.abs(offset) % 3600 / 60)
    }
    return "D:" + time.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss", Locale.ROOT)) + zone
}

internal const val APP_NAME = "PDF Penguin"
