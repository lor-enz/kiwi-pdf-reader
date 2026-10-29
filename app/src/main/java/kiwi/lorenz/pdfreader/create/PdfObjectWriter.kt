package kiwi.lorenz.pdfreader.create

import kiwi.lorenz.pdfreader.pdf.PdfName
import kiwi.lorenz.pdfreader.pdf.PdfRef
import kiwi.lorenz.pdfreader.pdf.PdfString
import java.io.OutputStream
import java.math.BigDecimal
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Writes a PDF object by object, as they come, and at the end the cross-reference table that
 * finds them. Objects are numbered by the caller and may be written in any order. Values are
 * what [kiwi.lorenz.pdfreader.pdf.PdfFile] reads: numbers, [Boolean], [PdfName], [PdfString],
 * [PdfRef], [List] for arrays and [Map] keyed by name for dictionaries; anything else is null.
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

    fun writeObject(number: Int, value: Any?) {
        offsets[number] = out.count
        out.write("$number 0 obj\n${serialize(value)}\nendobj\n".toByteArray(Charsets.US_ASCII))
    }

    fun writeStream(number: Int, dict: Map<String, Any?>, data: ByteArray) =
        writeStream(number, dict, data.size.toLong()) { it.write(data) }

    /** [dict] is the stream's dictionary without its length; [writeData] writes [length] bytes. */
    fun writeStream(number: Int, dict: Map<String, Any?>, length: Long, writeData: (OutputStream) -> Unit) {
        offsets[number] = out.count
        out.write("$number 0 obj\n${serialize(dict - "Length" + ("Length" to length))}\nstream\n".toByteArray(Charsets.US_ASCII))
        val start = out.count
        writeData(out)
        check(out.count - start == length) { "Stream $number isn't $length bytes long" }
        out.write("\nendstream\nendobj\n".toByteArray(Charsets.US_ASCII))
    }

    /** The document information dictionary of a PDF the app made at [created]. */
    fun writeInfo(number: Int, created: ZonedDateTime) = writeObject(
        number,
        mapOf(
            "Creator" to textString(APP_NAME),
            "Producer" to textString(APP_NAME),
            "CreationDate" to textString(pdfDate(created)),
        ),
    )

    /**
     * Writes the cross-reference table for objects 1 until [size] and the trailer, which points
     * to the catalog ([root]) and the document information dictionary ([info]). Numbers that
     * weren't written are free.
     */
    fun finish(size: Int, root: Int, info: Int) {
        val xref = out.count
        val table = StringBuilder("xref\n0 $size\n0000000000 65535 f \n")
        for (number in 1 until size) {
            val offset = offsets[number]
            table.append(if (offset == null) "0000000000 65535 f \n" else "%010d 00000 n \n".format(Locale.ROOT, offset))
        }
        val trailer = mapOf("Size" to size, "Root" to PdfRef(root, 0), "Info" to PdfRef(info, 0))
        table.append("trailer\n${serialize(trailer)}\nstartxref\n$xref\n%%EOF\n")
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

/** A text string: PDFDocEncoding's ASCII part, or UTF-16 for anything else. */
internal fun textString(text: String): PdfString =
    if (text.all { it in ' '..'~' }) {
        PdfString(text.toByteArray(Charsets.US_ASCII))
    } else {
        PdfString(byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + text.toByteArray(Charsets.UTF_16BE))
    }

private fun pdfDate(time: ZonedDateTime): String {
    val offset = time.offset.totalSeconds
    val zone = if (offset == 0) {
        "Z"
    } else {
        val sign = if (offset < 0) "-" else "+"
        "%s%02d'%02d'".format(Locale.ROOT, sign, Math.abs(offset) / 3600, Math.abs(offset) % 3600 / 60)
    }
    return "D:" + time.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss", Locale.ROOT)) + zone
}

private const val APP_NAME = "PDF Penguin"

/** [value] as written in a PDF; strings in hex, which needs no escaping. */
private fun serialize(value: Any?): String = StringBuilder().also { serialize(value, it) }.toString()

private fun serialize(value: Any?, out: StringBuilder) {
    when (value) {
        null -> out.append("null")
        is Boolean, is Long, is Int -> out.append(value)
        is Double -> out.append(if (value.isFinite()) BigDecimal.valueOf(value).stripTrailingZeros().toPlainString() else "0")
        is PdfName -> name(value.value, out)
        is PdfString -> {
            out.append('<')
            value.bytes.forEach { out.append(HEX[(it.toInt() shr 4) and 0xF]).append(HEX[it.toInt() and 0xF]) }
            out.append('>')
        }
        is PdfRef -> out.append(value.number).append(' ').append(value.generation).append(" R")
        is List<*> -> {
            out.append('[')
            value.forEachIndexed { index, item ->
                if (index > 0) out.append(' ')
                serialize(item, out)
            }
            out.append(']')
        }
        is Map<*, *> -> {
            out.append("<<")
            value.forEach { (key, item) ->
                out.append(' ')
                name(key as String, out)
                out.append(' ')
                serialize(item, out)
            }
            out.append(" >>")
        }
        else -> out.append("null")
    }
}

/** Characters that aren't regular, or would end the name, are written as `#xx`. */
private fun name(value: String, out: StringBuilder) {
    out.append('/')
    for (c in value) {
        if (c in '!'..'~' && c !in "()<>[]{}/%#") {
            out.append(c)
        } else {
            out.append('#').append(HEX[(c.code shr 4) and 0xF]).append(HEX[c.code and 0xF])
        }
    }
}

private const val HEX = "0123456789ABCDEF"
