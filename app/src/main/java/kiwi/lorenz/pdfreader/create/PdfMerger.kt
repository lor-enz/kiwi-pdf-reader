package kiwi.lorenz.pdfreader.create

import kiwi.lorenz.pdfreader.pdf.ByteSource
import kiwi.lorenz.pdfreader.pdf.PdfFile
import kiwi.lorenz.pdfreader.pdf.PdfName
import kiwi.lorenz.pdfreader.pdf.PdfOutlineNode
import kiwi.lorenz.pdfreader.pdf.PdfRef
import kiwi.lorenz.pdfreader.pdf.PdfStream
import kiwi.lorenz.pdfreader.pdf.PdfString
import kiwi.lorenz.pdfreader.pdf.decodePdfText
import java.io.OutputStream
import java.time.ZonedDateTime
import java.util.IdentityHashMap

/** A PDF that can't be merged: protected by a password that's missing or wrong, or damaged. */
class MergeException(val needsPassword: Boolean) : Exception()

/**
 * Merges PDFs into one, their pages in the order added. Pages are copied with everything they
 * use, streams as they are (no quality lost), decrypted. Links within a document keep working:
 * named destinations become explicit ones, so names of different documents can't collide, and
 * links with a bare destination get a "go to" action, the only kind androidx.pdf follows. Form
 * fields are merged, a name an earlier document took getting a number, so equal forms don't
 * share their values. Layers are merged as each document left them. The outline gets an entry
 * per document, its file name without `.pdf` (the user knows it by that; titles are often
 * generic, "untitled" or "Microsoft Word - …"), with the document's own outline below it. What
 * belongs to a document as a whole (tags for screen readers, page labels, attachments) is left
 * out. Call [finish] once all documents are added.
 *
 * Unlike [kiwi.lorenz.pdfreader.pdf.PdfMetadataReader], nothing is left out of a page because it
 * couldn't be read: a document that can't be copied whole throws a [MergeException], and the
 * output is unusable.
 */
class PdfMerger(output: OutputStream) {
    private val writer = PdfObjectWriter(output, "1.7")
    private var nextNumber = FIRST_OBJECT
    private val pages = mutableListOf<Int>()
    private val outline = mutableListOf<OutlineItem>()

    private val fields = mutableListOf<Any?>()
    private val calculationOrder = mutableListOf<Any?>()
    private val fieldNames = HashSet<String>()
    private val formResources = LinkedHashMap<String, Any?>()
    private var defaultAppearance: Any? = null
    private var needsAppearances = false

    private val layers = mutableListOf<Any?>()
    private val layersOff = mutableListOf<Any?>()
    private val layerOrder = mutableListOf<Any?>()
    private val layerRadioGroups = mutableListOf<Any?>()

    val pageCount get() = pages.size

    /** Adds the pages of the PDF in [source], decrypted with [password]. Its file [name] stands for it in the outline. */
    fun add(source: ByteSource, password: String?, name: String) {
        val file = PdfFile(source, password)
        if (file.isLocked) throw MergeException(needsPassword = true)
        Copy(file).run(name)
    }

    /** Writes what refers to all documents, and the file's index. Needs at least one page. */
    fun finish(created: ZonedDateTime) {
        check(pages.isNotEmpty()) { "A PDF needs a page" }
        writer.writeObject(
            PAGES,
            mapOf("Type" to PdfName("Pages"), "Kids" to pages.map { PdfRef(it, 0) }, "Count" to pages.size),
        )
        writeOutline()

        val catalog = linkedMapOf<String, Any?>(
            "Type" to PdfName("Catalog"),
            "Pages" to PdfRef(PAGES, 0),
            "Outlines" to PdfRef(OUTLINES, 0),
        )
        if (fields.isNotEmpty()) {
            catalog["AcroForm"] = linkedMapOf<String, Any?>("Fields" to fields).apply {
                if (calculationOrder.isNotEmpty()) put("CO", calculationOrder)
                if (formResources.isNotEmpty()) put("DR", formResources)
                defaultAppearance?.let { put("DA", it) }
                if (needsAppearances) put("NeedAppearances", true)
            }
        }
        if (layers.isNotEmpty()) {
            val config = linkedMapOf<String, Any?>("OFF" to layersOff)
            if (layerOrder.isNotEmpty()) config["Order"] = layerOrder
            if (layerRadioGroups.isNotEmpty()) config["RBGroups"] = layerRadioGroups
            catalog["OCProperties"] = mapOf("OCGs" to layers, "D" to config)
        }
        writer.writeObject(CATALOG, catalog)
        writer.writeInfo(INFO, created)
        writer.finish(nextNumber, CATALOG, INFO)
    }

    // Copying

