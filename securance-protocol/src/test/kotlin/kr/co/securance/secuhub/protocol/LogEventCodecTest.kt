package kr.co.securance.secuhub.protocol

import kr.co.securance.secuhub.common.util.HexCodec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/**
 * [LogEventCodec] 검증 — 사용자가 실제 수신했다고 제공한 36바이트 로그 엔트리 샘플로 확인한다.
 * `SpeedGate_Log_protocol_20260728_01.md` 문서의 필드 오프셋/BCD 규칙을 그대로 반영했는지가
 * 핵심이며, 회귀 방지를 위해 이 샘플을 고정 테스트로 남긴다.
 */
class LogEventCodecTest {

    // 사용자 제공 실수신 로그 엔트리(2026-08-07): System 이벤트, 도어 Open, 2025-08-05 15:00:25.
    private val sampleHex =
        "1801050B0500000001FF2508051500250000000000000000000000000000000000000000"

    @Test
    fun `실수신 로그 샘플을 문서 필드 순서대로 정확히 디코딩한다`() {
        val entry = HexCodec.fromHex(sampleHex)
        assertEquals(36, entry.size)

        val event = LogEventCodec.decode(entry)

        assertEquals(LogEventCodec.EventType.SYSTEM, event.eventType)
        assertEquals(0x01.toByte(), event.objectCode)
        assertEquals(0x05.toByte(), event.code)
        assertEquals(0x0B.toByte(), event.errCode)
        assertEquals(0x05.toByte(), event.operationMode)
        assertEquals(0x00.toByte(), event.readerType)
        assertEquals(0, event.moduleNumber)
        assertEquals(0, event.readerNumber)
        assertEquals(LogEventCodec.DoorStatus.ACTIVE_OPEN, event.doorStatus)
        assertEquals(255, event.functionCode)
        assertEquals(LocalDateTime.of(2025, 8, 5, 15, 0, 25), event.eventTime)
        assertEquals("000000000000000000000000", event.userData1Hex)
        assertEquals("0000000000000000", event.userData2Hex)
    }

    @Test
    fun `decodeAll은 dataCount개의 로그 엔트리를 순서대로 반환한다`() {
        val entry = HexCodec.fromHex(sampleHex)
        val doubled = entry + entry

        val events = LogEventCodec.decodeAll(doubled, entryCount = 2)

        assertEquals(2, events.size)
        assertEquals(events[0], events[1])
        assertEquals(LocalDateTime.of(2025, 8, 5, 15, 0, 25), events[0].eventTime)
    }

    @Test
    fun `엔트리 길이가 36바이트가 아니면 예외를 던진다`() {
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            LogEventCodec.decode(ByteArray(10))
        }
    }
}
