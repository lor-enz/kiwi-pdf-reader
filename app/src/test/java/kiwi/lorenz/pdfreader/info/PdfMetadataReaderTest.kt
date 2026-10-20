package kiwi.lorenz.pdfreader.info

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

/** The fixtures are made by `scripts/test/make-metadata-fixtures.py`. */
class PdfMetadataReaderTest {
    private fun read(name: String, password: String? = null, withOutline: Boolean = false): PdfMetadata {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/metadata/$name")).use { it.readBytes() }
        return PdfMetadataReader(ByteArraySource(bytes), password).read(withOutline)
    }

    private val a4 = PageSize(595.276, 841.89)

    private fun assertBill(metadata: PdfMetadata, title: String = "Electricity bill") {
        assertEquals(title, metadata.title)
        assertEquals("Kiwi Power Ltd", metadata.author)
        assertEquals("March 2024", metadata.subject)
        assertEquals("Writer", metadata.creator)
        assertEquals("LibreOffice 7.6", metadata.producer)
        assertEquals(Instant.parse("2024-03-05T13:30:00Z"), metadata.created)
        assertEquals(Instant.parse("2024-03-06T09:00:00Z"), metadata.modified)
        assertEquals(2, metadata.pageCount)
    }

    @Test
    fun `reads a cross-reference table`() {
        val metadata = read("table.pdf")
        assertBill(metadata)
        assertEquals(a4, metadata.pageSize)
    }

    @Test
    fun `reads object streams through a compressed cross-reference stream`() {
        val metadata = read("streams.pdf")
        assertBill(metadata)
        assertEquals(a4, metadata.pageSize)
    }

    @Test
    fun `decodes UTF-16 and turns pages by their inherited rotation`() {
        val metadata = read("unicode.pdf")
        assertEquals("Stromrechnung März – 2024 ✓", metadata.title)
        assertEquals("Zoë Ångström", metadata.author)
        assertEquals(PageSize(792.0, 612.0), metadata.pageSize)
    }

    @Test
    fun `the newest update wins`() {
        val metadata = read("updated.pdf")
        assertEquals("Final", metadata.title)
        // The new info dictionary replaces the old one as a whole.
        assertNull(metadata.subject)
        assertEquals(2, metadata.pageCount)
    }

    @Test
    fun `decrypts files that open without a password`() {
        for (name in listOf("rc4-40.pdf", "aes-128.pdf", "aes-256.pdf")) {
            val metadata = read(name)
            assertBill(metadata)
            assertEquals(a4, metadata.pageSize)
            assertFalse(name, metadata.isLocked)
        }
    }

    @Test
    fun `decrypts protected files with their password or the permissions one`() {
        for (name in listOf("rc4-128-password.pdf", "aes-256-password.pdf")) {
            assertBill(read(name, "kiwi"))
            assertBill(read(name, "owner"))
        }
    }

    @Test
    fun `leaves out the strings of protected files without their password`() {
        for (name in listOf("rc4-128-password.pdf", "aes-256-password.pdf")) {
            for (password in listOf(null, "wrong")) {
                val metadata = read(name, password)
                assertTrue(name, metadata.isLocked)
                assertNull(metadata.title)
                assertNull(metadata.created)
                assertEquals(2, metadata.pageCount)
                assertEquals(a4, metadata.pageSize)
            }
        }
    }

    @Test
    fun `finds objects by scanning when the offsets are wrong`() {
        val metadata = read("broken-xref.pdf")
        assertBill(metadata, title = "Shifted")
        assertEquals(a4, metadata.pageSize)
    }

    @Test
    fun `without an info dictionary only the pages are known`() {
        val metadata = read("no-info.pdf")
        assertEquals(PdfMetadata(pageCount = 1, pageSize = a4), metadata)
    }

    @Test
    fun `garbage gives nothing instead of failing`() {
        val noise = ByteArray(5000) { (it * 31 % 251).toByte() }
        assertEquals(PdfMetadata(), PdfMetadataReader(ByteArraySource(noise)).read())
        assertEquals(PdfMetadata(), PdfMetadataReader(ByteArraySource(ByteArray(0))).read())
        val truncated = checkNotNull(javaClass.getResourceAsStream("/metadata/streams.pdf")).use { it.readBytes() }
        PdfMetadataReader(ByteArraySource(truncated.copyOf(truncated.size / 2))).read()
    }

