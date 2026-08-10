package kr.co.securance.secuhub.server.control

import kotlinx.coroutines.Dispatchers
import kr.co.securance.secuhub.common.util.HexCodec
import kr.co.securance.secuhub.domain.entity.DataSend
import kr.co.securance.secuhub.domain.repository.DataReceiveAnalysisRepository
import kr.co.securance.secuhub.domain.repository.DataSendRepository
import kr.co.securance.secuhub.domain.repository.GateLaneInfo
import kr.co.securance.secuhub.domain.repository.NetStateRepository
import kr.co.securance.secuhub.protocol.GateProtocolCodecRegistry
import kr.co.securance.secuhub.protocol.SpeedFlapGateProtocolCodec
import kr.co.securance.secuhub.protocol.SpeedGateControlCommand
import kr.co.securance.secuhub.protocol.SpeedGatePacketCodec
import kr.co.securance.secuhub.protocol.SpeedGateProtocolConstants
import kr.co.securance.secuhub.protocol.SpeedGateSecurityMode
import kr.co.securance.secuhub.server.connection.GateConnectionActor
import kr.co.securance.secuhub.server.connection.GateConnectionRegistryImpl
import kr.co.securance.secuhub.server.connection.GateConnectionState
import kr.co.securance.secuhub.server.db.GateDbWriteQueue
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import reactor.netty.Connection
import reactor.netty.NettyOutbound
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * QUEUED 접수 경로([QueuedGateControlService])와 장애 해제([GateFaultResolutionService]) 검증
 * (2차 스프린트 1·3번 항목).
 */
class QueuedControlAndResolutionTest {

    private val codec = SpeedFlapGateProtocolCodec()
    private val codecRegistry = GateProtocolCodecRegistry(listOf(codec))

