package kiwi.lorenz.pdfreader.pdf

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater
import kotlin.math.abs

/**
 * Reads [PdfMetadata] from a PDF. Only as much of the file format as that needs: cross-reference
 * tables and streams (with updates), object streams and Flate-compressed data. If the
 * cross-reference data is broken, the file is scanned for its objects, as PDF readers do.
 * Encrypted files are decrypted with [password], or the empty one (see [PdfSecurity]). Never
 * throws for a damaged file; whatever couldn't be read is left out.
 *
 * The outline is only read when asked for, as it means going through every page.
 */
class PdfMetadataReader(private val source: ByteSource, private val password: String? = null) {
    private val entries = HashMap<Int, XrefEntry>()
    private var trailer = Trailer()
    private var repaired: Repair? = null
    private val objectStreams = HashMap<Int, ObjectStream?>()
    private var security: PdfSecurity? = null

    fun read(withOutline: Boolean = false): PdfMetadata {
        loadXref()
        setUpSecurity()
        var info = infoDictionary()
        var root = resolveDict(trailer.root)
        if (info == null && root == null) {
            // Nothing found through the cross-reference data, so it's broken.
            entries.clear()
            trailer = repair().trailer
            setUpSecurity()
            info = infoDictionary()
            root = resolveDict(trailer.root)
        }
        val isLocked = trailer.encrypt != null && security == null
        fun text(key: String) = if (isLocked) null else text(info?.get(key))

        val pages = attempt { resolveDict(root?.get("Pages")) }
        return PdfMetadata(
            title = text("Title"),
            author = text("Author"),
            subject = text("Subject"),
            creator = text("Creator"),
            producer = text("Producer"),
            created = text("CreationDate")?.let(::parsePdfDate),
            modified = text("ModDate")?.let(::parsePdfDate),
            pageCount = (resolve(pages?.get("Count")) as? Number)?.toInt()?.takeIf { it > 0 },
            pageSize = pages?.let { attempt { firstPageSize(it) } },
            isLocked = isLocked,
            outline = if (withOutline && !isLocked && root != null && pages != null) {
                attempt { outline(root, pages) }.orEmpty()
            } else {
                emptyList()
            },
        )
    }

    private fun text(value: Any?) =
        (resolve(value) as? PdfString)?.let { decodePdfText(it.bytes).trim() }?.takeIf { it.isNotEmpty() }

    /** Before anything else is read, as objects read before it would stay encrypted. */
    private fun setUpSecurity() {
        security = null
        objectStreams.clear()
        val encrypt = trailer.encrypt ?: return
        security = attempt {
            val dict = resolveDict(encrypt) ?: return@attempt null
            val id = ((resolve(trailer.id) as? List<*>)?.firstOrNull()?.let(::resolve) as? PdfString)?.bytes ?: ByteArray(0)
            PdfSecurity.open(dict, id, password) { resolve(it) }
        }
    }

    private fun infoDictionary() = attempt { resolveDict(trailer.info) }

    // Pages

    /** Box and rotation are inherited from the page tree's nodes. */
    private fun firstPageSize(pages: Map<String, Any?>): PageSize? {
        var node = pages
        var mediaBox: List<Double>? = null
        var cropBox: List<Double>? = null
        var rotate = 0
        repeat(MAX_DEPTH) {
            box(node["MediaBox"])?.let { mediaBox = it }
            box(node["CropBox"])?.let { cropBox = it }
            (resolve(node["Rotate"]) as? Number)?.let { rotate = it.toInt() }
            val kids = resolve(node["Kids"]) as? List<*>
            if ((resolve(node["Type"]) as? PdfName)?.value == "Page" || kids.isNullOrEmpty()) {
                val visible = cropBox ?: mediaBox ?: return null
                val unit = (resolve(node["UserUnit"]) as? Number)?.toDouble() ?: 1.0
                val width = abs(visible[2] - visible[0]) * unit
                val height = abs(visible[3] - visible[1]) * unit
                if (width <= 0 || height <= 0) return null
                val isTurned = Math.floorMod(rotate, 180) == 90
                return if (isTurned) PageSize(height, width) else PageSize(width, height)
            }
            node = resolveDict(kids[0]) ?: return null
        }
        return null
    }

