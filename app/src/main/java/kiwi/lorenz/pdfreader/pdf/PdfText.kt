package kiwi.lorenz.pdfreader.pdf

/** A text string: UTF-16 or UTF-8 with a byte order mark, PDFDocEncoding otherwise. */
internal fun decodePdfText(bytes: ByteArray): String {
    val text = when {
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
            String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
            String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
            String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        else -> buildString { bytes.forEach { append(pdfDocChar(it.toInt() and 0xFF)) } }
    }
    // Language tags (ESC xx ESC) and control characters aren't meant to be shown.
    return text.replace(LANGUAGE_TAG, "").filter { it >= ' ' || it == '\n' }.replace('\n', ' ')
}

private val LANGUAGE_TAG = Regex("\u001B[^\u001B]*\u001B")

private fun pdfDocChar(byte: Int): Char = when (byte) {
    in 0x18..0x1F -> "˘ˇˆ˙˝˛˚˜"[byte - 0x18]
    in 0x80..0x9E -> "•†‡…—–ƒ⁄‹›−‰„“”‘’‚™ﬁﬂŁŒŠŸŽıłœšž"[byte - 0x80]
    0xA0 -> '€'
    else -> byte.toChar()
}