    /** Copies one document: its objects get new numbers, given as they're first referred to. */
    private inner class Copy(private val file: PdfFile) {
        private val numbers = HashMap<Int, Int>()
        private val queue = ArrayDeque<Int>()

        /** Entries to replace in an object, by its old number. */
        private val overrides = HashMap<Int, Map<String, Any?>>()

        fun run(name: String) {
            val root = file.root ?: throw MergeException(needsPassword = false)
            val sourcePages = file.pages { throw MergeException(needsPassword = false) }
            if (sourcePages.isEmpty()) throw MergeException(needsPassword = false)
            // Numbered first, so whatever refers to a page (links, annotations) gets the copy.
            sourcePages.forEach { numbers[it.ref.number] = nextNumber++ }
            val sourceFields = renameFields(root)

            for (page in sourcePages) {
                val dict = page.inherited + page.dict - DROPPED_PAGE_KEYS
                val copy = convertDict(dict) + ("Parent" to PdfRef(PAGES, 0))
                val number = numbers.getValue(page.ref.number)
                writer.writeObject(number, copy)
                pages += number
                drain()
            }

            fields.addAll(sourceFields.map(::convert))
            copyForm(root)
            copyLayers(root)
            outline += OutlineItem(
                title = textString(name.replace(PDF_EXTENSION, "")),
                destination = listOf(PdfRef(numbers.getValue(sourcePages.first().ref.number), 0), PdfName("XYZ"), null, null, null),
                action = null,
                style = emptyMap(),
                isOpen = true,
                children = outlineItems(file.outline()),
            )
            drain()
        }

        /** Writes the objects referred to so far. */
        private fun drain() {
            while (queue.isNotEmpty()) {
                val old = queue.removeFirst()
                val number = numbers.getValue(old)
                when (val value = file.resolve(PdfRef(old, 0))) {
                    is PdfStream -> {
                        val data = file.encodedStream(value) ?: throw MergeException(needsPassword = false)
                        // The length is written anew; a reference to it would copy an object nothing needs.
                        writer.writeStream(number, convertDict(value.dict - "Length"), data.length, data.writeTo)
                    }
                    is Map<*, *> -> {
                        @Suppress("UNCHECKED_CAST")
                        val dict = value as Map<String, Any?>
                        val type = (file.resolve(dict["Type"]) as? PdfName)?.value
                        // What belongs to the whole document isn't copied, as it would bring all of it.
                        val copy = if (type in DOCUMENT_TYPES) null else convertDict(dict + overrides[old].orEmpty())
                        writer.writeObject(number, copy)
                    }
                    // A reference to an object that isn't there means null.
                    else -> writer.writeObject(number, convert(value))
                }
            }
        }

        private fun ref(ref: PdfRef): PdfRef = PdfRef(
            numbers.getOrPut(ref.number) {
                queue += ref.number
                nextNumber++
            },
            0,
        )

        /** [value] with references to the copies, which are written by [drain]. */
        fun convert(value: Any?): Any? = when (value) {
            is PdfRef -> ref(value)
            is List<*> -> value.map(::convert)
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                convertDict(value as Map<String, Any?>)
            }
            is PdfName, is PdfString, is Number, is Boolean -> value
            else -> null
        }

        private fun convertDict(dict: Map<String, Any?>): Map<String, Any?> {
            // Viewers built on androidx.pdf follow only "go to" actions, so a link's bare destination becomes one.
            if ((file.resolve(dict["Subtype"]) as? PdfName)?.value == "Link" && "Dest" in dict && "A" !in dict) {
                val action = mapOf("S" to PdfName("GoTo"), "D" to convert(file.explicitDestination(dict["Dest"])))
                return convertDict(dict - "Dest") + ("A" to action)
            }
            val isGoTo = (file.resolve(dict["S"]) as? PdfName)?.value == "GoTo"
            return dict.mapValues { (key, value) ->
                // Links and outline items lead to destinations; a "go to" action's is D.
                if (key == "Dest" || (key == "D" && isGoTo)) convert(file.explicitDestination(value)) else convert(value)
            }
        }

        // Forms

        /**
         * The document's fields, before its pages are copied, as their widgets refer to them.
         * A name an earlier document took gets a number, as fields with the same name are one.
         */
        private fun renameFields(root: Map<String, Any?>): List<Any?> {
            val form = file.resolveDict(root["AcroForm"]) ?: return emptyList()
            val sourceFields = file.resolve(form["Fields"]) as? List<*> ?: return emptyList()
            val names = sourceFields.mapNotNull { field ->
                (file.resolve(file.resolveDict(field)?.get("T")) as? PdfString)?.let { decodePdfText(it.bytes) }
            }.toSet()
            val taken = HashSet<String>()
            for (field in sourceFields) {
                val dict = file.resolveDict(field) ?: continue
                val name = (file.resolve(dict["T"]) as? PdfString)?.let { decodePdfText(it.bytes) } ?: continue
                // Fields in a form are referred to; one that isn't can't be renamed.
                if (name !in fieldNames || field !is PdfRef) {
                    taken += name
                    continue
                }
                val unique = generateSequence(2) { it + 1 }.map { "${name}_$it" }
                    .first { it !in fieldNames && it !in names && it !in taken }
                overrides[field.number] = mapOf("T" to textString(unique))
                taken += unique
            }
            fieldNames += taken
            return sourceFields
        }

