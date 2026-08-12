package kr.co.securance.secuhub.server.tcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kr.co.securance.secuhub.domain.repository.DataReceiveAckRepository
import kr.co.securance.secuhub.domain.repository.DataReceiveAnalysisRepository
import kr.co.securance.secuhub.domain.repository.DataReceiveFailRepository
import kr.co.securance.secuhub.domain.repository.DataReceiveRepository
import kr.co.securance.secuhub.domain.repository.DataSendRepository
import kr.co.securance.secuhub.domain.repository.GateDetailRepository
import kr.co.securance.secuhub.domain.repository.GateLaneInfo
import kr.co.securance.secuhub.domain.repository.NetStateRepository
import kr.co.securance.secuhub.protocol.GatePacket
import kr.co.securance.secuhub.protocol.SpeedFlapGateProtocolCodec
import kr.co.securance.secuhub.protocol.SpeedGatePacketCodec
import kr.co.securance.secuhub.protocol.SpeedGateProtocolConstants
import kr.co.securance.secuhub.server.connection.GateConnectionActor
import kr.co.securance.secuhub.server.connection.GateConnectionRegistryImpl
import kr.co.securance.secuhub.server.connection.GateConnectionState
import kr.co.securance.secuhub.server.control.ControlProperties
import kr.co.securance.secuhub.server.control.GateControlDispatcher
import kr.co.securance.secuhub.server.control.GateFaultResolutionService
import kr.co.securance.secuhub.server.db.GateDbWriteQueue
import kr.co.securance.secuhub.server.db.GatePacketPersister
import kr.co.securance.secuhub.server.db.OprStatusPersister
import org.mockito.Mockito.mock
import reactor.netty.Connection
import reactor.netty.NettyOutbound
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [DefaultGatePacketHandler]의 수신 처리 흐름 검증(1차 스프린트 1·2번 항목).
 *
 * 특히 **"변경 없음" 분기에서도 ACK가 반드시 나가는지**를 회귀 테스트로 고정한다 —
 * 레거시에서 이 분기의 조기 `return`이 ACK 전송을 통째로 건너뛰던 버그가 있었다.
 */
class DefaultGatePacketHandlerAckTest {

    private val codec = SpeedFlapGateProtocolCodec()

    /** 소켓 write를 가로채 기록하는 레지스트리 대역. `tb_net_state` 갱신은 무시한다. */
    private class RecordingRegistry : GateConnectionRegistryImpl(
        mock(NetStateRepository::class.java),
        GateDbWriteQueue(shardCount = 1),
        mock(GateDetailRepository::class.java),
    ) {
        val sentPackets = mutableListOf<ByteArray>()
        val onlineLanes = mutableListOf<Int>()

        override fun sendRaw(state: GateConnectionState, packet: ByteArray): Boolean {
            sentPackets += packet
            return true
        }

        override fun enqueueNetStateUpdate(state: GateConnectionState, dtlLaneNo: Int, online: Boolean) {
            if (online) onlineLanes += dtlLaneNo
        }

        // 핸들러는 온라인/오프라인 전이가 있을 때만 IP 기준 오버로드를 호출한다(mains 계열 로직).
        override fun enqueueNetStateUpdate(dtlIp: String, dtlLaneNo: Int, online: Boolean) {
            if (online) onlineLanes += dtlLaneNo
        }
    }

    /** DB 적재 호출만 세는 퍼시스터 대역. */
    private class CountingPersister : GatePacketPersister(
        GateDbWriteQueue(shardCount = 1),
        mock(DataReceiveRepository::class.java),
        mock(DataReceiveAckRepository::class.java),
        mock(DataReceiveFailRepository::class.java),
        mock(DataReceiveAnalysisRepository::class.java),
        mock(GateFaultResolutionService::class.java),
    ) {
        var receiveCount = 0
        var ackCount = 0

        override fun persistReceivedPacket(
            state: GateConnectionState,
            packet: GatePacket,
            laneNo: Int,
        ): Boolean {
            receiveCount++
            return true
        }

        override fun persistAck(state: GateConnectionState, raw: ByteArray, laneNo: Int) {
            ackCount++
        }

        override fun persistStatusAnalysis(state: GateConnectionState, raw: ByteArray) {
            analysisCount++
        }

        var analysisCount = 0
    }

    /** 제어 명령 ACK 알림만 기록하는 디스패처 대역. */
    private class RecordingDispatcher : GateControlDispatcher(
        mock(GateConnectionRegistryImpl::class.java),
        mock(DataSendRepository::class.java),
        mock(GateFaultResolutionService::class.java),
        ControlProperties(),
    ) {
        val ackedLanes = mutableListOf<Pair<String, Int>>()

        override fun onDeviceControlAck(dtlIp: String, dtlLaneNo: Int) {
            ackedLanes += dtlIp to dtlLaneNo
        }
    }

