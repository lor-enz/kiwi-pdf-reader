package kiwi.lorenz.pdfreader.create

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class JpegHeaderTest {
    private fun jpeg(width: Int, height: Int, type: Int): ByteArray {
        val output = ByteArrayOutputStream()
        ImageIO.write(BufferedImage(width, height, type), "jpg", output)
        return output.toByteArray()
    }

    @Test
    fun `reads size and colors`() {
        assertEquals(JpegHeader(640, 480, 3), JpegHeader.read(jpeg(640, 480, BufferedImage.TYPE_INT_RGB)))
        assertEquals(JpegHeader(20, 10, 1), JpegHeader.read(jpeg(20, 10, BufferedImage.TYPE_BYTE_GRAY)))
    }

    @Test
    fun `other files and broken JPEGs are not embedded as they are`() {
        val output = ByteArrayOutputStream()
        ImageIO.write(BufferedImage(20, 10, BufferedImage.TYPE_INT_RGB), "png", output)
        assertNull(JpegHeader.read(output.toByteArray()))
        assertNull(JpegHeader.read(jpeg(640, 480, BufferedImage.TYPE_INT_RGB).copyOf(20)))
        assertNull(JpegHeader.read(ByteArray(0)))
    }

    @Test
    fun `arithmetic coding is decoded instead`() {
        val bytes = jpeg(640, 480, BufferedImage.TYPE_INT_RGB)
        val sof = (2 until bytes.size).first { bytes[it - 1] == 0xFF.toByte() && bytes[it] == 0xC0.toByte() }
        bytes[sof] = 0xC9.toByte()
        assertNull(JpegHeader.read(bytes))
    }
}