    private fun box(value: Any?): List<Double>? {
        val numbers = (resolve(value) as? List<*>)?.map { (resolve(it) as? Number)?.toDouble() ?: return null }
        return numbers?.takeIf { it.size == 4 }
    }

    // Outline

    private fun outline(root: Map<String, Any?>, pages: Map<String, Any?>): List<OutlineItem> {
        val first = resolveDict(root["Outlines"])?.get("First") ?: return emptyList()
        val spots = pageSpots(pages)
        val destinations = Destinations(root)
        val visited = HashSet<Int>()
        var budget = MAX_OUTLINE_ITEMS

        fun items(first: Any?, depth: Int): List<OutlineItem> {
            if (depth > MAX_DEPTH) return emptyList()
            val items = ArrayList<OutlineItem>()
            // Items are linked from first to last; a loop or a broken link ends the list.
            var ref = first
            while (ref is PdfRef && visited.add(ref.number) && budget-- > 0) {
                val dict = resolveDict(ref) ?: break
                val target = attempt { target(dict, spots, destinations) }
                items += OutlineItem(
                    title = text(dict["Title"]).orEmpty(),
                    page = target?.page,
                    top = target?.top,
                    // Positive when the author left it open, negative when closed.
                    isOpen = ((resolve(dict["Count"]) as? Number)?.toInt() ?: 0) > 0,
                    children = items(dict["First"], depth + 1),
                )
                ref = dict["Next"]
            }
            return items
        }
        return items(first, 0)
    }

    private class Target(val page: Int, val top: Float?)

    /** Where an outline item leads: its destination, or a "go to" action's. */
    private fun target(item: Map<String, Any?>, spots: Map<Int, PageSpot>, destinations: Destinations): Target? {
        val destination = item["Dest"] ?: resolveDict(item["A"])
            ?.takeIf { (resolve(it["S"]) as? PdfName)?.value == "GoTo" }
            ?.get("D")
        val explicit = when (val value = resolve(destination)) {
            is PdfName -> destinations.find(value.value)
            is PdfString -> destinations.find(String(value.bytes, Charsets.ISO_8859_1))
            else -> value
        }
        // A named destination may be a dictionary that holds it.
        val array = (resolve(explicit).let { if (it is Map<*, *>) resolve(it["D"]) else it }) as? List<*> ?: return null
        val spot = when (val page = array.firstOrNull()) {
            is PdfRef -> spots[page.number]
            // Meant for other files, but some writers use page numbers in their own.
            is Long -> spots.values.firstOrNull { it.index.toLong() == page }
            else -> null
        } ?: return null
        // [page /XYZ left top zoom], [page /FitH top], [page /FitR left bottom right top], ...
        val top = when ((resolve(array.getOrNull(1)) as? PdfName)?.value) {
            "XYZ" -> array.getOrNull(3)
            "FitH", "FitBH" -> array.getOrNull(2)
            "FitR" -> array.getOrNull(5)
            else -> null
        }?.let { resolve(it) as? Number }?.toDouble()
        val fromTop = if (top != null && spot.top != null) (spot.top - top).coerceAtLeast(0.0).toFloat() else null
        return Target(spot.index, fromTop)
    }

