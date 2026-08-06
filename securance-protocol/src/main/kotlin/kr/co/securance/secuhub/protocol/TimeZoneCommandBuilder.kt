package kr.co.securance.secuhub.protocol

/**
 * #7 SR_F_Schedule / #15 SR_P_Timezone — 타임존(스케줄) 26바이트 페이로드 빌더.
 *
 * 레거시 `SR_P_Timezone.SaveSchedule`/`SR_F_Schedule.SaveScheduleTime`(둘 다 동일 로직 중복)을
 * 바이트 단위로 그대로 이식한다 — [GateControlCommandBuilder]와 동일하게 "레거시 그대로 재현"
 * 원칙을 따른다(계획서 Phase 4, 2026-08-06 사용자 확인).
 *
 * [주의 — 의도적으로 "특이한" 인코딩까지 재현함] 시/분 값은 실제 이진값이 아니라, 레거시가
 * `Convert.ToByte(문자열, 16)`로 10진수 문자열을 그대로 16진수로 파싱해 바이트로 저장한다
 * (예: "13"시 → 0x13 = BCD 방식). 유효 범위(시 00~23, 분 00~59)의 두 자리 문자열은 전부 유효한
 * 16진 문자이므로 예외 없이 동작한다 — 장비가 기대하는 BCD 포맷으로 추정되며, "고쳐서"(실제
 * 이진값으로) 보내면 오히려 장비와 불일치할 위험이 있어 그대로 재현한다.
 */
object TimeZoneCommandBuilder {

    /** 요일 선택 — 레거시 `DayCheckToByteArray`의 `arrDay` 대응(일반 요일 7개 + 사용자 정의 2개). */
    data class DaySelection(
        val sunday: Boolean = false,
        val monday: Boolean = false,
        val tuesday: Boolean = false,
        val wednesday: Boolean = false,
        val thursday: Boolean = false,
        val friday: Boolean = false,
        val saturday: Boolean = false,
        val user1: Boolean = false,
        val user2: Boolean = false,
    ) {
        /** 레거시 `BitArray` 비트 배치 그대로: day1 bit7=일,6=월,5=화,4=수,3=목,2=금,1=토,0=user1 / day2 bit7=user2. */
        fun toBytes(): Pair<Byte, Byte> {
            var day1 = 0
            if (sunday) day1 = day1 or 0x80
            if (monday) day1 = day1 or 0x40
            if (tuesday) day1 = day1 or 0x20
            if (wednesday) day1 = day1 or 0x10
            if (thursday) day1 = day1 or 0x08
            if (friday) day1 = day1 or 0x04
            if (saturday) day1 = day1 or 0x02
            if (user1) day1 = day1 or 0x01
            val day2 = if (user2) 0x80 else 0x00
            return day1.toByte() to day2.toByte()
        }
    }

    /** 타임존 슬롯 1개(시작~종료 시각 + 적용 요일). 시/분은 "HH"/"mm" 두 자리 10진 문자열로 받는다. */
    data class Slot(
        val fromHour: Int,
        val fromMinute: Int,
        val toHour: Int,
        val toMinute: Int,
        val days: DaySelection = DaySelection(),
    ) {
        init {
            require(fromHour in 0..23) { "시작 시(fromHour)는 0~23 범위여야 합니다: $fromHour" }
            require(toHour in 0..23) { "종료 시(toHour)는 0~23 범위여야 합니다: $toHour" }
            require(fromMinute in 0..59) { "시작 분(fromMinute)은 0~59 범위여야 합니다: $fromMinute" }
            require(toMinute in 0..59) { "종료 분(toMinute)은 0~59 범위여야 합니다: $toMinute" }
        }
    }

    /**
     * 26바이트 타임존 페이로드를 만든다 — [0..1]=타임존ID(2바이트, big-endian),
     * 이후 슬롯 4개 × 6바이트(시작시/시작분/종료시/종료분/요일1/요일2).
     *
     * @param timezoneId DB에서 발급된(또는 발급될) 타임존 ID — Short 범위(0~32767)만 지원한다
     *   (레거시가 `BitConverter.GetBytes(Convert.ToInt16(...))`로 Int16 변환 후 뒤집는 것과 동일).
     * @param slots 정확히 4개(레거시 타임존1~4 고정 슬롯). 비어 있는 슬롯은 all-false Slot(0,0,0,0)로 채운다.
     */
    fun buildTimezoneHexData(timezoneId: Int, slots: List<Slot>): ByteArray {
        require(timezoneId in 0..Short.MAX_VALUE) { "타임존 ID는 0~${Short.MAX_VALUE} 범위여야 합니다: $timezoneId" }
        require(slots.size == 4) { "타임존 슬롯은 정확히 4개여야 합니다: ${slots.size}개" }

        val data = ByteArray(26)
        data[0] = ((timezoneId ushr 8) and 0xFF).toByte()
        data[1] = (timezoneId and 0xFF).toByte()

        for ((index, slot) in slots.withIndex()) {
            val offset = 2 + index * 6
            data[offset] = decimalStringAsHexByte(slot.fromHour)
            data[offset + 1] = decimalStringAsHexByte(slot.fromMinute)
            data[offset + 2] = decimalStringAsHexByte(slot.toHour)
            data[offset + 3] = decimalStringAsHexByte(slot.toMinute)
            val (day1, day2) = slot.days.toBytes()
            data[offset + 4] = day1
            data[offset + 5] = day2
        }
        return data
    }

    /** `Convert.ToByte(값.ToString("00"), 16)`과 동일 — 두 자리 10진 문자열을 16진수로 해석. */
    private fun decimalStringAsHexByte(value: Int): Byte =
        value.toString().padStart(2, '0').toInt(16).toByte()

    /**
     * #6 SetupSchedule의 "예약 슬롯" 참조 3바이트(레거시 `bScheduleUser`/`bScheduleSecu`) —
     * byte0=UI에서 고른 예약 슬롯 번호(운영모드 라디오 0x01~0x0A, 보안모드 라디오 0x00~0x03),
     * byte1~2=참조하는 [GateTimeZone]의 ID(big-endian 2바이트, `buildTimezoneHexData`의 [0..1]과 동일 값).
     */
    fun buildScheduleReference(scheduleSlot: Int, timezoneId: Int): ByteArray {
        require(scheduleSlot in 0..255) { "예약 슬롯 값은 0~255 범위여야 합니다: $scheduleSlot" }
        require(timezoneId in 0..Short.MAX_VALUE) { "타임존 ID는 0~${Short.MAX_VALUE} 범위여야 합니다: $timezoneId" }
        return byteArrayOf(
            scheduleSlot.toByte(),
            ((timezoneId ushr 8) and 0xFF).toByte(),
            (timezoneId and 0xFF).toByte(),
        )
    }
}
