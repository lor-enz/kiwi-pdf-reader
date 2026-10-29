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
            pageSize = attempt { file.pages(limit = 1).firstOrNull()?.let(::pageSize) },
            isLocked = isLocked,
            outline = if (withOutline && !isLocked) attempt { outline() }.orEmpty() else emptyList(),
        )
    }

    private fun text(value: Any?) =
        (resolve(value) as? PdfString)?.let { decodePdfText(it.bytes).trim() }?.takeIf { it.isNotEmpty() }

    // Pages

    /** As shown: its visible box, rotation applied. */
    private fun pageSize(page: PdfPage): PageSize? {
        val visible = visibleBox(page) ?: return null
        val unit = (resolve(page.dict["UserUnit"]) as? Number)?.toDouble() ?: 1.0
        val width = abs(visible[2] - visible[0]) * unit
        val height = abs(visible[3] - visible[1]) * unit
        if (width <= 0 || height <= 0) return null
        val isTurned = Math.floorMod(rotation(page), 180) == 90
        return if (isTurned) PageSize(height, width) else PageSize(width, height)
    }

    private fun visibleBox(page: PdfPage) = box(page.attribute("CropBox")) ?: box(page.attribute("MediaBox"))

    private fun rotation(page: PdfPage) = (resolve(page.attribute("Rotate")) as? Number)?.toInt() ?: 0

    private fun box(value: Any?): List<Double>? {
        val numbers = (resolve(value) as? List<*>)?.map { (resolve(it) as? Number)?.toDouble() ?: return null }
        return numbers?.takeIf { it.size == 4 }
    }

    // Outline

    private fun outline(): List<OutlineItem> {
        val nodes = file.outline()
        if (nodes.isEmpty()) return emptyList()
        val spots = pageSpots()

        fun items(nodes: List<PdfOutlineNode>): List<OutlineItem> = nodes.map { node ->
            val target = attempt { target(node.dict, spots) }
            OutlineItem(
                title = text(node.dict["Title"]).orEmpty(),
                page = target?.page,
                top = target?.top,
                isOpen = node.isOpen,
                children = items(node.children),
            )
        }
        return items(nodes)
    }

    private class Target(val page: Int, val top: Float?)

    /** Where an outline item leads: its destination, or a "go to" action's. */
    private fun target(item: Map<String, Any?>, spots: Map<Int, PageSpot>): Target? {
        val destination = item["Dest"] ?: resolveDict(item["A"])
            ?.takeIf { (resolve(it["S"]) as? PdfName)?.value == "GoTo" }
            ?.get("D")
        val array = resolve(file.explicitDestination(destination)) as? List<*> ?: return null
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
    private fun pageSpots(): Map<Int, PageSpot> =
        file.pages(limit = MAX_PAGES).withIndex().associate { (index, page) ->
            val isTurned = Math.floorMod(rotation(page), 360) != 0
            val top = visibleBox(page)?.let { maxOf(it[1], it[3]) }?.takeUnless { isTurned }
            page.ref.number to PageSpot(index, top)
        }

    private fun resolveDict(value: Any?) = file.resolveDict(value)

    private fun resolve(value: Any?) = file.resolve(value)

    private companion object {
        const val MAX_PAGES = 100_000
    }
}
