package kr.co.securance.secuhub.common.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HexCodecTest {

    @Test
    fun `바이트 배열을 대문자 16진 문자열로 변환한다`() {
        val bytes = byteArrayOf(0x02, 0x00, 0x2A, 0x04.toByte(), 0xFF.toByte())
        assertEquals("02002A04FF", HexCodec.toHex(bytes))
    }

    @Test
    fun `16진 문자열을 바이트 배열로 되돌린다 (라운드트립)`() {
        val original = byteArrayOf(0x02, 0x08, 0x03, 0x00, 0x7F)
        val hex = HexCodec.toHex(original)
        val restored = HexCodec.fromHex(hex)
        assertEquals(original.toList(), restored.toList())
    }

    @Test
    fun `구분자(대시)가 포함돼도 정상 변환한다`() {
        assertEquals(byteArrayOf(0x02, 0x03).toList(), HexCodec.fromHex("02-03").toList())
    }

    @Test
    fun `길이가 홀수면 예외를 던진다`() {
        assertFailsWith<IllegalArgumentException> { HexCodec.fromHex("020") }
    }
}
