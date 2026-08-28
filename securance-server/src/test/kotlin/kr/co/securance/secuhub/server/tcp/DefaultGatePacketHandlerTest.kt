package kr.co.securance.secuhub.server.tcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kr.co.securance.secuhub.protocol.GatePacket
import kr.co.securance.secuhub.protocol.GateProtocolCodec
import kr.co.securance.secuhub.protocol.PacketReassembler
import kr.co.securance.secuhub.protocol.SpeedGateControlPayload
import kr.co.securance.secuhub.protocol.SpeedGatePacketCodec
import kr.co.securance.secuhub.protocol.SpeedGateProtocolConstants
import kr.co.securance.secuhub.server.connection.GateConnectionActor
import kr.co.securance.secuhub.server.connection.GateConnectionRegistryImpl
import kr.co.securance.secuhub.server.connection.GateConnectionState
import kr.co.securance.secuhub.server.control.GateControlDispatcher
import kr.co.securance.secuhub.server.db.GatePacketPersister
import kr.co.securance.secuhub.server.db.OprStatusPersister
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import reactor.netty.Connection
import reactor.netty.NettyOutbound
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private object FakeCodec : GateProtocolCodec {
    override val supportedGateTypes: Set<Int> = setOf(1)
    override val defaultAddress: ByteArray = ByteArray(0)
    override fun verifyChecksum(packet: ByteArray): Boolean = true
    override fun decode(packet: ByteArray): GatePacket = throw UnsupportedOperationException()
    override fun buildStatusRequest(address: ByteArray, dateTime: LocalDateTime): ByteArray = ByteArray(0)
    override fun buildAck(objectCode: Byte, dateTime: LocalDateTime): ByteArray = ByteArray(0)
    override fun buildControlCommand(laneNo: Int, payload: SpeedGateControlPayload): ByteArray = ByteArray(0)
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
        val handler = DefaultGatePacketHandler(registry, mock(GatePacketPersister::class.java), mock(GateControlDispatcher::class.java), mock(GateLogService::class.java), mock(OprStatusPersister::class.java))
        val state = newState()

        handler.handle(state, fakeStatusPacket(laneCount = 3) { 0x01 })
        verify(registry, times(1)).enqueueNetStateUpdate(state, 1, true)
        verify(registry, times(1)).enqueueNetStateUpdate(state, 2, true)
        verify(registry, times(1)).enqueueNetStateUpdate(state, 3, true)

        // 센서 바이트가 바뀌어(레인 2) 패킷 전체는 "변경됨"으로 잡히지만, 이미 온라인인 레인 1/2/3은
        // 다시 net_state에 큐잉되면 안 된다.
        handler.handle(state, fakeStatusPacket(laneCount = 3) { lane -> if (lane == 2) 0x02 else 0x01 })
        verify(registry, times(1)).enqueueNetStateUpdate(state, 1, true)
        verify(registry, times(1)).enqueueNetStateUpdate(state, 2, true)
        verify(registry, times(1)).enqueueNetStateUpdate(state, 3, true)
    }

    @Test
    fun `새로 나타난 레인만 온라인으로 큐잉한다`() = runBlocking {
        val registry = mock(GateConnectionRegistryImpl::class.java)
        val handler = DefaultGatePacketHandler(registry, mock(GatePacketPersister::class.java), mock(GateControlDispatcher::class.java), mock(GateLogService::class.java), mock(OprStatusPersister::class.java))
        val state = newState()

        handler.handle(state, fakeStatusPacket(laneCount = 2))
        verify(registry, times(1)).enqueueNetStateUpdate(state, 1, true)
        verify(registry, times(1)).enqueueNetStateUpdate(state, 2, true)

        // 레인 3이 새로 추가된 상태 패킷 — 레인 3만 새로 큐잉되어야 한다.
        handler.handle(state, fakeStatusPacket(laneCount = 3))
        verify(registry, times(1)).enqueueNetStateUpdate(state, 3, true)
        verify(registry, never()).enqueueNetStateUpdate(state, 1, false)
        verify(registry, times(1)).enqueueNetStateUpdate(state, 1, true) // 여전히 1회만.
    }

    @Test
    fun `authoritative 레인 집합이 줄어들면 사라진 레인을 오프라인으로 큐잉하고 다시 나타나면 온라인으로 재큐잉한다`() = runBlocking {
        // 2차 적대적 리뷰(codex) 지적 회귀 테스트: 예전에는 새로 나타난 레인만 처리하고 사라진
        // 레인은 아무 것도 하지 않았다 — laneSnapshot(replaceLaneNumbers로 이미 교체됨)에서도
        // 빠지므로 커넥션 종료 시 오프라인 일괄 처리 대상에도 잡히지 않아 온라인 상태가 영구
        // 잔존했다. 또한 onlineLanesRecorded에 계속 남아 있으면 나중에 그 레인이 다시 나타나도
        // "이미 온라인으로 기록됨"으로 오인해 온라인 갱신 자체가 생략되는 2차 버그도 함께 검증한다.
        val registry = mock(GateConnectionRegistryImpl::class.java)
        val handler = DefaultGatePacketHandler(registry, mock(GatePacketPersister::class.java), mock(GateControlDispatcher::class.java), mock(GateLogService::class.java), mock(OprStatusPersister::class.java))
        val state = newState()

        handler.handle(state, fakeStatusPacket(laneCount = 3))
        verify(registry, times(1)).enqueueNetStateUpdate(state, 3, true)

        // 레인 3이 사라진 상태 패킷 — 사라진 레인은 오프라인으로 큐잉되어야 한다.
        handler.handle(state, fakeStatusPacket(laneCount = 2))
        verify(registry, times(1)).enqueueNetStateUpdate(state, 3, false)
        verify(registry, never()).enqueueNetStateUpdate(state, 1, false)
        verify(registry, never()).enqueueNetStateUpdate(state, 2, false)

        // 레인 3이 다시 나타나면 "새로 나타난 레인"으로 인식되어 다시 온라인으로 큐잉되어야 한다.
        handler.handle(state, fakeStatusPacket(laneCount = 3))
        verify(registry, times(2)).enqueueNetStateUpdate(state, 3, true)
    }

    @Test
    fun `레인이 패킷에 계속 보고돼도 센서 값이 전부 0이면 온라인으로 큐잉하지 않는다`() = runBlocking {
        // 어드버서리얼 리뷰 지적: 레거시 ClsPacketAnalyzer.IsNotConnected에 대응하는 로직이 신규
        // 구현에는 없어, 물리 센서가 분리돼도(레인번호만 남고 나머지 바이트가 0) 레인이 패킷에
        // 계속 보고되기만 하면 온라인으로 남는 문제가 있었다.
        val registry = mock(GateConnectionRegistryImpl::class.java)
        val handler = DefaultGatePacketHandler(registry, mock(GatePacketPersister::class.java), mock(GateControlDispatcher::class.java), mock(GateLogService::class.java), mock(OprStatusPersister::class.java))
        val state = newState()

        // 레인 2의 센서 바이트를 0으로 둬 "미연결" 상태로 만든다.
        handler.handle(state, fakeStatusPacket(laneCount = 2) { lane -> if (lane == 2) 0x00 else 0x01 })

        verify(registry, times(1)).enqueueNetStateUpdate(state, 1, true)
        verify(registry, never()).enqueueNetStateUpdate(state, 2, true)
    }

    @Test
    fun `단일 레인 게이트도 센서 값이 0이면 온라인으로 큐잉하지 않는다`() = runBlocking {
        // 재검토 지적: 레거시 SpeedServer.cs는 iLaneCntForNet(레인 수)이 1보다 클 때만 레인별
        // IsNotConnected 검사를 net_state에 반영했고, 단일 레인 게이트는 이 검사를 건너뛰고
        // ClsQuartzJobReqStatus의 TCP 소켓 생존 폴링에만 의존했다. 사용자 확인 결과, 현재 포트는
        // 레인 수와 무관하게 항상 isLaneConnected를 적용하는 동작을 의도적으로 유지하기로 했다 —
        // 이 결정을 회귀 테스트로 고정한다.
        val registry = mock(GateConnectionRegistryImpl::class.java)
        val handler = DefaultGatePacketHandler(registry, mock(GatePacketPersister::class.java), mock(GateControlDispatcher::class.java), mock(GateLogService::class.java), mock(OprStatusPersister::class.java))
        val state = newState()

        handler.handle(state, fakeStatusPacket(laneCount = 1) { 0x00 })

        verify(registry, never()).enqueueNetStateUpdate(state, 1, true)
    }

    @Test
    fun `연결돼 있던 레인의 센서 값이 0으로 바뀌면 오프라인으로 큐잉한다`() = runBlocking {
        val registry = mock(GateConnectionRegistryImpl::class.java)
        val handler = DefaultGatePacketHandler(registry, mock(GatePacketPersister::class.java), mock(GateControlDispatcher::class.java), mock(GateLogService::class.java), mock(OprStatusPersister::class.java))
        val state = newState()

        handler.handle(state, fakeStatusPacket(laneCount = 2))
        verify(registry, times(1)).enqueueNetStateUpdate(state, 2, true)

        // 레인 2의 센서 값이 이후 패킷에서 전부 0으로 바뀜(물리 센서 분리) — 레인 자체는 여전히
        // 보고되므로 라우팅 대상에서는 빠지지 않지만, net_state는 오프라인으로 전이돼야 한다.
        handler.handle(state, fakeStatusPacket(laneCount = 2) { lane -> if (lane == 2) 0x00 else 0x01 })
        verify(registry, times(1)).enqueueNetStateUpdate(state, 2, false)
    }

    @Test
    fun `레인 수 0을 보고하면 이전에 온라인이던 레인을 전부 오프라인으로 큐잉한다`() = runBlocking {
        // 회귀 방지 테스트(코드 리뷰 지적, 2026-08-28): 예전에는 laneOffsetsOf 결과가 비어 있으면
        // (장치가 GATE_STATUS 패킷에서 레인 수 0을 보고) 오프라인 전이 로직 블록 전체가
        // `if (lanes.isNotEmpty())`에 걸려 스킵됐다 — 실제로 끊긴 게이트가 대시보드에 영구히
        // 온라인으로 남는 문제였다. 이제는 레인 수 0도 authoritative 갱신으로 처리해 이전에
        // 온라인이던 레인을 모두 오프라인으로 큐잉해야 한다.
        val registry = mock(GateConnectionRegistryImpl::class.java)
        val handler = DefaultGatePacketHandler(registry, mock(GatePacketPersister::class.java), mock(GateControlDispatcher::class.java), mock(GateLogService::class.java), mock(OprStatusPersister::class.java))
        val state = newState()

        handler.handle(state, fakeStatusPacket(laneCount = 2))
        verify(registry, times(1)).enqueueNetStateUpdate(state, 1, true)
        verify(registry, times(1)).enqueueNetStateUpdate(state, 2, true)

        handler.handle(state, fakeStatusPacket(laneCount = 0))
        verify(registry, times(1)).enqueueNetStateUpdate(state, 1, false)
        verify(registry, times(1)).enqueueNetStateUpdate(state, 2, false)

        // 이후 레인이 다시 나타나면 "새로 나타난 레인"으로 인식되어 다시 온라인으로 큐잉돼야 한다.
        handler.handle(state, fakeStatusPacket(laneCount = 2))
        verify(registry, times(2)).enqueueNetStateUpdate(state, 1, true)
        verify(registry, times(2)).enqueueNetStateUpdate(state, 2, true)
    }

    @Test
    fun `레인 수 0을 보고해도 레인 라우팅(authoritative 상태)은 건드리지 않아 제어 명령이 계속 가능하다`() = runBlocking {
        // 회귀 방지 테스트(Codex 적대적 리뷰 지적, 2026-08-28): 위 "레인 수 0을 보고하면..." 수정의
        // 1차 버전은 lanes가 비어 있어도 무조건 state.replaceLaneNumbers(emptyList())를 호출했다 —
        // 그러면 hasAuthoritativeLaneInfo=true + 레인 집합 비어있음 조합이 되어
        // GateConnectionRegistryImpl.sendToLane의 "!ownsLane && hasAuthoritativeLaneInfo" 가드에
        // 걸려, 장치 재기동/손상된 패킷 등으로 인한 단 한 번의 일시적 레인 수 0 보고만으로 그 장치의
        // 모든 레인 지정 제어 명령이 다음 정상 상태 패킷이 올 때까지 거부됐다. 이제는 레인이 실제로
        // 보고된 패킷만 라우팅에 반영하고, 빈 보고는 라우팅을 그대로 둔다 — net_state(대시보드)만
        // 오프라인으로 전이되고 라우팅 정보(hasAuthoritativeLaneInfo/laneSnapshot)는 유지돼야 한다.
        val registry = mock(GateConnectionRegistryImpl::class.java)
        val handler = DefaultGatePacketHandler(registry, mock(GatePacketPersister::class.java), mock(GateControlDispatcher::class.java), mock(GateLogService::class.java), mock(OprStatusPersister::class.java))
        val state = newState()

        handler.handle(state, fakeStatusPacket(laneCount = 2))
        assertTrue(state.hasAuthoritativeLaneInfo)
        assertEquals(setOf(1, 2), state.laneSnapshot())

        handler.handle(state, fakeStatusPacket(laneCount = 0))

        // net_state는 여전히 오프라인으로 전이된다(대시보드 정확성은 유지).
        verify(registry, times(1)).enqueueNetStateUpdate(state, 1, false)
        verify(registry, times(1)).enqueueNetStateUpdate(state, 2, false)
        // 그러나 라우팅 상태(레인 소유권)는 직전 authoritative 값 그대로 남아, sendToLane이 이
        // 장치의 레인 1/2로 향하는 제어 명령을 계속 받아들일 수 있어야 한다.
        assertTrue(state.hasAuthoritativeLaneInfo)
        assertEquals(setOf(1, 2), state.laneSnapshot())
    }

    @Test
    fun `GATE_LOG 패킷은 GateLogService에 위임하고 net_state는 건드리지 않는다`() = runBlocking {
        val registry = mock(GateConnectionRegistryImpl::class.java)
        val gateLogService = mock(GateLogService::class.java)
        val handler = DefaultGatePacketHandler(registry, mock(GatePacketPersister::class.java), mock(GateControlDispatcher::class.java), gateLogService, mock(OprStatusPersister::class.java))
        val state = newState()

        val packet = GatePacket(
            command1 = SpeedGateProtocolConstants.Command1.SEND_DATA,
            command2 = SpeedGateProtocolConstants.Command2.READ,
            objectCode = SpeedGateProtocolConstants.ObjectCode.GATE_LOG,
            dataInfoLength = 0,
            dataCount = 1,
            dataLength = SpeedGateProtocolConstants.LOG_ENTRY_LENGTH,
            raw = ByteArray(SpeedGateProtocolConstants.HEADER_LENGTH + SpeedGateProtocolConstants.LOG_ENTRY_LENGTH + SpeedGateProtocolConstants.TAIL_LENGTH),
        )

        handler.handle(state, packet)

        verify(gateLogService, times(1)).handle("192.168.0.30", packet)
        verify(registry, never()).enqueueNetStateUpdate(anyKtState(), anyKtInt(), org.mockito.ArgumentMatchers.anyBoolean())
    }

    @Test
    fun `GATE_STATUS 패킷 끝에 이어붙은 로그는 handleEmbedded로 위임하고 올바른 오프셋을 계산한다`() = runBlocking {
        // 레거시 SpeedServer.cs 검증 결과(GateLogService 클래스 KDoc "레이아웃 정정" 참고) — 로그는
        // Header+DataInfo+Status(laneCnt*74) 뒤에 이어 붙는다. laneCnt=2로 상태 패킷을 만들고 그
        // 뒤에 DATA_COUNT=1인 로그 엔트리 1건을 이어붙여, handleEmbedded가 정확한 오프셋(Header(27)+
        // DataInfo(45)+Status(2*74)=220)과 entryCount(1)로 호출되는지 검증한다.
        val registry = mock(GateConnectionRegistryImpl::class.java)
        val gateLogService = mock(GateLogService::class.java)
        val handler = DefaultGatePacketHandler(registry, mock(GatePacketPersister::class.java), mock(GateControlDispatcher::class.java), gateLogService, mock(OprStatusPersister::class.java))
        val state = newState()

        val address = SpeedGatePacketCodec.buildAddress(comSlot = 1, controller = 1, deviceNumber = 1)
        val dataInfo = ByteArray(SpeedGateProtocolConstants.DATA_INFO_LENGTH)
        dataInfo[dataInfo.size - 1] = 2 // laneCnt=2
        val laneBlocks = ByteArray(2 * SpeedGateProtocolConstants.STATUS_DATA_LENGTH)
        laneBlocks[0] = 1
        laneBlocks[SpeedGateProtocolConstants.STATUS_DATA_LENGTH] = 2
        val logEntry = ByteArray(SpeedGateProtocolConstants.LOG_ENTRY_LENGTH)

        val raw = SpeedGatePacketCodec.buildPacket(
            address = address,
            command1 = SpeedGateProtocolConstants.Command1.SEND_DATA,
            command2 = SpeedGateProtocolConstants.Command2.READ,
            objectCode = SpeedGateProtocolConstants.ObjectCode.GATE_STATUS,
            dataInfoLength = SpeedGateProtocolConstants.DATA_INFO_LENGTH,
            dataCount = 1, // 로그 1건(DATA_COUNT 필드는 laneCnt가 아니라 logCnt다)
            dataLength = logEntry.size,
            payload = dataInfo + laneBlocks + logEntry,
        )
        val packet = GatePacket(
            command1 = SpeedGateProtocolConstants.Command1.SEND_DATA,
            command2 = SpeedGateProtocolConstants.Command2.READ,
            objectCode = SpeedGateProtocolConstants.ObjectCode.GATE_STATUS,
            dataInfoLength = SpeedGateProtocolConstants.DATA_INFO_LENGTH,
            dataCount = 1,
            dataLength = logEntry.size,
            raw = raw,
        )

        handler.handle(state, packet)

        val expectedLogStart = SpeedGateProtocolConstants.HEADER_LENGTH +
            SpeedGateProtocolConstants.DATA_INFO_LENGTH +
            2 * SpeedGateProtocolConstants.STATUS_DATA_LENGTH
        verify(gateLogService, times(1)).handleEmbedded("192.168.0.30", raw, expectedLogStart, 1)
    }
}

@Suppress("UNCHECKED_CAST")
private fun <T> uninitialized(): T = null as T

private fun anyKtState(): GateConnectionState {
    Mockito.any<GateConnectionState>()
    return uninitialized()
}

private fun anyKtInt(): Int {
    Mockito.anyInt()
    return 0
}
