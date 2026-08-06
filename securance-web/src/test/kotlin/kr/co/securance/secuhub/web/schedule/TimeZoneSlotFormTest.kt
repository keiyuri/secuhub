package kr.co.securance.secuhub.web.schedule

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TimeZoneSlotFormTest {

    @Test
    fun `요일이 하나도 선택되지 않으면 미사용 슬롯으로 취급한다`() {
        assertTrue(TimeZoneSlotForm().isBlank())
    }

    @Test
    fun `요일이 하나라도 선택되면 미사용 슬롯이 아니다`() {
        assertFalse(TimeZoneSlotForm(monday = true).isBlank())
    }

    @Test
    fun `dayLabels는 일-월-화-수-목-금-토 순서를 유지한다`() {
        // 레거시 UI 체크박스 순서와 동일해야 tb_gate_timezone.timezone_day*에 저장되는
        // "일,화,금" 같은 문자열이 화면 표시 순서와 어긋나지 않는다.
        val form = TimeZoneSlotForm(sunday = true, tuesday = true, friday = true)
        assertEquals(listOf("일", "화", "금"), form.dayLabels())
    }

    @Test
    fun `toBuilderSlot은 폼 값을 그대로 Slot에 매핑한다`() {
        val form = TimeZoneSlotForm(fromHour = 9, fromMinute = 30, toHour = 18, toMinute = 0, monday = true)
        val slot = form.toBuilderSlot()

        assertEquals(9, slot.fromHour)
        assertEquals(30, slot.fromMinute)
        assertEquals(18, slot.toHour)
        assertEquals(0, slot.toMinute)
        assertTrue(slot.days.monday)
        assertFalse(slot.days.tuesday)
    }
}
