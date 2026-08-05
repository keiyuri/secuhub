package kr.co.securance.secuhub.server.tcp

import kr.co.securance.secuhub.protocol.GatePacket
import kr.co.securance.secuhub.protocol.PacketDiffer
import kr.co.securance.secuhub.protocol.SpeedGateProtocolConstants
import kr.co.securance.secuhub.server.connection.GateConnectionRegistryImpl
import kr.co.securance.secuhub.server.connection.GateConnectionState
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 1차 스캐폴드 기본 패킷 핸들러.
 *
 * `GATE_STATUS`(0x4D) 패킷 수신 시 레인 집합을 authoritative하게 갱신하고 `tb_net_state`를
 * 온라인으로 기록한다. 그 외 객체 코드는 로그만 남긴다 — 상세 저장(tb_data_rcv 등)은
 * 계획서 3.5절 "DB 쓰기 파이프라인"의 나머지 부분으로 후속 작업이다(README 참고).
 */
@Component
class DefaultGatePacketHandler(
    private val registry: GateConnectionRegistryImpl,
) : GatePacketHandler {

    private val logger = LoggerFactory.getLogger(DefaultGatePacketHandler::class.java)

    override suspend fun handle(state: GateConnectionState, packet: GatePacket) {
        // ACK(0x07/0x08)는 별도 저장 대상 — 1차 스캐폴드는 로그만 남긴다.
        if (packet.command1 == SpeedGateProtocolConstants.Command1.SEND_ACK ||
            packet.command1 == SpeedGateProtocolConstants.Command1.REQUEST_ACK
        ) {
            logger.debug("커넥션[{}] ACK 수신: objectCode={}", state.dtlIp, packet.objectCode)
            return
        }

        if (packet.objectCode == SpeedGateProtocolConstants.ObjectCode.GATE_STATUS) {
            val lanes = PacketDiffer.laneNumbersOf(packet.raw)
            if (lanes.isNotEmpty()) {
                state.replaceLaneNumbers(lanes)
                lanes.forEach { lane -> registry.enqueueNetStateUpdate(state.dtlIp, lane, online = true) }
            }
            val changes = PacketDiffer.diff(state.lastStatusPacket, packet.raw)
            if (changes.anyChanged) {
                state.lastStatusPacket = packet.raw
                logger.info(
                    "커넥션[{}] 상태 변경 감지: 레인={}, changedLanes={}",
                    state.dtlIp, lanes, changes.laneChanges.count { it.anyChanged },
                )
            }
        } else {
            logger.debug("커넥션[{}] objectCode={} 패킷 수신(상세 저장은 후속 작업)", state.dtlIp, packet.objectCode)
        }
    }
}
