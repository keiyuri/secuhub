package kr.co.securance.secuhub.protocol

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [SpeedGateLogCodec] 테스트(3차 스프린트 "0x61 로그 패킷" 항목).
 *
 * `SpeedGate_Log_protocol_20260728_01.md`의 36바이트 고정 구조 예시(연/월/일/시/분/초 BCD 인코딩은
 * `FastGate Protocol Ver1_2020102601_01.md`의 TimeSync 예시 `0x20,0x08,0x11,...=2020-08-11`과
 * 동일한 BCD 규칙을 공유한다)를 기준으로 오프셋 단위로 검증한다.
 */
class SpeedGateLogCodecTest {

    /** Event Time을 제외한 나머지 필드를 채운 36바이트 엔트리를 만든다. */
    private fun buildEntry(
        eventType: Byte = SpeedGateProtocolConstants.LogEventType.ACCESS,
        objectCode: Byte = 0x01,
        code: Byte = 0x01,
        errCode: Byte = 0x00,
        operationMode: Byte = 0x01,
        readerType: Byte = 0x00,
        moduleNumber: Int = 3,
        readerNumber: Int = 1,
        doorStatus: Byte = SpeedGateProtocolConstants.LogDoorStatus.ACTIVE,
        functionCode: Int = 0,
        year: Int = 2026,
        month: Int = 8,
        day: Int = 10,
        hour: Int = 21,
        minute: Int = 47,
        second: Int = 13,
        userData1: ByteArray = ByteArray(12) { (it + 1).toByte() },
        userData2: ByteArray = ByteArray(8) { (it + 1).toByte() },
    ): ByteArray {
        val entry = ByteArray(SpeedGateProtocolConstants.LOG_ENTRY_LENGTH)
        entry[SpeedGateProtocolConstants.LogEntryOffset.EVENT_TYPE] = eventType
        entry[SpeedGateProtocolConstants.LogEntryOffset.OBJECT_CODE] = objectCode
        entry[SpeedGateProtocolConstants.LogEntryOffset.CODE] = code
        entry[SpeedGateProtocolConstants.LogEntryOffset.ERR_CODE] = errCode
        entry[SpeedGateProtocolConstants.LogEntryOffset.OPERATION_MODE] = operationMode
        entry[SpeedGateProtocolConstants.LogEntryOffset.READER_TYPE] = readerType
        entry[SpeedGateProtocolConstants.LogEntryOffset.MODULE_NUMBER] = moduleNumber.toByte()
        entry[SpeedGateProtocolConstants.LogEntryOffset.READER_NUMBER] = readerNumber.toByte()
        entry[SpeedGateProtocolConstants.LogEntryOffset.DOOR_STATUS] = doorStatus
        entry[SpeedGateProtocolConstants.LogEntryOffset.FUNCTION_CODE] = functionCode.toByte()

        val timeOffset = SpeedGateProtocolConstants.LogEntryOffset.EVENT_TIME
        entry[timeOffset] = SpeedGatePacketCodec.toBcd(year % 100)
        entry[timeOffset + 1] = SpeedGatePacketCodec.toBcd(month)
        entry[timeOffset + 2] = SpeedGatePacketCodec.toBcd(day)
        entry[timeOffset + 3] = SpeedGatePacketCodec.toBcd(hour)
        entry[timeOffset + 4] = SpeedGatePacketCodec.toBcd(minute)
        entry[timeOffset + 5] = SpeedGatePacketCodec.toBcd(second)

        userData1.copyInto(entry, SpeedGateProtocolConstants.LogEntryOffset.USER_DATA1)
        userData2.copyInto(entry, SpeedGateProtocolConstants.LogEntryOffset.USER_DATA2)
        return entry
    }

    @Test
    fun `36바이트 엔트리를 오프셋대로 파싱한다`() {
        val raw = buildEntry()

        val decoded = SpeedGateLogCodec.decodeEntry(raw)

        assertEquals(SpeedGateProtocolConstants.LogEventType.ACCESS, decoded.eventType)
        assertEquals(1, decoded.objectCode.toInt())
        assertEquals(1, decoded.code.toInt())
        assertEquals(0, decoded.errCode.toInt())
        assertEquals(1, decoded.operationMode.toInt())
        assertEquals(3, decoded.moduleNumber)
        assertEquals(1, decoded.readerNumber)
        assertEquals(SpeedGateProtocolConstants.LogDoorStatus.ACTIVE, decoded.doorStatus)
        assertEquals(0, decoded.functionCode)
        assertEquals(LocalDateTime.of(2026, 8, 10, 21, 47, 13), decoded.eventTime)
        assertEquals((1..12).map { it.toByte() }, decoded.userData1.toList())
        assertEquals((1..8).map { it.toByte() }, decoded.userData2.toList())
        assertTrue(raw.contentEquals(decoded.raw))
    }

    @Test
    fun `문서 예시의 BCD 시각을 그대로 디코딩한다`() {
        // FastGate Protocol 문서의 TimeSync 예시: 0x20,0x08,0x11,0x02,0x21,0x47,0x13 = 2020-08-11 21:47:13
        // (Weekday 바이트만 제외하면 로그 Event Time과 동일한 BCD 규칙).
        val raw = buildEntry(year = 2020, month = 8, day = 11, hour = 21, minute = 47, second = 13)

        val decoded = SpeedGateLogCodec.decodeEntry(raw)

        assertEquals(LocalDateTime.of(2020, 8, 11, 21, 47, 13), decoded.eventTime)
    }

    @Test
    fun `BCD 값이 달력 범위를 벗어나면 예외 대신 null을 반환한다`() {
        val raw = buildEntry()
        // Month 오프셋에 달력으로 성립하지 않는 BCD 값(0x13 → 13월)을 직접 주입한다.
        raw[SpeedGateProtocolConstants.LogEntryOffset.EVENT_TIME + 1] = 0x13

        val decoded = SpeedGateLogCodec.decodeEntry(raw)

        assertNull(decoded.eventTime)
    }

    @Test
    fun `36바이트가 아니면 예외를 던진다`() {
        assertFailsWith<IllegalArgumentException> {
            SpeedGateLogCodec.decodeEntry(ByteArray(35))
        }
    }

    @Test
    fun `dataCount 개수만큼 연속된 엔트리를 디코딩한다`() {
        val entries = listOf(
            buildEntry(moduleNumber = 1, functionCode = 10),
            buildEntry(moduleNumber = 2, functionCode = 20),
            buildEntry(moduleNumber = 3, functionCode = 30),
        )
        val data = entries.reduce { acc, e -> acc + e }

        val decoded = SpeedGateLogCodec.decodeEntries(data, entryCount = 3)

        assertEquals(3, decoded.size)
        assertEquals(listOf(1, 2, 3), decoded.map { it.moduleNumber })
        assertEquals(listOf(10, 20, 30), decoded.map { it.functionCode })
    }

    @Test
    fun `데이터가 부족해 잘린 엔트리는 버리고 그 앞까지만 반환한다`() {
        val entries = listOf(buildEntry(moduleNumber = 1), buildEntry(moduleNumber = 2))
        val truncated = (entries[0] + entries[1]).copyOfRange(0, SpeedGateProtocolConstants.LOG_ENTRY_LENGTH + 10)

        val decoded = SpeedGateLogCodec.decodeEntries(truncated, entryCount = 2)

        assertEquals(1, decoded.size)
        assertEquals(1, decoded[0].moduleNumber)
    }
}
