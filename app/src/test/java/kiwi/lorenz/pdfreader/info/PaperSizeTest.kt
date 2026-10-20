package kiwi.lorenz.pdfreader.info

import kiwi.lorenz.pdfreader.pdf.PageSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PaperSizeTest {
    @Test
    fun `names common sizes in both orientations`() {
        assertEquals(NamedSize(Paper.A4, isLandscape = false), PageSize(595.276, 841.89).paper())
        assertEquals(NamedSize(Paper.A4, isLandscape = true), PageSize(842.0, 595.0).paper())
        assertEquals(NamedSize(Paper.LETTER, isLandscape = false), PageSize(612.0, 792.0).paper())
        assertEquals(NamedSize(Paper.LEGAL, isLandscape = true), PageSize(1008.0, 612.0).paper())
    }

    @Test
    fun `other sizes have no name`() {
        // A 16:9 slide.
        assertNull(PageSize(960.0, 540.0).paper())
        // A4 trimmed by a crop box.
        assertNull(PageSize(560.0, 800.0).paper())
    }
}
