package kiwi.lorenz.pdfreader.pdf

import java.io.ByteArrayOutputStream

internal data class PdfName(val value: String)
internal class PdfString(val bytes: ByteArray)
internal data class PdfRef(val number: Int, val generation: Int)
internal data class Keyword(val value: String)
internal class PdfStream(val dict: Map<String, Any?>, val dataOffset: Long, val number: Int, val generation: Int)

/** Ran out of bytes; a larger window may help. */
internal class EndOfData : RuntimeException(null, null, false, false)

/**
 * Turns bytes into objects: numbers are [Long] or [Double], arrays [List], dictionaries [Map]
 * keyed by name, `null` is null. [reachesEnd] tells whether [bytes] go to the end of the file.
 */
internal class Lexer(private val bytes: ByteArray, private val reachesEnd: Boolean, var pos: Int = 0) {
    fun next(): Any? {
        skipSpace()
        val c = byteAt(pos)
        return when {
            c == '/'.code -> name()
            c == '('.code -> literalString()
            c == '<'.code && byteAt(pos + 1) == '<'.code -> dictionary()
            c == '<'.code -> hexString()
            c == '['.code -> array()
            c == '+'.code || c == '-'.code || c == '.'.code || isDigit(c) -> numberOrRef()
            isDelimiter(c) -> {
                pos++
                Keyword(c.toChar().toString())
            }
            else -> keyword().let {
                when (it) {
                    "true" -> true
                    "false" -> false
                    "null" -> null
                    else -> Keyword(it)
                }
            }
        }
    }

    /** The keyword that comes next, null if something else does or the file ends. */
    fun peekKeyword(): String? {
        val start = pos
        try {
            skipSpace()
            if (isDelimiter(byteAt(pos)) || isDigit(byteAt(pos))) return null
            return keyword()
        } catch (e: EndOfData) {
            if (reachesEnd) return null
            throw e
        } finally {
            pos = start
        }
    }

    fun skipStreamLineEnd() {
        if (byteAt(pos) == '\r'.code) pos++
        if (byteAt(pos) == '\n'.code) pos++
    }

    private fun byteAt(index: Int): Int {
        if (index >= bytes.size) throw EndOfData()
        return bytes[index].toInt() and 0xFF
    }

    private fun skipSpace() {
        while (true) {
            val c = byteAt(pos)
            when {
                isSpace(c) -> pos++
                c == '%'.code -> while (byteAt(pos) != '\n'.code && byteAt(pos) != '\r'.code) pos++
                else -> return
            }
        }
    }

    private fun regularRun(): String {
        val start = pos
        while (pos < bytes.size && !isSpace(byteAt(pos)) && !isDelimiter(byteAt(pos))) pos++
        // A token cut off by the window's end may go on.
        if (pos >= bytes.size && !reachesEnd) throw EndOfData()
        return String(bytes, start, pos - start, Charsets.ISO_8859_1)
    }

    private fun keyword() = regularRun().ifEmpty { throw EndOfData() }

    private fun name(): PdfName {
        pos++
        val raw = regularRun()
        val decoded = if ('#' in raw) {
            HEX_ESCAPE.replace(raw) { it.groupValues[1].toInt(16).toChar().toString() }
        } else {
            raw
        }
        return PdfName(decoded)
    }

    private fun numberOrRef(): Any {
        val token = regularRun()
        val integer = token.toLongOrNull() ?: return token.toDoubleOrNull() ?: 0L
        if (integer < 0) return integer
        // "12 0 R" is a reference.
        val afterNumber = pos
        try {
            skipSpace()
            if (isDigit(byteAt(pos))) {
                val generation = regularRun().toIntOrNull()
                skipSpace()
                if (generation != null && byteAt(pos) == 'R'.code &&
                    (pos + 1 >= bytes.size || isSpace(byteAt(pos + 1)) || isDelimiter(byteAt(pos + 1)))
                ) {
                    pos++
                    return PdfRef(integer.toInt(), generation)
                }
            }
        } catch (e: EndOfData) {
            if (!reachesEnd) throw e
        }
        pos = afterNumber
        return integer
    }

    private fun literalString(): PdfString {
        pos++
        val out = ByteArrayOutputStream()
        var depth = 1
        while (true) {
            when (val c = byteAt(pos++)) {
                '('.code -> {
                    depth++
                    out.write(c)
                }
                ')'.code -> {
                    if (--depth == 0) return PdfString(out.toByteArray())
                    out.write(c)
                }
                '\\'.code -> when (val e = byteAt(pos++)) {
                    'n'.code -> out.write('\n'.code)
                    'r'.code -> out.write('\r'.code)
                    't'.code -> out.write('\t'.code)
                    'b'.code -> out.write(8)
                    'f'.code -> out.write(12)
                    '\r'.code -> if (byteAt(pos) == '\n'.code) pos++
                    '\n'.code -> {}
                    in '0'.code..'7'.code -> {
                        var value = e - '0'.code
                        repeat(2) {
                            val d = byteAt(pos)
                            if (d in '0'.code..'7'.code) {
                                value = value * 8 + (d - '0'.code)
                                pos++
                            }
                        }
                        out.write(value and 0xFF)
                    }
                    else -> out.write(e)
                }
                else -> out.write(c)
            }
        }
    }

    private fun hexString(): PdfString {
        pos++
        val out = ByteArrayOutputStream()
        var high = -1
        while (true) {
            val c = byteAt(pos++)
            if (c == '>'.code) break
            val digit = Character.digit(c, 16)
            if (digit < 0) continue
            if (high < 0) {
                high = digit
            } else {
                out.write(high * 16 + digit)
                high = -1
            }
        }
        if (high >= 0) out.write(high * 16)
        return PdfString(out.toByteArray())
    }

    private fun array(): List<Any?> {
        pos++
        val items = ArrayList<Any?>()
        while (true) {
            skipSpace()
            if (byteAt(pos) == ']'.code) {
                pos++
                return items
            }
            items += next()
        }
    }

    private fun dictionary(): Map<String, Any?> {
        pos += 2
        val map = LinkedHashMap<String, Any?>()
        while (true) {
            skipSpace()
            if (byteAt(pos) == '>'.code && byteAt(pos + 1) == '>'.code) {
                pos += 2
                return map
            }
            val key = next()
            // A broken dictionary may have stray tokens; they're skipped.
            if (key is PdfName) map[key.value] = next()
        }
    }

    private companion object {
        val HEX_ESCAPE = Regex("#([0-9A-Fa-f]{2})")
        fun isSpace(c: Int) = c == 0 || c == 9 || c == 10 || c == 12 || c == 13 || c == 32
        fun isDigit(c: Int) = c in '0'.code..'9'.code
        fun isDelimiter(c: Int) = c == '('.code || c == ')'.code || c == '<'.code || c == '>'.code ||
            c == '['.code || c == ']'.code || c == '{'.code || c == '}'.code || c == '/'.code || c == '%'.code
    }
}
