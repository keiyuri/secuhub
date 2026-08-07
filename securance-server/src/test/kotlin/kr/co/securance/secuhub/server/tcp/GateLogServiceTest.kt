package kr.co.securance.secuhub.server.tcp

import kotlinx.coroutines.runBlocking
import kr.co.securance.secuhub.common.util.HexCodec
import kr.co.securance.secuhub.domain.entity.GateLog
import kr.co.securance.secuhub.domain.repository.GateLogRepository
import kr.co.securance.secuhub.protocol.GatePacket
import kr.co.securance.secuhub.protocol.SpeedGatePacketCodec
import kr.co.securance.secuhub.protocol.SpeedGateProtocolConstants
import kr.co.securance.secuhub.server.db.GateDbWriteQueue
import kr.co.securance.secuhub.server.db.GateDbWriteTask
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

private fun <T> anyKt(): T {
    Mockito.any<T>()
    @Suppress("UNCHECKED_CAST")
    return null as T
}

/**
 * [GateLogService] 검증 — 사용자가 실제 수신했다고 제공한 36바이트 로그 엔트리 샘플
 * (`LogEventCodecTest`와 동일)을 감싼 합성 `GATE_LOG` 패킷으로 재조립·저장 로직을 확인한다.
 *
 * `GateDbWriteQueue`는 mock으로 가로채 [GateDbWriteTask]만 캡처하고, 실제 실행은 테스트에서
 * `task.execute()`를 직접 호출해 검증한다 — `GateConnectionRegistryImplTest`와 동일한 패턴.
 */
class GateLogServiceTest {

    // LogEventCodecTest와 동일한 실수신 로그 엔트리(2025-08-05 15:00:25, System, Door Open, functionCode=0xFF).
    private val sampleEntryHex =
        "1801050B0500000001FF2508051500250000000000000000000000000000000000000000"

    /**
     * 로그 엔트리 [entryHex]를 payload로 담은 합성 `GATE_LOG` 패킷.
     *
     * **주의**: 헤더 필드(주소/체크섬 등)는 [SpeedGatePacketCodec.buildPacket]의 기본 조립 규칙을
     * 그대로 쓸 뿐, "완전한 GATE_LOG 패킷"의 실물 샘플로 검증된 것은 아니다(GateLogService.kt
     * 클래스 KDoc 참고) — 이 테스트는 "Header 직후부터 dataCount*36바이트를 로그 엔트리로 읽는다"는
     * GateLogService의 파싱 가정 자체를 고정하는 목적이다.
     */
    private fun fakeLogPacket(entryHex: String = sampleEntryHex, dataCount: Int = 1): GatePacket {
        val address = SpeedGatePacketCodec.buildAddress(comSlot = 1, controller = 1, deviceNumber = 1)
        val payload = HexCodec.fromHex(entryHex)
        val raw = SpeedGatePacketCodec.buildPacket(
            address = address,
            command1 = SpeedGateProtocolConstants.Command1.SEND_DATA,
            command2 = SpeedGateProtocolConstants.Command2.READ,
            objectCode = SpeedGateProtocolConstants.ObjectCode.GATE_LOG,
            dataInfoLength = 0,
            dataCount = dataCount,
            dataLength = payload.size,
            payload = payload,
        )
        return GatePacket(
            command1 = SpeedGateProtocolConstants.Command1.SEND_DATA,
            command2 = SpeedGateProtocolConstants.Command2.READ,
            objectCode = SpeedGateProtocolConstants.ObjectCode.GATE_LOG,
            dataInfoLength = 0,
            dataCount = dataCount,
            dataLength = payload.size,
            raw = raw,
        )
    }

