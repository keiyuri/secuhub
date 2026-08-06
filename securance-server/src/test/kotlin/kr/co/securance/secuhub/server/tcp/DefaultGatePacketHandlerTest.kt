package kr.co.securance.secuhub.server.tcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kr.co.securance.secuhub.protocol.GatePacket
import kr.co.securance.secuhub.protocol.GateProtocolCodec
import kr.co.securance.secuhub.protocol.PacketReassembler
import kr.co.securance.secuhub.protocol.SpeedGatePacketCodec
import kr.co.securance.secuhub.protocol.SpeedGateProtocolConstants
import kr.co.securance.secuhub.server.connection.GateConnectionActor
import kr.co.securance.secuhub.server.connection.GateConnectionRegistryImpl
import kr.co.securance.secuhub.server.connection.GateConnectionState
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import reactor.netty.Connection
import reactor.netty.NettyOutbound
import java.time.LocalDateTime
import kotlin.test.Test

private object FakeCodec : GateProtocolCodec {
    override val supportedGateTypes: Set<Int> = setOf(1)
    override fun verifyChecksum(packet: ByteArray): Boolean = true
    override fun decode(packet: ByteArray): GatePacket = throw UnsupportedOperationException()
    override fun buildStatusRequest(address: ByteArray, dateTime: LocalDateTime): ByteArray = ByteArray(0)
    override fun newReassembler(): PacketReassembler = object : PacketReassembler {
        override fun append(chunk: ByteArray): List<ByteArray> = emptyList()
    }
}

class DefaultGatePacketHandlerTest {

    /** [kr.co.securance.secuhub.protocol.PacketDifferTest]와 동일한 방식으로 가상 GATE_STATUS 패킷을 만든다. */
    private fun fakeStatusPacket(laneCount: Int, sensorByteAt: (lane: Int) -> Byte = { 0x01 }): GatePacket {
        val address = SpeedGatePacketCodec.buildAddress(comSlot = 1, controller = 1, deviceNumber = 1)
        val dataInfo = ByteArray(SpeedGateProtocolConstants.DATA_INFO_LENGTH)
        dataInfo[dataInfo.size - 1] = laneCount.toByte()

        val laneBlocks = ByteArray(laneCount * SpeedGateProtocolConstants.STATUS_DATA_LENGTH)
        for (lane in 1..laneCount) {
            val offset = (lane - 1) * SpeedGateProtocolConstants.STATUS_DATA_LENGTH
            laneBlocks[offset] = lane.toByte()
            laneBlocks[offset + 10] = sensorByteAt(lane)
        }

        val raw = SpeedGatePacketCodec.buildPacket(
            address = address,
            command1 = SpeedGateProtocolConstants.Command1.SEND_DATA,
            command2 = SpeedGateProtocolConstants.Command2.READ,
            objectCode = SpeedGateProtocolConstants.ObjectCode.GATE_STATUS,
            dataInfoLength = SpeedGateProtocolConstants.DATA_INFO_LENGTH,
            dataCount = laneCount,
            dataLength = SpeedGateProtocolConstants.STATUS_DATA_LENGTH,
            payload = dataInfo + laneBlocks,
        )
        return GatePacket(
            command1 = SpeedGateProtocolConstants.Command1.SEND_DATA,
            command2 = SpeedGateProtocolConstants.Command2.READ,
            objectCode = SpeedGateProtocolConstants.ObjectCode.GATE_STATUS,
            dataInfoLength = SpeedGateProtocolConstants.DATA_INFO_LENGTH,
            dataCount = laneCount,
            dataLength = SpeedGateProtocolConstants.STATUS_DATA_LENGTH,
            raw = raw,
        )
    }

    private fun newState(): GateConnectionState =
        GateConnectionState(
            dtlIp = "192.168.0.30",
            gateTypeCode = 1,
            codec = FakeCodec,
            connection = mock(Connection::class.java),
            outbound = mock(NettyOutbound::class.java),
            actor = GateConnectionActor("192.168.0.30", Dispatchers.Default, 10),
        )