    private fun newState(dtlIp: String = "192.168.0.10"): GateConnectionState =
        GateConnectionState(
            dtlIp = dtlIp,
            gateTypeCode = 1,
            codec = codec,
            connection = mock(Connection::class.java),
            outbound = mock(NettyOutbound::class.java),
            actor = GateConnectionActor(dtlIp, Dispatchers.Default, queueCapacity = 100),
            laneInfo = listOf(
                GateLaneInfo(locId = 7, grpId = 3, dtlId = 11, dtlLaneNo = 1, dtlType = 1, analysisYn = true),
                GateLaneInfo(locId = 7, grpId = 3, dtlId = 12, dtlLaneNo = 2, dtlType = 1, analysisYn = true),
            ),
        )

    /**
     * `GATE_STATUS`(0x4D) 응답 패킷을 만든다.
     * payload = DataInfo(45, 마지막 바이트가 레인 수) + 레인별 상태 블록(74바이트, 첫 바이트가 레인 번호).
     */
    private fun statusPacket(lanes: List<Int>, marker: Byte = 0x00): ByteArray {
        val statusLen = SpeedGateProtocolConstants.STATUS_DATA_LENGTH
        val payload = ByteArray(SpeedGateProtocolConstants.DATA_INFO_LENGTH + lanes.size * statusLen)
        payload[SpeedGateProtocolConstants.DATA_INFO_LENGTH - 1] = lanes.size.toByte() // LOCAL GATE LANE COUNT
        lanes.forEachIndexed { index, lane ->
            val offset = SpeedGateProtocolConstants.DATA_INFO_LENGTH + index * statusLen
            payload[offset] = lane.toByte()
            payload[offset + 12] = marker // 상태 변화 감지용 임의 바이트(센서 구간)
        }
        return SpeedGatePacketCodec.buildPacket(
            address = SpeedGatePacketCodec.ZERO_ADDRESS,
            command1 = SpeedGateProtocolConstants.Command1.SEND_DATA,
            command2 = SpeedGateProtocolConstants.Command2.READ,
            objectCode = SpeedGateProtocolConstants.ObjectCode.GATE_STATUS,
            dataInfoLength = SpeedGateProtocolConstants.DATA_INFO_LENGTH,
            dataCount = 0,
            dataLength = lanes.size * statusLen,
            payload = payload,
        )
    }

    private fun decoded(raw: ByteArray): GatePacket = codec.decode(raw)

    @Test
    fun `상태 변경이 없어도 ACK는 반드시 회신한다`() = runBlocking {
        val registry = RecordingRegistry()
        val persister = CountingPersister()
        val handler = DefaultGatePacketHandler(registry, persister, RecordingDispatcher(), mock(GateLogService::class.java), mock(OprStatusPersister::class.java))
        val state = newState()

        val raw = statusPacket(listOf(1, 2))
        handler.handle(state, decoded(raw))
        // 완전히 동일한 패킷을 다시 수신 — "변경 없음" 분기를 탄다.
        handler.handle(state, decoded(raw))

        assertEquals(2, registry.sentPackets.size, "변경 없음 분기에서도 ACK가 나가야 한다")
        assertEquals(1, persister.receiveCount, "변경이 없으면 DB 적재는 생략되어야 한다")
        registry.sentPackets.forEach { ack ->
            assertEquals(SpeedGateProtocolConstants.Command1.SEND_ACK, ack[SpeedGateProtocolConstants.HeaderOffset.COMMAND1])
            assertEquals(
                SpeedGateProtocolConstants.ObjectCode.GATE_STATUS,
                ack[SpeedGateProtocolConstants.HeaderOffset.OBJECT_CODE],
            )
            assertTrue(SpeedGatePacketCodec.verifyChecksum(ack))
        }
        state.actor.close()
    }

    @Test
    fun `상태가 바뀌면 DB 적재와 ACK가 모두 수행된다`() = runBlocking {
        val registry = RecordingRegistry()
        val persister = CountingPersister()
        val handler = DefaultGatePacketHandler(registry, persister, RecordingDispatcher(), mock(GateLogService::class.java), mock(OprStatusPersister::class.java))
        val state = newState()

        handler.handle(state, decoded(statusPacket(listOf(1, 2), marker = 0x00)))
        handler.handle(state, decoded(statusPacket(listOf(1, 2), marker = 0x11)))

        assertEquals(2, persister.receiveCount)
        assertEquals(2, registry.sentPackets.size)
        state.actor.close()
    }