    @Test
    fun `GATE_LOG 패킷을 디코딩해 새 로그 엔트리를 저장한다`() {
        val repository = mock(GateLogRepository::class.java)
        `when`(
            repository.existsByDtlIpAndDtlLaneNoAndEventTimeAndEventTypeAndCodeAndErrCodeAndFunctionCode(
                anyString(), anyInt(), anyKt(), anyInt(), anyInt(), anyInt(), anyInt(),
            ),
        ).thenReturn(false)

        val dbWriteQueue = mock(GateDbWriteQueue::class.java)
        var capturedTask: GateDbWriteTask? = null
        doAnswer { invocation -> capturedTask = invocation.getArgument(0); null }
            .`when`(dbWriteQueue).enqueue(anyKt())

        val service = GateLogService(dbWriteQueue, repository)

        service.handle("192.168.0.40", fakeLogPacket())
        assertEquals("192.168.0.40", capturedTask!!.partitionKey)
        runBlocking { capturedTask!!.execute() }

        verify(repository).save(
            org.mockito.ArgumentMatchers.argThat { saved: GateLog ->
                saved.dtlIp == "192.168.0.40" &&
                    saved.dtlLaneNo == 0 &&
                    saved.eventType == 0x18 &&
                    saved.doorStatus == 0x01 &&
                    saved.functionCode == 255 &&
                    saved.eventTime == LocalDateTime.of(2025, 8, 5, 15, 0, 25) &&
                    saved.userData1 == "000000000000000000000000" &&
                    saved.userData2 == "0000000000000000"
            },
        )
    }

    @Test
    fun `이미 저장된 자연키와 동일한 엔트리는 다시 저장하지 않는다`() {
        val repository = mock(GateLogRepository::class.java)
        `when`(
            repository.existsByDtlIpAndDtlLaneNoAndEventTimeAndEventTypeAndCodeAndErrCodeAndFunctionCode(
                anyString(), anyInt(), anyKt(), anyInt(), anyInt(), anyInt(), anyInt(),
            ),
        ).thenReturn(true)

        val dbWriteQueue = mock(GateDbWriteQueue::class.java)
        var capturedTask: GateDbWriteTask? = null
        doAnswer { invocation -> capturedTask = invocation.getArgument(0); null }
            .`when`(dbWriteQueue).enqueue(anyKt())

        val service = GateLogService(dbWriteQueue, repository)

        service.handle("192.168.0.41", fakeLogPacket())
        runBlocking { capturedTask!!.execute() }

        verify(repository, never()).save(anyKt())
    }

    @Test
    fun `dataCount가 실제 수신 바이트로 커버 가능한 엔트리 수보다 크면 커버 가능한 만큼만 디코딩한다`() {
        // 손상/잘린 패킷 방어 — dataCount=2를 주장하지만 실제로는 엔트리 1건 분량만 실려 있다.
        // 전체를 버리지 않고, 안전하게 읽을 수 있는 1건까지는 정상 처리해야 한다.
        val repository = mock(GateLogRepository::class.java)
        `when`(
            repository.existsByDtlIpAndDtlLaneNoAndEventTimeAndEventTypeAndCodeAndErrCodeAndFunctionCode(
                anyString(), anyInt(), anyKt(), anyInt(), anyInt(), anyInt(), anyInt(),
            ),
        ).thenReturn(false)

        val dbWriteQueue = mock(GateDbWriteQueue::class.java)
        var capturedTask: GateDbWriteTask? = null
        doAnswer { invocation -> capturedTask = invocation.getArgument(0); null }
            .`when`(dbWriteQueue).enqueue(anyKt())

        val service = GateLogService(dbWriteQueue, repository)
        service.handle("192.168.0.42", fakeLogPacket(dataCount = 2))
        runBlocking { capturedTask!!.execute() }

        verify(repository, Mockito.times(1)).save(anyKt())
    }

    @Test
    fun `데이터 영역이 로그 엔트리 1건보다 짧으면 아무 것도 하지 않는다`() {
        val repository = mock(GateLogRepository::class.java)
        val dbWriteQueue = mock(GateDbWriteQueue::class.java)
        val service = GateLogService(dbWriteQueue, repository)

        val tooShort = GatePacket(
            command1 = SpeedGateProtocolConstants.Command1.SEND_DATA,
            command2 = SpeedGateProtocolConstants.Command2.READ,
            objectCode = SpeedGateProtocolConstants.ObjectCode.GATE_LOG,
            dataInfoLength = 0,
            dataCount = 1,
            dataLength = 0,
            raw = ByteArray(SpeedGateProtocolConstants.HEADER_LENGTH + SpeedGateProtocolConstants.TAIL_LENGTH),
        )

        service.handle("192.168.0.43", tooShort)

        verify(dbWriteQueue, never()).enqueue(anyKt())
    }
}
