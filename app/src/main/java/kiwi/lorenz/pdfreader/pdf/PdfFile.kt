package kiwi.lorenz.pdfreader.pdf

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.OutputStream
import java.util.zip.Inflater
import kotlin.math.abs

/**
 * A PDF's objects, read as they're asked for. Only as much of the file format as that needs:
 * cross-reference tables and streams (with updates), object streams and Flate-compressed data. If
 * the cross-reference data is broken, the file is scanned for its objects, as PDF readers do.
 * Encrypted files are decrypted with [password], or the empty one (see [PdfSecurity]). Never
 * throws for a damaged file; whatever couldn't be read is null.
 */
internal class PdfFile(private val source: ByteSource, private val password: String? = null) {
    private val entries = HashMap<Int, XrefEntry>()
    private var trailer = Trailer()
    private var repaired: Repair? = null
    private val objectStreams = HashMap<Int, ObjectStream?>()
    private var security: PdfSecurity? = null

    /** The document catalog. */
    val root: Map<String, Any?>?

    /** The document information dictionary: title, author and such. */
    val info: Map<String, Any?>?

    init {
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
        this.info = info
        this.root = root
    }

    /** Encrypted, and the password (or the empty one) doesn't open it. */
    val isLocked get() = trailer.encrypt != null && security == null

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

    // Objects

    @Suppress("UNCHECKED_CAST")
    fun resolveDict(value: Any?) = resolve(value) as? Map<String, Any?>