    private fun registryWith(dtlIp: String?): GateConnectionRegistryImpl {
        val registry = GateConnectionRegistryImpl(
            mock(NetStateRepository::class.java),
            GateDbWriteQueue(shardCount = 1),
        )
        if (dtlIp != null) {
            registry.register(
                GateConnectionState(
                    dtlIp = dtlIp,
                    gateTypeCode = 1,
                    codec = codec,
                    connection = mock(Connection::class.java),
                    outbound = mock(NettyOutbound::class.java),
                    actor = GateConnectionActor(dtlIp, Dispatchers.Default, queueCapacity = 10),
                    laneInfo = listOf(
                        GateLaneInfo(locId = 7, grpId = 3, dtlId = 42, dtlLaneNo = 1, dtlType = 1, analysisYn = true),
                    ),
                ),
            )
        }
        return registry
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> anyNonNull(): T {
        org.mockito.Mockito.any<T>()
        return null as T
    }

    // ── QUEUED 접수 ──────────────────────────────────────────────────

    @Test
    fun `QUEUED 모드는 전송하지 않고 tb_data_snd에 대기 상태로 적재한다`() {
        val repository = mock(DataSendRepository::class.java)
        val service = QueuedGateControlService(registryWith("192.168.0.10"), repository, codecRegistry)

        val result = service.send(
            GateControlRequest("192.168.0.10", 1, SpeedGateControlCommand.OPEN, requestedBy = "operator1"),
        )

        assertEquals(GateControlResult.QUEUED, result)

        val captor = ArgumentCaptor.forClass(DataSend::class.java)
        verify(repository).save(captor.capture())
        val saved = captor.value

        assertTrue(saved.isPending, "SendControlJob이 집어갈 수 있도록 (N,N) 상태여야 한다")
        assertEquals("192.168.0.10", saved.dtlIp)
        assertEquals(1, saved.dtlLaneNo)
        assertEquals(42L, saved.dtlId)
        assertEquals(7L, saved.locId)
        assertEquals("operator1", saved.sndUser)
        assertEquals(SpeedGateControlCommand.OPEN.legacyCode, saved.sndTypeCd)

        // 저장된 원시 데이터가 실제로 전송 가능한 유효 패킷인지 확인한다.
        val packet = HexCodec.fromHex(saved.sndRaw)
        assertTrue(SpeedGatePacketCodec.verifyChecksum(packet))
        assertEquals(
            SpeedGateProtocolConstants.ObjectCode.GATE_SETTING,
            packet[SpeedGateProtocolConstants.HeaderOffset.OBJECT_CODE],
        )
        // header/data/tail 분해 저장이 원본과 일치해야 한다.
        assertEquals(saved.sndRaw, saved.sndHeader + saved.sndData + saved.sndTail)
    }

    @Test
    fun `장비가 접속되어 있지 않아도 명령을 접수한다`() {
        // 재접속 후 이어서 전송되는 것이 QUEUED 모드의 존재 이유다.
        val repository = mock(DataSendRepository::class.java)
        val service = QueuedGateControlService(registryWith(null), repository, codecRegistry)

        val result = service.send(GateControlRequest("192.168.0.99", 1, SpeedGateControlCommand.CLOSE))

        assertEquals(GateControlResult.QUEUED, result)
        verify(repository).save(anyNonNull<DataSend>())
    }

    @Test
    fun `보안 등급을 지정하면 패킷 본문에 인코딩된다`() {
        val repository = mock(DataSendRepository::class.java)
        val service = QueuedGateControlService(registryWith("192.168.0.10"), repository, codecRegistry)

        service.send(
            GateControlRequest(
                dtlIp = "192.168.0.10",
                dtlLaneNo = 1,
                command = SpeedGateControlCommand.NORMAL,
                securityMode = SpeedGateSecurityMode.HIGH,
            ),
        )

        val captor = ArgumentCaptor.forClass(DataSend::class.java)
        verify(repository).save(captor.capture())
        val body = HexCodec.fromHex(captor.value.sndRaw)
            .copyOfRange(
                SpeedGateProtocolConstants.HEADER_LENGTH,
                SpeedGateProtocolConstants.HEADER_LENGTH + SpeedGateProtocolConstants.CONTROL_BODY_LENGTH,
            )

        assertEquals(
            SpeedGateSecurityMode.HIGH.value,
            body[SpeedGateProtocolConstants.ControlBodyOffset.SECURITY_MODE],
        )
    }

    @Test
    fun `리셋 명령은 레거시 snd_data_tp 문자열을 함께 남긴다`() {
        val repository = mock(DataSendRepository::class.java)
        val service = QueuedGateControlService(registryWith("192.168.0.10"), repository, codecRegistry)

        service.send(GateControlRequest("192.168.0.10", 1, SpeedGateControlCommand.RESET_MOTOR))

        val captor = ArgumentCaptor.forClass(DataSend::class.java)
        verify(repository).save(captor.capture())
        assertEquals("RESET_MOTOR", captor.value.sndDataTp)
    }

    // ── 장애 해제 ────────────────────────────────────────────────────

    @Test
    fun `리셋 명령별로 해제 대상 분류가 매핑된다`() {
        assertEquals(GateFaultCategory.ALL, GateFaultCategory.of(SpeedGateControlCommand.RESET_SYSTEM))
        assertEquals(GateFaultCategory.ALL, GateFaultCategory.of(SpeedGateControlCommand.RESET_BOARD))
        assertEquals(GateFaultCategory.SENSOR, GateFaultCategory.of(SpeedGateControlCommand.RESET_OPERATION_SENSOR))
        assertEquals(GateFaultCategory.SENSOR, GateFaultCategory.of(SpeedGateControlCommand.RESET_SAFETY_SENSOR))
        assertEquals(GateFaultCategory.MOTOR, GateFaultCategory.of(SpeedGateControlCommand.RESET_MOTOR))
        // 리셋이 아닌 명령은 해제 대상이 없다.
        assertNull(GateFaultCategory.of(SpeedGateControlCommand.OPEN))
    }

    @Test
    fun `모터 리셋은 모터 장애만 해제한다`() {
        val repository = mock(DataReceiveAnalysisRepository::class.java)
        val service = GateFaultResolutionService(repository)

        service.resolveByResetCommand("192.168.0.10", 2, SpeedGateControlCommand.RESET_MOTOR, "operator1")

        verify(repository).resolveMotorErrors(
            anyString(), anyInt(), anyString(), anyString(), anyString(), anyNonNull<LocalDateTime>(),
        )
        // 다른 분류까지 함께 해제하면 실제로 고장 난 센서가 정상으로 보인다.
        verify(repository, never()).resolveSensorErrors(
            anyString(), anyInt(), anyString(), anyString(), anyString(), anyNonNull<LocalDateTime>(),
        )
        verify(repository, never()).resolveAllErrors(
            anyString(), anyInt(), anyString(), anyString(), anyString(), anyNonNull<LocalDateTime>(),
        )
    }

    @Test
    fun `해제 조건에 레인 번호가 포함된다`() {
        // 레거시는 dtl_ip만으로 해제해, 1번 레인 리셋이 2번 레인 장애까지 해제해 버렸다.
        val repository = mock(DataReceiveAnalysisRepository::class.java)
        val service = GateFaultResolutionService(repository)

        // ArgumentCaptor는 Kotlin non-null 파라미터에서 NPE가 나므로 Answer로 인자를 기록한다.
        var capturedIp: String? = null
        var capturedLane: Int? = null
        org.mockito.Mockito.`when`(
            repository.resolveAllErrors(
                anyString(), anyInt(), anyString(), anyString(), anyString(), anyNonNull<LocalDateTime>(),
            ),
        ).thenAnswer { invocation ->
            capturedIp = invocation.getArgument(0)
            capturedLane = invocation.getArgument(1)
            1
        }

        service.resolveByResetCommand("192.168.0.10", 3, SpeedGateControlCommand.RESET_SYSTEM, "admin")

        assertEquals("192.168.0.10", capturedIp)
        assertEquals(3, capturedLane)
    }

    @Test
    fun `리셋이 아닌 명령으로는 아무 것도 해제하지 않는다`() {
        val repository = mock(DataReceiveAnalysisRepository::class.java)
        val service = GateFaultResolutionService(repository)

        val resolved = service.resolveByResetCommand("192.168.0.10", 1, SpeedGateControlCommand.OPEN, "admin")

        assertEquals(0, resolved)
        verify(repository, never()).resolveAllErrors(
            anyString(), anyInt(), anyString(), anyString(), anyString(), anyNonNull<LocalDateTime>(),
        )
    }
}