    @Test
    fun `reads a small file written by hand`() {
        val pdf = """
            %PDF-1.4
            1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj
            2 0 obj << /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 612 1008] >> endobj
            3 0 obj << /Type /Page /Parent 2 0 R /CropBox [10 10 602 782] >> endobj
            4 0 obj << /Title (Caf\351 \(draft\)\\n) /Author <FEFF00C5> /Subject () >> endobj
            trailer << /Root 1 0 R /Info 4 0 R >>
            %%EOF
        """.trimIndent().replace("\\\\", "\\").toByteArray(Charsets.ISO_8859_1)
        val metadata = PdfMetadataReader(ByteArraySource(pdf)).read()
        // PDFDocEncoding and escapes; the line break becomes a space and is trimmed.
        assertEquals("Café (draft)", metadata.title)
        assertEquals("Å", metadata.author)
        assertNull(metadata.subject)
        assertEquals(PageSize(592.0, 772.0), metadata.pageSize)
    }

    private fun item(title: String, page: Int?, top: Double? = null, isOpen: Boolean = false, vararg children: OutlineItem) =
        OutlineItem(title, page, top?.toFloat(), isOpen, children.toList())

    @Test
    fun `reads a nested outline with explicit destinations`() {
        val a4Top = 841.89
        val expected = listOf(
            item(
                "1 Introduction", 0, isOpen = true, children = arrayOf(
                    item("1.1 Motivation", 0, a4Top - 600),
                    // Through a "go to" action.
                    item("1.2 Scope", 1, a4Top - 700),
                ),
            ),
            item(
                "2 Methods", 2, children = arrayOf(
                    item("2.1 Data", 3, a4Top - 500, isOpen = true, item("2.1.1 Sources", 3, a4Top - 400)),
                ),
            ),
            // Measured on a turned page, the top would be somewhere else.
            item("3 Turned page", 4),
            item(
                "Appendix", null, isOpen = true, children = arrayOf(
                    item("Website", null),
                    item("A Tables", 5),
                ),
            ),
        )
        assertEquals(expected, read("outline.pdf", withOutline = true).outline)
    }

    @Test
    fun `finds named destinations in name trees and dictionaries`() {
        val expected = listOf(
            item("Name tree", 1, 841.89 - 800),
            item("Old dictionary", 2),
            item("Action with name", 0, 0.0),
            item("Missing name", null),
        )
        assertEquals(expected, read("outline-named.pdf", withOutline = true).outline)
    }

    @Test
    fun `reads the outline only when asked`() {
        assertEquals(emptyList<OutlineItem>(), read("outline.pdf").outline)
        assertEquals(emptyList<OutlineItem>(), read("table.pdf", withOutline = true).outline)
    }

    @Test
    fun `decrypts the outline of protected files only with their password`() {
        for (name in listOf("rc4-128-password.pdf", "aes-256-password.pdf")) {
            assertEquals(name, listOf(item("Übersicht ✓", 1)), read(name, "kiwi", withOutline = true).outline)
            assertEquals(name, emptyList<OutlineItem>(), read(name, withOutline = true).outline)
        }
    }

    @Test
    fun `an outline that loops ends where it repeats`() {
        val pdf = """
            %PDF-1.4
            1 0 obj << /Type /Catalog /Pages 2 0 R /Outlines 4 0 R >> endobj
            2 0 obj << /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 612 792] >> endobj
            3 0 obj << /Type /Page /Parent 2 0 R >> endobj
            4 0 obj << /First 5 0 R >> endobj
            5 0 obj << /Title (Start) /Dest [3 0 R /XYZ 0 792 0] /Next 6 0 R /First 5 0 R /Count 1 >> endobj
            6 0 obj << /Title (Again) /Dest [0 /Fit] /Next 5 0 R >> endobj
            trailer << /Root 1 0 R >>
            %%EOF
        """.trimIndent().toByteArray(Charsets.ISO_8859_1)
        val outline = PdfMetadataReader(ByteArraySource(pdf)).read(withOutline = true).outline
        // A page number instead of a reference works too.
        assertEquals(listOf(item("Start", 0, 0.0, isOpen = true), item("Again", 0)), outline)
    }

    @Test
    fun `parses dates with and without time zones`() {
        val berlin = ZoneOffset.ofHours(2)
        assertEquals(Instant.parse("2024-03-05T13:30:00Z"), parsePdfDate("D:20240305143000+01'00'"))
        assertEquals(Instant.parse("2024-03-05T19:00:00Z"), parsePdfDate("D:20240305143000-04'30"))
        assertEquals(Instant.parse("2024-03-05T14:30:00Z"), parsePdfDate("D:20240305143000Z00'00'"))
        assertEquals(Instant.parse("2024-03-05T12:30:00Z"), parsePdfDate("D:20240305143000", berlin))
        assertEquals(Instant.parse("2023-12-31T22:00:00Z"), parsePdfDate("D:2024", berlin))
        assertEquals(Instant.parse("2024-03-05T14:30:00Z"), parsePdfDate("20240305143000Z"))
        assertNull(parsePdfDate("yesterday"))
        assertNull(parsePdfDate("D:20241305"))
    }
}