    @Test
    fun `같은 레인의 상태 패킷이 반복 수신돼도 이미 온라인으로 기록한 레인은 다시 큐잉하지 않는다`() = runBlocking {
        // 적대적 리뷰 지적: 예전에는 GATE_STATUS 패킷마다 무조건 전 레인을 enqueueNetStateUpdate했다 —
        // 폴링 주기마다 반복되므로 DB 쓰기 큐(샤드당 용량 1000)가 대수/레인 수가 많을 때 곧바로
        // 포화된다. 이제는 새로 온라인이 된 레인만 큐잉해야 한다.
        val registry = mock(GateConnectionRegistryImpl::class.java)
        val handler = DefaultGatePacketHandler(registry)
        val state = newState()

        handler.handle(state, fakeStatusPacket(laneCount = 3) { 0x01 })
        verify(registry, times(1)).enqueueNetStateUpdate("192.168.0.30", 1, true)
        verify(registry, times(1)).enqueueNetStateUpdate("192.168.0.30", 2, true)
        verify(registry, times(1)).enqueueNetStateUpdate("192.168.0.30", 3, true)

        // 센서 바이트가 바뀌어(레인 2) 패킷 전체는 "변경됨"으로 잡히지만, 이미 온라인인 레인 1/2/3은
        // 다시 net_state에 큐잉되면 안 된다.
        handler.handle(state, fakeStatusPacket(laneCount = 3) { lane -> if (lane == 2) 0x02 else 0x01 })
        verify(registry, times(1)).enqueueNetStateUpdate("192.168.0.30", 1, true)
        verify(registry, times(1)).enqueueNetStateUpdate("192.168.0.30", 2, true)
        verify(registry, times(1)).enqueueNetStateUpdate("192.168.0.30", 3, true)
    }

    @Test
    fun `새로 나타난 레인만 온라인으로 큐잉한다`() = runBlocking {
        val registry = mock(GateConnectionRegistryImpl::class.java)
        val handler = DefaultGatePacketHandler(registry)
        val state = newState()

        handler.handle(state, fakeStatusPacket(laneCount = 2))
        verify(registry, times(1)).enqueueNetStateUpdate("192.168.0.30", 1, true)
        verify(registry, times(1)).enqueueNetStateUpdate("192.168.0.30", 2, true)

        // 레인 3이 새로 추가된 상태 패킷 — 레인 3만 새로 큐잉되어야 한다.
        handler.handle(state, fakeStatusPacket(laneCount = 3))
        verify(registry, times(1)).enqueueNetStateUpdate("192.168.0.30", 3, true)
        verify(registry, never()).enqueueNetStateUpdate("192.168.0.30", 1, false)
        verify(registry, times(1)).enqueueNetStateUpdate("192.168.0.30", 1, true) // 여전히 1회만.
    }

    @Test
    fun `authoritative 레인 집합이 줄어들면 사라진 레인을 오프라인으로 큐잉하고 다시 나타나면 온라인으로 재큐잉한다`() = runBlocking {
        // 2차 적대적 리뷰(codex) 지적 회귀 테스트: 예전에는 새로 나타난 레인만 처리하고 사라진
        // 레인은 아무 것도 하지 않았다 — laneSnapshot(replaceLaneNumbers로 이미 교체됨)에서도
        // 빠지므로 커넥션 종료 시 오프라인 일괄 처리 대상에도 잡히지 않아 온라인 상태가 영구
        // 잔존했다. 또한 onlineLanesRecorded에 계속 남아 있으면 나중에 그 레인이 다시 나타나도
        // "이미 온라인으로 기록됨"으로 오인해 온라인 갱신 자체가 생략되는 2차 버그도 함께 검증한다.
        val registry = mock(GateConnectionRegistryImpl::class.java)
        val handler = DefaultGatePacketHandler(registry)
        val state = newState()

        handler.handle(state, fakeStatusPacket(laneCount = 3))
        verify(registry, times(1)).enqueueNetStateUpdate("192.168.0.30", 3, true)

        // 레인 3이 사라진 상태 패킷 — 사라진 레인은 오프라인으로 큐잉되어야 한다.
        handler.handle(state, fakeStatusPacket(laneCount = 2))
        verify(registry, times(1)).enqueueNetStateUpdate("192.168.0.30", 3, false)
        verify(registry, never()).enqueueNetStateUpdate("192.168.0.30", 1, false)
        verify(registry, never()).enqueueNetStateUpdate("192.168.0.30", 2, false)

        // 레인 3이 다시 나타나면 "새로 나타난 레인"으로 인식되어 다시 온라인으로 큐잉되어야 한다.
        handler.handle(state, fakeStatusPacket(laneCount = 3))
        verify(registry, times(2)).enqueueNetStateUpdate("192.168.0.30", 3, true)
    }
}
