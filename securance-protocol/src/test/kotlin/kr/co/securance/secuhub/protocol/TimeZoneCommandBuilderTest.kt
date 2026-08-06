package kr.co.securance.secuhub.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TimeZoneCommandBuilderTest {

    private val emptySlot = TimeZoneCommandBuilder.Slot(0, 0, 0, 0)

    @Test
    fun `타임존ID는 big-endian 2바이트로 앞에 온다`() {
        val data = TimeZoneCommandBuilder.buildTimezoneHexData(0x0102, List(4) { emptySlot })
        assertEquals(0x01, data[0].toInt() and 0xFF)
        assertEquals(0x02, data[1].toInt() and 0xFF)
    }

    @Test
    fun `시분은 10진 문자열을 16진수로 파싱한 BCD 방식이다`() {
        // 13시 45분 -> 0x13, 0x45 (실제 이진값 13, 45가 아니다 — 레거시 재현)
        val slot = TimeZoneCommandBuilder.Slot(13, 45, 18, 5, TimeZoneCommandBuilder.DaySelection())
        val data = TimeZoneCommandBuilder.buildTimezoneHexData(1, listOf(slot, emptySlot, emptySlot, emptySlot))
        assertEquals(0x13, data[2].toInt() and 0xFF)
        assertEquals(0x45, data[3].toInt() and 0xFF)
        assertEquals(0x18, data[4].toInt() and 0xFF)
        assertEquals(0x05, data[5].toInt() and 0xFF)
    }

    @Test
    fun `요일 비트마스크는 일요일이 최상위비트다`() {
        val days = TimeZoneCommandBuilder.DaySelection(sunday = true, monday = true, saturday = true, user1 = true, user2 = true)
        val (day1, day2) = days.toBytes()
        assertEquals(0xC3, day1.toInt() and 0xFF) // 1100_0011: 일(0x80)+월(0x40)+토(0x02)+user1(0x01)
        assertEquals(0x80, day2.toInt() and 0xFF) // user2
    }

    @Test
    fun `4번째 슬롯은 offset 20부터 시작한다`() {
        val slot4 = TimeZoneCommandBuilder.Slot(9, 30, 17, 0, TimeZoneCommandBuilder.DaySelection(friday = true))
        val data = TimeZoneCommandBuilder.buildTimezoneHexData(5, listOf(emptySlot, emptySlot, emptySlot, slot4))
        assertEquals(0x09, data[20].toInt() and 0xFF)
        assertEquals(0x30, data[21].toInt() and 0xFF)
        assertEquals(0x17, data[22].toInt() and 0xFF)
        assertEquals(0x00, data[23].toInt() and 0xFF)
        assertEquals(0x04, data[24].toInt() and 0xFF) // 금요일
        assertEquals(26, data.size)
    }

    @Test
    fun `슬롯이 4개가 아니면 예외`() {
        assertFailsWith<IllegalArgumentException> {
            TimeZoneCommandBuilder.buildTimezoneHexData(1, listOf(emptySlot, emptySlot))
        }
    }

    @Test
    fun `시 범위를 벗어나면 예외`() {
        assertFailsWith<IllegalArgumentException> {
            TimeZoneCommandBuilder.Slot(24, 0, 0, 0)
        }
    }

    @Test
    fun `스케줄 참조 3바이트는 슬롯번호+타임존ID이다`() {
        val ref = TimeZoneCommandBuilder.buildScheduleReference(scheduleSlot = 0x02, timezoneId = 0x0304)
        assertEquals(byteArrayOf(0x02, 0x03, 0x04).toList(), ref.toList())
    }
}
