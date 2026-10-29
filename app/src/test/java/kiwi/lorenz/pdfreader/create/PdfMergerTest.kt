package kiwi.lorenz.pdfreader.create

import kiwi.lorenz.pdfreader.pdf.ByteArraySource
import kiwi.lorenz.pdfreader.pdf.OutlineItem
import kiwi.lorenz.pdfreader.pdf.PageSize
import kiwi.lorenz.pdfreader.pdf.PdfFile
import kiwi.lorenz.pdfreader.pdf.PdfMetadata
import kiwi.lorenz.pdfreader.pdf.PdfMetadataReader
import kiwi.lorenz.pdfreader.pdf.PdfName
import kiwi.lorenz.pdfreader.pdf.PdfRef
import kiwi.lorenz.pdfreader.pdf.PdfStream
import kiwi.lorenz.pdfreader.pdf.PdfString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Merged PDFs are read back with [PdfMetadataReader] and [PdfFile]. The fixtures are made by
 * `scripts/test/make-merge-fixtures.py` (`merge/`) and `make-metadata-fixtures.py` (`metadata/`).
 */
class PdfMergerTest {
    private val created = ZonedDateTime.of(2026, 10, 29, 14, 32, 0, 0, ZoneOffset.ofHours(1))

    private fun bytes(path: String) = checkNotNull(javaClass.getResourceAsStream("/$path")).use { it.readBytes() }

    /** Merges the fixtures at [paths], each with an optional password after a colon. */
    private fun merge(vararg paths: String): ByteArray {
        val output = ByteArrayOutputStream()
        PdfMerger(output).apply {
            for (path in paths) {
                val (name, password) = path.split(":").let { it[0] to it.getOrNull(1) }
                add(ByteArraySource(bytes(name)), password, name.substringAfterLast('/'))
            }
            finish(created)
        }
        return output.toByteArray()
    }

    @Test
    fun `entries are named without the extension, whatever its case`() {
        val output = ByteArrayOutputStream()
        PdfMerger(output).apply {
            for (name in listOf("Scan.PDF", "notes.pdf.pdf", "report")) add(ByteArraySource(bytes("metadata/table.pdf")), null, name)
            finish(created)
        }
        assertEquals(listOf("Scan", "notes.pdf", "report"), read(output.toByteArray()).outline.map { it.title })
    }

    private fun read(pdf: ByteArray, password: String? = null): PdfMetadata =
        PdfMetadataReader(ByteArraySource(pdf), password).read(withOutline = true)

    /** The merged file's pages, in order. */
    private fun PdfFile.pages(): List<Map<String, Any?>> =
        (resolve(resolveDict(root?.get("Pages"))?.get("Kids")) as List<*>).map { checkNotNull(resolveDict(it)) }

    private fun PdfFile.pageRefs(): List<PdfRef> =
        (resolve(resolveDict(root?.get("Pages"))?.get("Kids")) as List<*>).map { it as PdfRef }

    private fun PdfFile.text(value: Any?) = String((resolve(value) as PdfString).bytes, Charsets.ISO_8859_1)

    private fun PdfFile.contents(page: Map<String, Any?>) =
        String(checkNotNull(streamData(resolve(page["Contents"]) as PdfStream)), Charsets.ISO_8859_1)

    private fun List<OutlineItem>.shifted(by: Int): List<OutlineItem> =
        map { it.copy(page = it.page?.plus(by), children = it.children.shifted(by)) }

    @Test
    fun `pages follow in the order added, each document an entry of the outline`() {
        val merged = read(merge("metadata/outline.pdf", "merge/inherited.pdf", "metadata/no-info.pdf"))
        val outline = read(bytes("metadata/outline.pdf"))

        assertEquals(outline.pageCount!! + 3 + 1, merged.pageCount)
        // Named by their files, not their titles ("Electricity bill", "Inherited").
        assertEquals(listOf("outline", "inherited", "no-info"), merged.outline.map { it.title })
        assertEquals(listOf(0, 6, 9), merged.outline.map { it.page })
        // Only an entry with an outline below it can be open.
        assertEquals(listOf(true, false, false), merged.outline.map { it.isOpen })
        assertTrue(merged.outline.all { it.top == null })
        // A document's own outline is below its entry, leading to its pages in the merged file.
        assertEquals(outline.outline, merged.outline[0].children)
        assertEquals(emptyList<OutlineItem>(), merged.outline[2].children)
        assertEquals("PDF Penguin", merged.creator)
        assertNull(merged.title)
    }

