package dev.classgraph.toolwindow

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageExportTest {
    @Test
    fun `decodes base64 and percent-encoded data urls`() {
        assertArrayEquals(byteArrayOf(1, 2, 3), ImageExport.decode("data:image/png;base64,AQID"))
        assertEquals("<svg a=\"1+1\"/>", ImageExport.decode("data:image/svg+xml;charset=utf-8,%3Csvg%20a%3D%221+1%22%2F%3E")!!.decodeToString())
        assertNull(ImageExport.decode("not a data url"))
    }
}