    /** Named destinations: a dictionary in older files, a name tree in newer ones. */
    private inner class Destinations(private val root: Map<String, Any?>) {
        private val tree by lazy {
            HashMap<String, Any?>().also { names ->
                resolveDict(resolveDict(root["Names"])?.get("Dests"))?.let { collect(it, names, 0) }
            }
        }

        fun find(name: String): Any? = resolveDict(root["Dests"])?.get(name) ?: tree[name]

        private fun collect(node: Map<String, Any?>, names: MutableMap<String, Any?>, depth: Int) {
            if (depth > MAX_DEPTH || names.size > MAX_NAMES) return
            (resolve(node["Names"]) as? List<*>)?.chunked(2)?.forEach { pair ->
                val key = resolve(pair[0]) as? PdfString ?: return@forEach
                names.putIfAbsent(String(key.bytes, Charsets.ISO_8859_1), pair.getOrNull(1))
            }
            (resolve(node["Kids"]) as? List<*>)?.forEach { kid ->
                resolveDict(kid)?.let { collect(it, names, depth + 1) }
            }
        }
    }

    /**
     * Where each page is: [index] in the document, and [top] the top of its visible box, from
     * which outline targets are measured. Null for turned pages, whose targets lead to their top.
     */
    private class PageSpot(val index: Int, val top: Double?)

    /** Keyed by object number, as destinations name pages by reference. */
    private fun pageSpots(pages: Map<String, Any?>): Map<Int, PageSpot> {
        val spots = HashMap<Int, PageSpot>()
        val visited = HashSet<Int>()

        // Box and rotation are inherited from the page tree's nodes.
        fun walk(node: Map<String, Any?>, mediaBox: List<Double>?, cropBox: List<Double>?, rotate: Int, depth: Int) {
            val kids = resolve(node["Kids"]) as? List<*> ?: return
            val media = box(node["MediaBox"]) ?: mediaBox
            val crop = box(node["CropBox"]) ?: cropBox
            val turn = (resolve(node["Rotate"]) as? Number)?.toInt() ?: rotate
            for (kid in kids) {
                if (kid !is PdfRef || !visited.add(kid.number) || spots.size >= MAX_PAGES) continue
                val dict = resolveDict(kid) ?: continue
                val isPage = (resolve(dict["Type"]) as? PdfName)?.value == "Page" || dict["Kids"] == null
                if (isPage) {
                    val visible = box(dict["CropBox"]) ?: crop ?: box(dict["MediaBox"]) ?: media
                    val isTurned = Math.floorMod((resolve(dict["Rotate"]) as? Number)?.toInt() ?: turn, 360) != 0
                    val top = visible?.let { maxOf(it[1], it[3]) }?.takeUnless { isTurned }
                    spots[kid.number] = PageSpot(spots.size, top)
                } else if (depth < MAX_DEPTH) {
                    walk(dict, media, crop, turn, depth + 1)
                }
            }
        }
        walk(pages, null, null, 0, 0)
        return spots
    }

    // Objects

    @Suppress("UNCHECKED_CAST")
    private fun resolveDict(value: Any?) = resolve(value) as? Map<String, Any?>

    private fun resolve(value: Any?, depth: Int = 0): Any? {
        if (value !is PdfRef || depth > MAX_DEPTH) return value
        return resolve(attempt { load(value.number) }, depth + 1)
    }

    /** Objects in object streams were decrypted with their stream. */
    private fun load(number: Int): Any? {
        val entry = entries[number]
        if (entry is XrefEntry.InStream) return objectStream(entry.stream)?.get(entry.index)
        val offset = (entry as? XrefEntry.InFile)?.offset
        val found = offset?.let { objectAt(it, number) }
            ?: repair().offsets[number]?.takeIf { it != offset }?.let { objectAt(it, number) }
            ?: return null
        return security?.decryptStrings(found.value, number, found.generation) ?: found.value
    }

    /** The object [number] at [offset], or null if a different one is there. */
    private fun objectAt(offset: Long, number: Int): Indirect? = indirectAt(offset)?.takeIf { it.number == number }

    private class Indirect(val number: Int, val generation: Int, val value: Any?)

