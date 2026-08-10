package kr.co.securance.secuhub.server.control

import kotlinx.coroutines.Dispatchers
import kr.co.securance.secuhub.domain.entity.DataSend
import kr.co.securance.secuhub.domain.repository.DataSendRepository
import kr.co.securance.secuhub.domain.repository.GateDetailRepository
import kr.co.securance.secuhub.domain.repository.GateLaneInfo
import kr.co.securance.secuhub.domain.repository.NetStateRepository
import kr.co.securance.secuhub.protocol.SpeedFlapGateProtocolCodec
import kr.co.securance.secuhub.protocol.SpeedGateControlCommand
import kr.co.securance.secuhub.protocol.SpeedGatePacketCodec
import kr.co.securance.secuhub.protocol.SpeedGateProtocolConstants
import kr.co.securance.secuhub.server.connection.GateConnectionActor
import kr.co.securance.secuhub.server.connection.GateConnectionRegistryImpl
import kr.co.securance.secuhub.server.connection.GateConnectionState
import kr.co.securance.secuhub.server.db.GateDbWriteQueue
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.timeout
import org.mockito.Mockito.verify
import reactor.netty.Connection
import reactor.netty.NettyOutbound
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * DIRECT 제어 명령 서비스 검증(1차 스프린트 4·5번 항목 — 제어 명령/리셋).
 */
class DirectGateControlServiceTest {

    private val codec = SpeedFlapGateProtocolCodec()

    /** 소켓 write를 가로채 기록하는 레지스트리 대역. */
    private class RecordingRegistry : GateConnectionRegistryImpl(
        mock(NetStateRepository::class.java),
        GateDbWriteQueue(shardCount = 1),
        mock(GateDetailRepository::class.java),
    ) {
        val sentPackets = mutableListOf<ByteArray>()
        var acceptSend = true

        override fun sendRaw(state: GateConnectionState, packet: ByteArray): Boolean {
            if (!acceptSend) return false
            sentPackets += packet
            return true
        }
    }

    private fun newState(dtlIp: String, registry: RecordingRegistry): GateConnectionState {
        val state = GateConnectionState(
            dtlIp = dtlIp,
            gateTypeCode = 1,
            codec = codec,
            connection = mock(Connection::class.java),
            outbound = mock(NettyOutbound::class.java),
            actor = GateConnectionActor(dtlIp, Dispatchers.Default, queueCapacity = 100),
            laneInfo = listOf(
                GateLaneInfo(locId = 7, grpId = 3, dtlId = 42, dtlLaneNo = 1, dtlType = 1, analysisYn = true),
            ),
        )
        registry.register(state)
        return state
    }

    @Test
    fun `접속되지 않은 게이트에는 NOT_CONNECTED를 반환한다`() {
        val registry = RecordingRegistry()
        val service = DirectGateControlService(registry, GateDbWriteQueue(1), mock(DataSendRepository::class.java))

        val result = service.send(GateControlRequest("192.168.0.99", 1, SpeedGateControlCommand.OPEN))

        assertEquals(GateControlResult.NOT_CONNECTED, result)
        assertTrue(registry.sentPackets.isEmpty())
    }

    @Test
    fun `제어 명령은 커넥션 액터 체인을 통해 전송된다`() {
        val registry = RecordingRegistry()
        val state = newState("192.168.0.10", registry)
        val service = DirectGateControlService(registry, GateDbWriteQueue(1), mock(DataSendRepository::class.java))

        val result = service.send(
            GateControlRequest("192.168.0.10", 1, SpeedGateControlCommand.OPEN, requestedBy = "admin"),
        )

        assertEquals(GateControlResult.SENT, result)
        assertEquals(1, registry.sentPackets.size)

        val packet = registry.sentPackets.single()
        assertEquals(
            SpeedGateProtocolConstants.ObjectCode.GATE_SETTING,
            packet[SpeedGateProtocolConstants.HeaderOffset.OBJECT_CODE],
        )
        assertTrue(SpeedGatePacketCodec.verifyChecksum(packet))
        state.actor.close()
    }

    @Test
    fun `리셋 명령의 이력이 tb_data_snd에 남는다`() {
        val registry = RecordingRegistry()
        val state = newState("192.168.0.11", registry)
        val repository = mock(DataSendRepository::class.java)
        val service = DirectGateControlService(registry, GateDbWriteQueue(1), repository)

        val result = service.send(
            GateControlRequest("192.168.0.11", 1, SpeedGateControlCommand.RESET_MOTOR, requestedBy = "operator1"),
        )
        assertEquals(GateControlResult.SENT, result)

        // DB 쓰기는 파티션 큐(비동기)에 위임되므로 타임아웃 검증을 사용한다.
        val captor = ArgumentCaptor.forClass(DataSend::class.java)
        verify(repository, timeout(5_000)).save(captor.capture())

        val saved = captor.value
        assertEquals("192.168.0.11", saved.dtlIp)
        assertEquals(1, saved.dtlLaneNo)
        assertEquals(42L, saved.dtlId)
        assertEquals("operator1", saved.sndUser)
        assertEquals(SpeedGateControlCommand.RESET_MOTOR.legacyCode, saved.sndTypeCd)
        assertEquals("Y", saved.sndYn)
        state.actor.close()
    }

    @Test
    fun `전송이 거부되면 REJECTED를 반환한다`() {
        val registry = RecordingRegistry().apply { acceptSend = false }
        val state = newState("192.168.0.12", registry)
        val service = DirectGateControlService(registry, GateDbWriteQueue(1), mock(DataSendRepository::class.java))

        val result = service.send(GateControlRequest("192.168.0.12", 1, SpeedGateControlCommand.CLOSE))

        assertEquals(GateControlResult.REJECTED, result)
        state.actor.close()
    }
}