        private fun copyForm(root: Map<String, Any?>) {
            val form = file.resolveDict(root["AcroForm"]) ?: return
            (file.resolve(form["CO"]) as? List<*>)?.let { calculationOrder.addAll(it.map(::convert)) }
            if (file.resolve(form["NeedAppearances"]) == true) needsAppearances = true
            if (defaultAppearance == null) defaultAppearance = convert(file.resolve(form["DA"]))
            // Fonts and such that fields use; a name the first document gave keeps its meaning.
            val resources = file.resolveDict(form["DR"]) ?: return
            for ((category, value) in resources) {
                val entries = file.resolveDict(value)
                if (entries == null) {
                    formResources.putIfAbsent(category, convert(value))
                    continue
                }
                @Suppress("UNCHECKED_CAST")
                val merged = formResources.getOrPut(category) { LinkedHashMap<String, Any?>() } as? MutableMap<String, Any?> ?: continue
                entries.forEach { (name, resource) -> if (name !in merged) merged[name] = convert(resource) }
            }
        }

        // Layers

        /** Optional content, as the document's default configuration shows it. */
        private fun copyLayers(root: Map<String, Any?>) {
            val properties = file.resolveDict(root["OCProperties"]) ?: return
            val groups = file.resolve(properties["OCGs"]) as? List<*> ?: return
            val config = file.resolveDict(properties["D"]).orEmpty()
            fun list(key: String) = (file.resolve(config[key]) as? List<*>).orEmpty()
            val isOffByDefault = (file.resolve(config["BaseState"]) as? PdfName)?.value == "OFF"
            val off = if (isOffByDefault) groups.filter { it !in list("ON") } else list("OFF")
            layers.addAll(groups.map(::convert))
            layersOff.addAll(off.map(::convert))
            layerOrder.addAll(list("Order").map(::convert))
            layerRadioGroups.addAll(list("RBGroups").map(::convert))
        }

        // Outline

        private fun outlineItems(nodes: List<PdfOutlineNode>): List<OutlineItem> = nodes.map { node ->
            val dict = node.dict
            OutlineItem(
                title = file.resolve(dict["Title"]) as? PdfString ?: PdfString(ByteArray(0)),
                destination = dict["Dest"]?.let { convert(file.explicitDestination(it)) },
                action = dict["A"]?.let(::convert),
                style = STYLE.mapNotNull { key -> dict[key]?.let { key to convert(it) } }.toMap(),
                isOpen = node.isOpen,
                children = outlineItems(node.children),
            )
        }
    }

    /** An outline item to write, its destination and action already copied. */
    private class OutlineItem(
        val title: PdfString,
        val destination: Any?,
        val action: Any?,
        /** Color and bold or italic. */
        val style: Map<String, Any?>,
        val isOpen: Boolean,
        val children: List<OutlineItem>,
    ) {
        /** Items shown below it when it's open. */
        val descendantsShown: Int get() = children.sumOf { 1 + if (it.isOpen) it.descendantsShown else 0 }
    }

    private fun writeOutline() {
        val numbers = IdentityHashMap<OutlineItem, Int>()
        fun number(items: List<OutlineItem>) {
            for (item in items) {
                numbers[item] = nextNumber++
                number(item.children)
            }
        }
        number(outline)

        fun write(items: List<OutlineItem>, parent: Int) {
            items.forEachIndexed { index, item ->
                val dict = linkedMapOf<String, Any?>("Title" to item.title, "Parent" to PdfRef(parent, 0))
                items.getOrNull(index - 1)?.let { dict["Prev"] = PdfRef(numbers.getValue(it), 0) }
                items.getOrNull(index + 1)?.let { dict["Next"] = PdfRef(numbers.getValue(it), 0) }
                if (item.children.isNotEmpty()) {
                    dict["First"] = PdfRef(numbers.getValue(item.children.first()), 0)
                    dict["Last"] = PdfRef(numbers.getValue(item.children.last()), 0)
                    // Positive when open, negative when closed.
                    dict["Count"] = (if (item.isOpen) 1L else -1L) * item.descendantsShown
                }
                item.destination?.let { dict["Dest"] = it }
                item.action?.let { dict["A"] = it }
                dict += item.style
                writer.writeObject(numbers.getValue(item), dict)
                write(item.children, numbers.getValue(item))
            }
        }
        write(outline, OUTLINES)

        val shown = outline.sumOf { 1 + if (it.isOpen) it.descendantsShown else 0 }
        writer.writeObject(
            OUTLINES,
            mapOf(
                "Type" to PdfName("Outlines"),
                "First" to PdfRef(numbers.getValue(outline.first()), 0),
                "Last" to PdfRef(numbers.getValue(outline.last()), 0),
                "Count" to shown,
            ),
        )
    }

    private companion object {
        const val CATALOG = 1
        const val PAGES = 2
        const val OUTLINES = 3
        const val INFO = 4
        const val FIRST_OBJECT = 5

        /** The page tree is written anew; article threads belong to the whole document. */
        val DROPPED_PAGE_KEYS = setOf("Parent", "B")

        val DOCUMENT_TYPES = setOf("Catalog", "Pages", "Outlines", "StructTreeRoot", "StructElem")

        val STYLE = listOf("C", "F")

        val PDF_EXTENSION = Regex("""\.pdf$""", RegexOption.IGNORE_CASE)
    }
}