    /**
     * The indirect object at [offset]. A stream's data isn't read yet, see [streamData]. The
     * window grows until the object fits.
     */
    private fun indirectAt(offset: Long): Indirect? {
        var window = INITIAL_WINDOW
        while (true) {
            val bytes = source.read(offset, window)
            val reachesEnd = offset + bytes.size >= source.size
            try {
                val lexer = Lexer(bytes, reachesEnd)
                val number = (lexer.next() as? Long)?.toInt() ?: return null
                val generation = (lexer.next() as? Long)?.toInt() ?: return null
                if (lexer.next() != Keyword("obj")) return null
                val value = lexer.next()
                if (value is Map<*, *> && lexer.peekKeyword() == "stream") {
                    lexer.next()
                    lexer.skipStreamLineEnd()
                    @Suppress("UNCHECKED_CAST")
                    val stream = PdfStream(value as Map<String, Any?>, offset + lexer.pos, number, generation)
                    return Indirect(number, generation, stream)
                }
                return Indirect(number, generation, value)
            } catch (_: EndOfData) {
                if (reachesEnd || window >= MAX_WINDOW) return null
                window *= 4
            }
        }
    }

    /** Decoded; null if it uses a filter other than Flate. */
    private fun streamData(stream: PdfStream): ByteArray? {
        val length = (resolve(stream.dict["Length"]) as? Number)?.toLong() ?: return null
        if (length < 0 || length > MAX_DECODED) return null
        val stored = source.read(stream.dataOffset, length.toInt())
        // Cross-reference streams are never encrypted.
        val isXref = (resolve(stream.dict["Type"]) as? PdfName)?.value == "XRef"
        val raw = security?.takeUnless { isXref }?.decryptStream(stored, stream.number, stream.generation) ?: stored
        val filters = when (val filter = resolve(stream.dict["Filter"])) {
            null -> emptyList()
            is PdfName -> listOf(filter.value)
            is List<*> -> filter.map { (resolve(it) as? PdfName)?.value ?: return null }
            else -> return null
        }
        if (filters.isEmpty()) return raw
        if (filters != listOf("FlateDecode") && filters != listOf("Fl")) return null
        val params = resolve(stream.dict["DecodeParms"]).let { if (it is List<*>) resolve(it.firstOrNull()) else it }
        return unpredict(inflate(raw), params as? Map<*, *>)
    }

    private fun objectStream(number: Int): ObjectStream? = objectStreams.getOrPut(number) {
        attempt {
            // Object streams can't be in object streams themselves.
            val stream = (if (entries[number] is XrefEntry.InStream) null else load(number)) as? PdfStream
                ?: return@attempt null
            val data = streamData(stream) ?: return@attempt null
            val count = (resolve(stream.dict["N"]) as? Number)?.toInt() ?: return@attempt null
            val first = (resolve(stream.dict["First"]) as? Number)?.toInt() ?: return@attempt null
            val header = Lexer(data, reachesEnd = true)
            val offsets = List(count) {
                header.next()
                (header.next() as Long).toInt()
            }
            ObjectStream(data, first, offsets)
        }
    }

    private class ObjectStream(val data: ByteArray, val first: Int, val offsets: List<Int>) {
        fun get(index: Int): Any? {
            val offset = offsets.getOrNull(index) ?: return null
            return Lexer(data, reachesEnd = true, pos = first + offset).next()
        }
    }

    // Cross-reference data

    private sealed interface XrefEntry {
        data class InFile(val offset: Long) : XrefEntry
        data class InStream(val stream: Int, val index: Int) : XrefEntry
    }

    /** What the trailer points to. The newest update that has a key wins. */
    private data class Trailer(val root: Any? = null, val info: Any? = null, val encrypt: Any? = null, val id: Any? = null) {
        fun withOlder(dict: Map<String, Any?>) = Trailer(
            root = root ?: dict["Root"],
            info = info ?: dict["Info"],
            encrypt = encrypt ?: dict["Encrypt"],
            id = id ?: dict["ID"],
        )
    }

    private fun loadXref() {
        val start = attempt { startXref() } ?: return
        val visited = HashSet<Long>()
        var next: Long? = start
        while (next != null && visited.add(next)) {
            val offset: Long = next
            next = attempt { xrefSectionAt(offset) }
        }
    }

