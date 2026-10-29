package kiwi.lorenz.pdfreader.create

/** What a PDF needs to know to embed a JPEG file as it is. */
data class JpegHeader(val width: Int, val height: Int, val components: Int) {
    companion object {
        /**
         * Reads the frame header of the JPEG in [bytes]. Null if it isn't one a PDF viewer
         * surely shows as it is: only 8-bit baseline, extended or progressive JPEGs in gray or
         * RGB. Others (CMYK, arithmetic coding, 12 bits) are rare and get decoded instead.
         */
        fun read(bytes: ByteArray): JpegHeader? {
            fun byte(i: Int) = bytes[i].toInt() and 0xFF
            fun short(i: Int) = byte(i) shl 8 or byte(i + 1)

            if (bytes.size < 4 || byte(0) != 0xFF || byte(1) != SOI) return null
            var i = 2
            while (i + 3 < bytes.size) {
                if (byte(i) != 0xFF) return null
                val marker = byte(i + 1)
                when {
                    // Fill bytes before a marker.
                    marker == 0xFF -> i++
                    // Markers without a segment.
                    marker == TEM || marker in RST0..RST7 -> i += 2
                    marker == SOS || marker == EOI -> return null
                    marker in SOF_MARKERS -> {
                        if (i + 9 >= bytes.size) return null
                        if (marker !in EMBEDDABLE || byte(i + 4) != 8) return null
                        val height = short(i + 5)
                        val width = short(i + 7)
                        val components = byte(i + 9)
                        // A height of 0 is only given later in the file (DNL), which PDFs don't allow.
                        if (width == 0 || height == 0 || components != 1 && components != 3) return null
                        return JpegHeader(width, height, components)
                    }
                    else -> i += 2 + short(i + 2)
                }
            }
            return null
        }

        private const val SOI = 0xD8
        private const val EOI = 0xD9
        private const val SOS = 0xDA
        private const val TEM = 0x01
        private const val RST0 = 0xD0
        private const val RST7 = 0xD7

        /** Start of frame; 0xC4, 0xC8 and 0xCC are other markers in their range. */
        private val SOF_MARKERS = (0xC0..0xCF).toSet() - setOf(0xC4, 0xC8, 0xCC)

        /** Baseline, extended and progressive, with Huffman coding. */
        private val EMBEDDABLE = setOf(0xC0, 0xC1, 0xC2)
    }
}
