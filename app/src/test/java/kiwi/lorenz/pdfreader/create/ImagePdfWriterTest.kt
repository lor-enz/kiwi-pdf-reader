package kiwi.lorenz.pdfreader.create

import kiwi.lorenz.pdfreader.pdf.ByteArraySource
import kiwi.lorenz.pdfreader.pdf.PageSize
import kiwi.lorenz.pdfreader.pdf.PdfMetadata
import kiwi.lorenz.pdfreader.pdf.PdfMetadataReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.zip.DeflaterOutputStream
import javax.imageio.ImageIO

/** Written PDFs are read back with [PdfMetadataReader]. */
class ImagePdfWriterTest {
    private val created = ZonedDateTime.of(2026, 10, 29, 14, 32, 0, 0, ZoneOffset.ofHours(1))

    private fun write(vararg images: PdfImage): ByteArray {
        val output = ByteArrayOutputStream()
        ImagePdfWriter(output).apply {
            images.forEach(::addPage)
            finish(created)
        }
        return output.toByteArray()
    }

    private fun read(pdf: ByteArray): PdfMetadata = PdfMetadataReader(ByteArraySource(pdf)).read()

    private fun photo(width: Int, height: Int, orientation: Int = 1): PdfImage {
        val jpeg = ByteArrayOutputStream()
        ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "jpg", jpeg)
        val header = checkNotNull(JpegHeader.read(jpeg.toByteArray()))
        return PdfImage(header.width, header.height, header.components, PdfImage.Encoding.JPEG, jpeg.toByteArray(), orientation)
    }

    private fun screenshot(width: Int, height: Int): PdfImage {
        val samples = ByteArrayOutputStream()
        DeflaterOutputStream(samples).use { it.write(ByteArray(width * height * 3) { 0x7F }) }
        return PdfImage(width, height, 3, PdfImage.Encoding.FLATE, samples.toByteArray())
    }

    @Test
    fun `one page per image, as wide as A4`() {
        val metadata = read(write(photo(400, 300), screenshot(108, 240)))

        assertEquals(2, metadata.pageCount)
        assertEquals(PageSize(595.28, 446.46), metadata.pageSize)
        assertEquals("PDF Penguin", metadata.creator)
        assertEquals("PDF Penguin", metadata.producer)
        assertEquals(Instant.parse("2026-10-29T13:32:00Z"), metadata.created)
        assertNull(metadata.title)
    }

    @Test
    fun `a photo turned by a quarter gets a page of its shown proportions`() {
        for (orientation in 5..8) {
            assertEquals(PageSize(595.28, 793.71), read(write(photo(400, 300, orientation))).pageSize)
        }
        for (orientation in 1..4) {
            assertEquals(PageSize(595.28, 446.46), read(write(photo(400, 300, orientation))).pageSize)
        }
    }

    @Test
    fun `images are embedded as they are`() {
        val image = photo(40, 30)
        val pdf = write(image)
        val text = String(pdf, Charsets.ISO_8859_1)

        assertTrue(text.contains("/Width 40 /Height 30 /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode"))
        assertTrue(text.contains(String(image.data, Charsets.ISO_8859_1)))
    }

    @Test
    fun `the placement turns the image's corners as EXIF says`() {
        // Where the image's top left corner (0, 1 in its unit square) ends up on a 2 × 1 page.
        fun topLeft(orientation: Int): Pair<Double, Double> {
            val matrix = ImagePdfWriter.placement(orientation, 2.0, 1.0)
            return (matrix[2] + matrix[4]) to (matrix[3] + matrix[5])
        }
        // 1: top left, 6: turned clockwise, so the top left goes to the top right.
        assertEquals(0.0 to 1.0, topLeft(1))
        assertEquals(2.0 to 1.0, topLeft(2))
        assertEquals(2.0 to 0.0, topLeft(3))
        assertEquals(0.0 to 0.0, topLeft(4))
        assertEquals(0.0 to 1.0, topLeft(5))
        assertEquals(2.0 to 1.0, topLeft(6))
        assertEquals(2.0 to 0.0, topLeft(7))
        assertEquals(0.0 to 0.0, topLeft(8))
    }
}