    private fun startXref(): Long? {
        val tailStart = maxOf(0, source.size - TAIL_BYTES)
        val tail = String(source.read(tailStart, TAIL_BYTES), Charsets.ISO_8859_1)
        val match = STARTXREF.findAll(tail).lastOrNull() ?: return null
        return match.groupValues[1].toLong()
    }

    /** Records one update's entries (newer ones were recorded before) and returns the previous one's offset. */
    private fun xrefSectionAt(offset: Long): Long? {
        var window = INITIAL_WINDOW
        while (true) {
            val bytes = source.read(offset, window)
            val reachesEnd = offset + bytes.size >= source.size
            val lexer = Lexer(bytes, reachesEnd)
            try {
                if (lexer.peekKeyword() != "xref") return xrefStreamAt(offset)
                lexer.next()
                val table = HashMap<Int, XrefEntry>()
                while (lexer.peekKeyword() != "trailer") {
                    val first = (lexer.next() as Long).toInt()
                    val count = (lexer.next() as Long).toInt()
                    for (number in first until first + count) {
                        val entryOffset = lexer.next() as Long
                        lexer.next()
                        if (lexer.next() == Keyword("n")) table[number] = XrefEntry.InFile(entryOffset)
                    }
                }
                lexer.next()
                @Suppress("UNCHECKED_CAST")
                val dict = lexer.next() as Map<String, Any?>
                table.forEach { (number, entry) -> entries.putIfAbsent(number, entry) }
                trailer = trailer.withOlder(dict)
                // A file updated by an app that knows streams, for readers that don't.
                (dict["XRefStm"] as? Number)?.let { attempt { xrefStreamAt(it.toLong()) } }
                return (dict["Prev"] as? Number)?.toLong()
            } catch (_: EndOfData) {
                if (reachesEnd || window >= MAX_WINDOW) return null
                window *= 4
            }
        }
    }

    private fun xrefStreamAt(offset: Long): Long? {
        val stream = indirectAt(offset)?.value as? PdfStream ?: return null
        val dict = stream.dict
        if ((dict["Type"] as? PdfName)?.value != "XRef") return null
        val data = streamData(stream) ?: return null
        val widths = (dict["W"] as List<*>).map { (it as Number).toInt() }
        val size = (dict["Size"] as Number).toInt()
        val index = (dict["Index"] as? List<*>)?.map { (it as Number).toInt() } ?: listOf(0, size)
        var pos = 0
        fun field(width: Int): Long {
            var value = 0L
            repeat(width) { value = (value shl 8) or (data[pos++].toLong() and 0xFF) }
            return value
        }
        val rowSize = widths.sum()
        for (range in index.chunked(2)) {
            for (number in range[0] until range[0] + range[1]) {
                if (pos + rowSize > data.size) break
                val type = if (widths[0] == 0) 1L else field(widths[0])
                val second = field(widths[1])
                val third = field(widths[2])
                when (type) {
                    1L -> entries.putIfAbsent(number, XrefEntry.InFile(second))
                    2L -> entries.putIfAbsent(number, XrefEntry.InStream(second.toInt(), third.toInt()))
                }
            }
        }
        trailer = trailer.withOlder(dict)
        return (dict["Prev"] as? Number)?.toLong()
    }

    // Repair

    /** Where each object starts and the last trailer, found by scanning the whole file. */
    private class Repair(val offsets: Map<Int, Long>, val trailer: Trailer)

    private fun repair(): Repair = repaired ?: scan().also { repaired = it }