    fun resolve(value: Any?, depth: Int = 0): Any? {
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
    fun streamData(stream: PdfStream): ByteArray? {
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

    /**
     * The stream's data as stored, still encoded by its filters but decrypted, for copying it
     * as it is. Null if it can't be found. If its length is wrong, it ends where `endstream`
     * does, as PDF readers do. Unencrypted data is copied in chunks, so a large image needn't
     * fit in memory.
     */
    fun encodedStream(stream: PdfStream): EncodedStream? {
        val stated = (resolve(stream.dict["Length"]) as? Number)?.toLong()
        val length = stated?.takeIf { it >= 0 && endsStream(stream.dataOffset + it) }
            ?: findStreamEnd(stream.dataOffset)?.let { it - stream.dataOffset }
            ?: return null
        // Cross-reference streams are never encrypted, and never copied.
        val security = security ?: return EncodedStream(length) { out ->
            var copied = 0L
            while (copied < length) {
                val chunk = source.read(stream.dataOffset + copied, minOf(COPY_CHUNK.toLong(), length - copied).toInt())
                if (chunk.isEmpty()) throw EOFException()
                out.write(chunk)
                copied += chunk.size
            }
        }
        if (length > MAX_DECRYPTED) return null
        val data = security.decryptStream(source.read(stream.dataOffset, length.toInt()), stream.number, stream.generation)
        return EncodedStream(data.size.toLong()) { it.write(data) }
    }

    /** Data of [length] bytes, written to a stream by [writeTo]. */
    class EncodedStream(val length: Long, val writeTo: (OutputStream) -> Unit)

    /** Whether `endstream` follows at [offset], after an optional line end. */
    private fun endsStream(offset: Long): Boolean {
        val text = String(source.read(offset, ENDSTREAM.length + 2), Charsets.ISO_8859_1)
        return text.trimStart('\r', '\n').startsWith(ENDSTREAM)
    }

    /** Where the data starting at [dataOffset] ends: before the line end ahead of `endstream`. */
    private fun findStreamEnd(dataOffset: Long): Long? {
        var chunkStart = dataOffset
        while (chunkStart < source.size) {
            val text = String(source.read(chunkStart, SCAN_CHUNK), Charsets.ISO_8859_1)
            val found = text.indexOf(ENDSTREAM)
            if (found >= 0) {
                var end = chunkStart + found
                // The line end before it isn't part of the data.
                val before = String(source.read(maxOf(dataOffset, end - 2), minOf(2L, end - dataOffset).toInt()), Charsets.ISO_8859_1)
                end -= before.length - before.trimEnd('\r', '\n').length
                return end
            }
            if (text.length < SCAN_CHUNK) return null
            chunkStart += SCAN_CHUNK - ENDSTREAM.length
        }
        return null
    }

    // Pages

    /**
     * The pages in order, at most [limit]. A kid of the page tree that can't be read is left
     * out, after telling [onBroken], which may throw to stop.
     */
    fun pages(limit: Int = Int.MAX_VALUE, onBroken: () -> Unit = {}): List<PdfPage> {
        val tree = resolveDict(root?.get("Pages")) ?: return emptyList()
        val found = ArrayList<PdfPage>()
        val visited = HashSet<Int>()

        fun walk(node: Map<String, Any?>, inherited: Map<String, Any?>, depth: Int) {
            val here = inherited + PdfPage.INHERITED.mapNotNull { key -> node[key]?.let { key to it } }
            val kids = resolve(node["Kids"]) as? List<*> ?: return
            for (kid in kids) {
                if (found.size >= limit) return
                if (kid !is PdfRef || !visited.add(kid.number)) continue
                val dict = resolveDict(kid)
                if (dict == null) {
                    onBroken()
                    continue
                }
                val isPage = (resolve(dict["Type"]) as? PdfName)?.value == "Page" || dict["Kids"] == null
                if (isPage) {
                    found += PdfPage(kid, dict, here)
                } else if (depth < MAX_DEPTH) {
                    walk(dict, here, depth + 1)
                }
            }
        }
        walk(tree, emptyMap(), 0)
        return found
    }

    // Outline

    /** The outline's items as stored, each with the items below it. */
    fun outline(): List<PdfOutlineNode> {
        val first = resolveDict(root?.get("Outlines"))?.get("First") ?: return emptyList()
        val visited = HashSet<Int>()
        var budget = MAX_OUTLINE_ITEMS

        fun items(first: Any?, depth: Int): List<PdfOutlineNode> {
            if (depth > MAX_DEPTH) return emptyList()
            val items = ArrayList<PdfOutlineNode>()
            // Items are linked from first to last; a loop or a broken link ends the list.
            var ref = first
            while (ref is PdfRef && visited.add(ref.number) && budget-- > 0) {
                val dict = resolveDict(ref) ?: break
                items += PdfOutlineNode(
                    dict = dict,
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

    // Destinations

    /**
     * Where [destination] leads: an array of the page and where on it, maybe as a reference. A
     * named destination becomes what it names; anything else is returned as it is.
     */
    fun explicitDestination(destination: Any?): Any? {
        val resolved = resolve(destination)
        if (resolved !is PdfName && resolved !is PdfString) return destination
        val named = resolve(namedDestination(resolved))
        // A named destination may be a dictionary that holds it.
        return if (named is Map<*, *>) named["D"] else named
    }

    /** Named destinations: a dictionary in older files, a name tree in newer ones. */
    private val destinationTree by lazy {
        HashMap<String, Any?>().also { names ->
            resolveDict(resolveDict(root?.get("Names"))?.get("Dests"))?.let { collectNames(it, names, 0) }
        }
    }

    /** What the destination named by [name] stores: an array, or a dictionary holding it as `D`. */
    private fun namedDestination(name: Any?): Any? {
        val key = when (name) {
            is PdfName -> name.value
            is PdfString -> String(name.bytes, Charsets.ISO_8859_1)
            else -> return null
        }
        return attempt { resolveDict(root?.get("Dests"))?.get(key) } ?: destinationTree[key]
    }

    private fun collectNames(node: Map<String, Any?>, names: MutableMap<String, Any?>, depth: Int) {
        if (depth > MAX_DEPTH || names.size > MAX_NAMES) return
        (resolve(node["Names"]) as? List<*>)?.chunked(2)?.forEach { pair ->
            val key = resolve(pair[0]) as? PdfString ?: return@forEach
            names.putIfAbsent(String(key.bytes, Charsets.ISO_8859_1), pair.getOrNull(1))
        }
        (resolve(node["Kids"]) as? List<*>)?.forEach { kid ->
            resolveDict(kid)?.let { collectNames(it, names, depth + 1) }
        }
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
        const val INITIAL_WINDOW = 16 * 1024
        const val MAX_WINDOW = 16 * 1024 * 1024
        const val MAX_DECODED = 64L * 1024 * 1024
        const val MAX_DECRYPTED = 256L * 1024 * 1024
        const val MAX_NAMES = 100_000
        const val MAX_OUTLINE_ITEMS = 10_000
        const val COPY_CHUNK = 256 * 1024
        const val TAIL_BYTES = 4096
        const val SCAN_CHUNK = 1024 * 1024
        const val SCAN_OVERLAP = 64
        val STARTXREF = Regex("""startxref\s+(\d+)""")
        val OBJECT_HEADER = Regex("""(?<![0-9])(\d{1,10})\s+\d{1,5}\s+obj\b""")
        val TRAILER = Regex("""trailer\b""")
        const val ENDSTREAM = "endstream"
    }
}

/**
 * A page as the page tree has it, [ref] referring to it. [inherited] holds what the tree's nodes
 * above it give it; the page's own [dict] wins over it, see [attribute].
 */
internal class PdfPage(val ref: PdfRef, val dict: Map<String, Any?>, val inherited: Map<String, Any?>) {
    fun attribute(key: String): Any? = dict[key] ?: inherited[key]

    companion object {
        /** What a page inherits from the page tree's nodes. */
        val INHERITED = listOf("Resources", "MediaBox", "CropBox", "Rotate")
    }
}

/** An item of the outline as stored, see [PdfFile.outline]. */
internal class PdfOutlineNode(val dict: Map<String, Any?>, val isOpen: Boolean, val children: List<PdfOutlineNode>)

/** How deep references and nested structures are followed, so loops in a damaged file end. */
internal const val MAX_DEPTH = 64

/** Damaged files make parsing fail in all sorts of ways; whatever failed is left out. */
internal inline fun <T> attempt(block: () -> T): T? = try {
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