    @Test
    fun `a later document's outline leads to its own pages`() {
        val merged = read(merge("merge/inherited.pdf", "metadata/outline.pdf"))
        val outline = read(bytes("metadata/outline.pdf")).outline

        assertEquals(outline.shifted(by = 3), merged.outline[1].children)
    }

    @Test
    fun `pages get what they inherited from the page tree`() {
        val file = PdfFile(ByteArraySource(merge("merge/inherited.pdf", "metadata/unicode.pdf")))
        val pages = file.pages()

        assertEquals(5, pages.size)
        for (page in pages.take(3)) {
            assertEquals(listOf(0L, 0L, 419.528, 595.276), file.resolve(page["MediaBox"]))
            val fonts = file.resolveDict(file.resolveDict(page["Resources"])?.get("Font"))
            assertEquals(PdfName("Helvetica"), file.resolveDict(fonts?.get("F1"))?.get("BaseFont"))
            assertTrue(file.contents(page).contains("inherited.pdf"))
        }
        assertEquals(listOf(null, 90L, 90L), pages.take(3).map { it["Rotate"] })
        // Turned by its page tree, Letter shows in landscape.
        assertEquals(90L, pages[3]["Rotate"])
        assertEquals(PageSize(612.0, 792.0).let { PageSize(it.height, it.width) }, read(merge("metadata/unicode.pdf")).pageSize)
    }

    @Test
    fun `protected documents are decrypted with their password`() {
        val pdf = merge("metadata/aes-256-password.pdf:kiwi", "metadata/rc4-128-password.pdf:kiwi", "metadata/aes-128.pdf")
        val merged = read(pdf)
        val file = PdfFile(ByteArraySource(pdf))

        assertFalse(merged.isLocked)
        assertEquals(6, merged.pageCount)
        assertEquals(listOf("aes-256-password", "rc4-128-password", "aes-128"), merged.outline.map { it.title })
        val texts = file.pages().map { file.contents(it) }
        assertTrue(texts[0].contains("Page 1 of 2"))
        assertTrue(texts[3].contains("Page 2 of 2"))
    }

    @Test
    fun `protected documents need their password`() {
        for (password in listOf("", ":wrong")) {
            val error = assertThrows(MergeException::class.java) {
                merge("metadata/table.pdf", "metadata/aes-256-password.pdf$password")
            }
            assertTrue(error.needsPassword)
        }
    }

    @Test
    fun `documents with object streams or broken offsets merge`() {
        val pdf = merge("metadata/streams.pdf", "metadata/aes-256.pdf", "metadata/broken-xref.pdf", "metadata/updated.pdf")
        val merged = read(pdf)
        val file = PdfFile(ByteArraySource(pdf))

        assertEquals(8, merged.pageCount)
        assertEquals(listOf("streams", "aes-256", "broken-xref", "updated"), merged.outline.map { it.title })
        assertTrue(file.pages().all { file.contents(it).contains("Page") })
    }

    @Test
    fun `what isn't a readable PDF can't be merged`() {
        val noise = ByteArray(5000) { (it * 31 % 251).toByte() }
        val truncated = bytes("metadata/table.pdf").let { it.copyOf(it.size / 3) }
        for (bytes in listOf(noise, ByteArray(0), truncated)) {
            val error = assertThrows(MergeException::class.java) {
                PdfMerger(ByteArrayOutputStream()).add(ByteArraySource(bytes), null, "broken.pdf")
            }
            assertFalse(error.needsPassword)
        }
    }