    private fun scan(): Repair {
        val offsets = HashMap<Int, Long>()
        val trailers = ArrayList<Long>()
        var chunkStart = 0L
        while (chunkStart < source.size) {
            val text = String(source.read(chunkStart, SCAN_CHUNK), Charsets.ISO_8859_1)
            // Later objects replace earlier ones with the same number, as updates do.
            OBJECT_HEADER.findAll(text).forEach {
                offsets[it.groupValues[1].toInt()] = chunkStart + it.range.first
            }
            TRAILER.findAll(text).forEach { trailers += chunkStart + it.range.last + 1 }
            if (text.length < SCAN_CHUNK) break
            chunkStart += SCAN_CHUNK - SCAN_OVERLAP
        }
        var trailer = Trailer()
        for (offset in trailers.distinct().asReversed()) {
            val dict = attempt { Lexer(source.read(offset, INITIAL_WINDOW), reachesEnd = true).next() }
            @Suppress("UNCHECKED_CAST")
            if (dict is Map<*, *>) trailer = trailer.withOlder(dict as Map<String, Any?>)
        }
        return Repair(offsets, trailer)
    }

    private companion object {
        const val MAX_DEPTH = 64
        const val MAX_OUTLINE_ITEMS = 10_000
        const val MAX_NAMES = 100_000
        const val MAX_PAGES = 100_000
        const val INITIAL_WINDOW = 16 * 1024
        const val MAX_WINDOW = 16 * 1024 * 1024
        const val MAX_DECODED = 64L * 1024 * 1024
        const val TAIL_BYTES = 4096
        const val SCAN_CHUNK = 1024 * 1024
        const val SCAN_OVERLAP = 64
        val STARTXREF = Regex("""startxref\s+(\d+)""")
        val OBJECT_HEADER = Regex("""(?<![0-9])(\d{1,10})\s+\d{1,5}\s+obj\b""")
        val TRAILER = Regex("""trailer\b""")
    }
}

/** Damaged files make parsing fail in all sorts of ways; whatever failed is left out. */
private inline fun <T> attempt(block: () -> T): T? = try {
    block()
} catch (_: Exception) {
    null
}

// Inflating and predictors

private fun inflate(data: ByteArray): ByteArray {
    val inflater = Inflater()
    try {
        inflater.setInput(data)
        val out = ByteArrayOutputStream(data.size * 4)
        val buffer = ByteArray(16 * 1024)
        // Truncated data still gives what was there.
        while (!inflater.finished() && out.size() < 64 * 1024 * 1024) {
            val count = try {
                inflater.inflate(buffer)
            } catch (_: Exception) {
                break
            }
            if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    } finally {
        inflater.end()
    }
}

/** Undoes a PNG predictor (cross-reference streams use them); TIFF predictors aren't needed. */
private fun unpredict(data: ByteArray, params: Map<*, *>?): ByteArray {
    val predictor = (params?.get("Predictor") as? Number)?.toInt() ?: 1
    if (predictor < 10) return data
    val colors = (params?.get("Colors") as? Number)?.toInt() ?: 1
    val bits = (params?.get("BitsPerComponent") as? Number)?.toInt() ?: 8
    val columns = (params?.get("Columns") as? Number)?.toInt() ?: 1
    val pixelBytes = maxOf(1, colors * bits / 8)
    val rowBytes = (colors * bits * columns + 7) / 8
    val out = ByteArrayOutputStream(data.size)
    var previous = ByteArray(rowBytes)
    var pos = 0
    while (pos + 1 + rowBytes <= data.size) {
        val type = data[pos].toInt()
        val row = data.copyOfRange(pos + 1, pos + 1 + rowBytes)
        for (i in row.indices) {
            val left = if (i >= pixelBytes) row[i - pixelBytes].toInt() and 0xFF else 0
            val up = previous[i].toInt() and 0xFF
            val upLeft = if (i >= pixelBytes) previous[i - pixelBytes].toInt() and 0xFF else 0
            val add = when (type) {
                1 -> left
                2 -> up
                3 -> (left + up) / 2
                4 -> paeth(left, up, upLeft)
                else -> 0
            }
            row[i] = (row[i] + add).toByte()
        }
        out.write(row)
        previous = row
        pos += 1 + rowBytes
    }
    return out.toByteArray()
}

private fun paeth(a: Int, b: Int, c: Int): Int {
    val p = a + b - c
    val pa = abs(p - a)
    val pb = abs(p - b)
    val pc = abs(p - c)
    return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
}

// Text

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

// Lexing

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