    @Test
    fun `0x4D 상태 패킷은 레인 집합을 authoritative하게 교체하고 온라인으로 기록한다`() = runBlocking {
        val registry = RecordingRegistry()
        val handler = DefaultGatePacketHandler(registry, CountingPersister(), RecordingDispatcher(), mock(GateLogService::class.java), mock(OprStatusPersister::class.java))
        val state = newState()

        // marker != 0: 레인 블록의 나머지 바이트가 전부 0이면 "센서 미연결"로 판정되어 온라인
        // 기록에서 제외된다(PacketDiffer.isLaneConnected) — 실제 연결 상태를 흉내 낸다.
        handler.handle(state, decoded(statusPacket(listOf(1, 2), marker = 0x11)))

        assertTrue(state.hasAuthoritativeLaneInfo)
        assertEquals(setOf(1, 2), state.laneSnapshot())
        assertEquals(listOf(1, 2), registry.onlineLanes)
        state.actor.close()
    }

    @Test
    fun `0x4D 이외의 패킷은 레인 집합을 축소하지 않는다`() = runBlocking {
        // 레거시 H-3/H-1: 4D가 아닌 패킷에서 레인 집합을 교체해버려 다중 레인 소켓의 레인 정보가
        // 대표 레인 1개로 줄어들고, 그 결과 다른 레인으로 제어 명령이 전달되지 않던 버그의 회귀 테스트.
        val registry = RecordingRegistry()
        val handler = DefaultGatePacketHandler(registry, CountingPersister(), RecordingDispatcher(), mock(GateLogService::class.java), mock(OprStatusPersister::class.java))
        val state = newState()

        handler.handle(state, decoded(statusPacket(listOf(1, 2))))
        assertEquals(setOf(1, 2), state.laneSnapshot())

        val settingPacket = SpeedGatePacketCodec.buildControlCommand(1, kr.co.securance.secuhub.protocol.SpeedGateControlCommand.OPEN)
        handler.handle(state, decoded(settingPacket))

        assertEquals(setOf(1, 2), state.laneSnapshot(), "0x4C 패킷 처리 후에도 레인 집합이 유지되어야 한다")
        state.actor.close()
    }

    @Test
    fun `제어 명령이 아닌 ACK는 제어 명령용 레인 FIFO를 소비하지 않는다`() = runBlocking {
        // Codex 어드버서리얼 리뷰 회귀 테스트: 상태 폴링(trackForAck=false)이 큐를 오염시키지
        // 않는지, 그리고 objectCode가 GATE_SETTING이 아닌 ACK가 도착해도 이미 쌓인 제어 명령
        // 레인 항목을 잘못 소비하지 않는지 검증한다.
        val registry = RecordingRegistry()
        val persister = CountingPersister()
        val handler = DefaultGatePacketHandler(registry, persister, RecordingDispatcher(), mock(GateLogService::class.java), mock(OprStatusPersister::class.java))
        val state = newState()

        // 제어 명령이 레인 2로 전송됐다고 가정 — FIFO에 레인 2가 쌓인다.
        state.recordSentLane(2)

        // 상태 폴링 ACK가 먼저 도착한다(objectCode=GATE_STATUS, 큐를 건드리면 안 된다).
        val statusAck = SpeedGatePacketCodec.buildAck(SpeedGateProtocolConstants.ObjectCode.GATE_STATUS)
        handler.handle(state, decoded(statusAck))

        // 이어서 실제 제어 명령 ACK(objectCode=GATE_SETTING)가 도착하면 레인 2로 정확히 상관돼야 한다.
        val controlAck = SpeedGatePacketCodec.buildAck(SpeedGateProtocolConstants.ObjectCode.GATE_SETTING)
        val dispatcher = RecordingDispatcher()
        val handlerWithDispatcher = DefaultGatePacketHandler(registry, persister, dispatcher, mock(GateLogService::class.java), mock(OprStatusPersister::class.java))
        handlerWithDispatcher.handle(state, decoded(controlAck))

        assertEquals(listOf("192.168.0.10" to 2), dispatcher.ackedLanes, "제어 ACK는 실제로 전송한 레인 2로 상관돼야 한다")
        state.actor.close()
    }

    @Test
    fun `게이트가 보낸 ACK는 저장만 하고 회신하지 않는다`() = runBlocking {
        val registry = RecordingRegistry()
        val persister = CountingPersister()
        val handler = DefaultGatePacketHandler(registry, persister, RecordingDispatcher(), mock(GateLogService::class.java), mock(OprStatusPersister::class.java))
        val state = newState()

        val gateAck = SpeedGatePacketCodec.buildAck(SpeedGateProtocolConstants.ObjectCode.GATE_STATUS)
        handler.handle(state, decoded(gateAck))

        assertEquals(1, persister.ackCount)
        assertEquals(0, persister.receiveCount)
        assertTrue(registry.sentPackets.isEmpty(), "ACK에 다시 ACK를 보내면 무한 왕복이 된다")
        state.actor.close()
    }
}
