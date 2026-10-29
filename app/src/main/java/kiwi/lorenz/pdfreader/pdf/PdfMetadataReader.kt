package kiwi.lorenz.pdfreader.pdf

import kotlin.math.abs

/**
 * Reads [PdfMetadata] from a PDF, through [PdfFile]. Encrypted files are decrypted with
 * [password], or the empty one. Never throws for a damaged file; whatever couldn't be read is
 * left out.
 *
 * The outline is only read when asked for, as it means going through every page.
 */
class PdfMetadataReader(private val source: ByteSource, private val password: String? = null) {
    private val file by lazy { PdfFile(source, password) }

    fun read(withOutline: Boolean = false): PdfMetadata {
        val info = file.info
        val root = file.root
        val isLocked = file.isLocked
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
        val visited = HashSet<Int>()
        var budget = MAX_OUTLINE_ITEMS

        fun items(first: Any?, depth: Int): List<OutlineItem> {
            if (depth > MAX_DEPTH) return emptyList()
            val items = ArrayList<OutlineItem>()
            // Items are linked from first to last; a loop or a broken link ends the list.
            var ref = first
            while (ref is PdfRef && visited.add(ref.number) && budget-- > 0) {
                val dict = resolveDict(ref) ?: break
                val target = attempt { target(dict, spots) }
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
    private fun target(item: Map<String, Any?>, spots: Map<Int, PageSpot>): Target? {
        val destination = item["Dest"] ?: resolveDict(item["A"])
            ?.takeIf { (resolve(it["S"]) as? PdfName)?.value == "GoTo" }
            ?.get("D")
        val explicit = when (val value = resolve(destination)) {
            is PdfName, is PdfString -> file.namedDestination(value)
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

    private fun resolveDict(value: Any?) = file.resolveDict(value)

    private fun resolve(value: Any?) = file.resolve(value)

    private companion object {
        const val MAX_OUTLINE_ITEMS = 10_000
        const val MAX_PAGES = 100_000
    }
}
