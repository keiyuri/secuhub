package kr.co.securance.secuhub.domain.converter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YnConverterTest {

    private val converter = YnConverter()

    @Test
    fun `true는 Y로 false는 N으로 저장한다`() {
        assertEquals("Y", converter.convertToDatabaseColumn(true))
        assertEquals("N", converter.convertToDatabaseColumn(false))
        assertEquals("N", converter.convertToDatabaseColumn(null))
    }

    @Test
    fun `Y만 true로 읽고 그 외는 false로 읽는다`() {
        assertTrue(converter.convertToEntityAttribute("Y"))
        assertFalse(converter.convertToEntityAttribute("N"))
        assertFalse(converter.convertToEntityAttribute(null))
    }
}