    @Test
    fun `named links lead to their pages`() {
        val pdf = merge("merge/form.pdf", "merge/named-links.pdf")
        val file = PdfFile(ByteArraySource(pdf))
        val pages = file.pageRefs()
        val links = file.resolve(file.pages()[1]["Annots"]) as List<*>

        // A bare destination becomes a "go to" action, which androidx.pdf follows.
        val link = checkNotNull(file.resolveDict(links[0]))
        assertNull(link["Dest"])
        val goTo = file.resolveDict(link["A"])
        assertEquals(PdfName("GoTo"), goTo?.get("S"))
        assertEquals(listOf(pages[2], PdfName("XYZ"), 0L, 500L, null), file.resolve(goTo?.get("D")))
        val action = file.resolveDict(file.resolveDict(links[1])?.get("A"))
        assertEquals(listOf(pages[3], PdfName("Fit")), file.resolve(action?.get("D")))
        assertNull(file.root?.get("Names"))

        val outline = read(pdf).outline[1].children
        assertEquals(listOf("Second page" to 2, "Third page" to 3), outline.map { it.title to it.page })
    }

    @Test
    fun `equal forms keep their own fields`() {
        val file = PdfFile(ByteArraySource(merge("merge/form.pdf", "merge/form.pdf", "merge/form.pdf")))
        val form = checkNotNull(file.resolveDict(file.root?.get("AcroForm")))
        val fields = (file.resolve(form["Fields"]) as List<*>).map { checkNotNull(file.resolveDict(it)) }

        assertEquals(listOf("name", "agree", "name_2", "agree_2", "name_3", "agree_3"), fields.map { file.text(it["T"]) })
        assertEquals(List(3) { "Pingu" }, fields.filter { it["FT"] == PdfName("Tx") }.map { file.text(it["V"]) })
        // The second page's widgets belong to the renamed fields.
        val widgets = (file.resolve(file.pages()[1]["Annots"]) as List<*>).map { checkNotNull(file.resolveDict(it)) }
        assertEquals("name_2", file.text(widgets[0]["T"]))
        assertEquals("agree_2", file.text(file.resolveDict(widgets[1]["Parent"])?.get("T")))
        assertEquals(file.pageRefs()[1], widgets[1]["P"])

        assertEquals(true, form["NeedAppearances"])
        assertEquals("/Helv 0 Tf 0 g", file.text(form["DA"]))
        val fonts = file.resolveDict(file.resolveDict(form["DR"])?.get("Font"))
        assertEquals(PdfName("Helvetica"), file.resolveDict(fonts?.get("Helv"))?.get("BaseFont"))
    }

    @Test
    fun `layers stay shown or hidden`() {
        val file = PdfFile(ByteArraySource(merge("merge/layers.pdf", "merge/layers-base-off.pdf")))
        val properties = checkNotNull(file.resolveDict(file.root?.get("OCProperties")))
        fun names(value: Any?) = (file.resolve(value) as List<*>).map { file.text(file.resolveDict(it)?.get("Name")) }

        assertEquals(listOf("Shown", "Hidden", "Shown", "Hidden"), names(properties["OCGs"]))
        val config = checkNotNull(file.resolveDict(properties["D"]))
        assertEquals(listOf("Hidden", "Hidden"), names(config["OFF"]))
        assertEquals(listOf("Shown", "Hidden", "Shown", "Hidden"), names(config["Order"]))
        // Each page's layers are the copies listed.
        val ocgs = file.resolve(properties["OCGs"]) as List<*>
        val used = file.pages().flatMap { page ->
            file.resolveDict(file.resolveDict(page["Resources"])?.get("Properties"))!!.values
        }
        assertEquals(ocgs.toSet(), used.toSet())
    }

    @Test
    fun `documents without layers or forms add none`() {
        val file = PdfFile(ByteArraySource(merge("metadata/table.pdf", "metadata/no-info.pdf")))

        assertNull(file.root?.get("AcroForm"))
        assertNull(file.root?.get("OCProperties"))
    }
}
