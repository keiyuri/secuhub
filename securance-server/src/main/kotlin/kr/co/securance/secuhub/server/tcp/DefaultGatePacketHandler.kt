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
 * 온라인으로 기록한다. `GATE_LOG`(0x61) 패킷은 [GateLogService]에 위임해 저장한다(계획서 3.8절,
 * 신규 설계). 그 외 객체 코드는 로그만 남긴다 — 상세 저장(tb_data_rcv 등)은 계획서 3.5절
 * "DB 쓰기 파이프라인"의 나머지 부분으로 후속 작업이다(README 참고).
 */
@Component
class DefaultGatePacketHandler(
    private val registry: GateConnectionRegistryImpl,
    private val gateLogService: GateLogService,
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
            val laneOffsets = PacketDiffer.laneOffsetsOf(packet.raw)
            val lanes = laneOffsets.keys.toList()
            if (lanes.isNotEmpty()) {
                // 레인 라우팅(sendToLane 대상 판단)은 패킷에 보고된 레인 전체를 그대로 쓴다 — 아래
                // net_state(온라인 표시)와는 목적이 달라, 센서가 일시적으로 끊겼다고 제어 대상에서
                // 빼면 안 된다.
                state.replaceLaneNumbers(lanes)

                // net_state(온라인 여부)는 "레인이 패킷에 보고됐는지"가 아니라 "레인 블록의 실제
                // 센서 값이 살아있는지"까지 확인한다(레거시 `ClsPacketAnalyzer.IsNotConnected` 대응,
                // 어드버서리얼 리뷰 지적 — 레거시는 있었지만 신규 구현에서 누락돼 있었다). 게이트가
                // 레인 슬롯을 계속 보고해도 물리 센서가 분리되면 나머지 바이트가 전부 0으로 오므로,
                // 단순 존재 여부만 보면 실제로는 끊긴 레인이 대시보드에 계속 온라인으로 표시된다.
                //
                // [레거시와의 의도적 차이] 레거시 SpeedServer.cs(라인 1096~1141)는 이 검사를
                // `iLaneCntForNet > 1`(다중 레인)일 때만 적용했다 — 단일 레인 게이트는 이 지점에서
                // net_state를 아예 건드리지 않고, ClsQuartzJobReqStatus의 주기적 TCP 소켓 생존 폴링
                // 결과만으로 온라인/오프라인을 판정했다(TCP 생존=레인 생존으로 취급). 재검토 시 이
                // 차이를 발견해 사용자에게 확인했고, "레인 수와 무관하게 항상 isLaneConnected를
                // 적용"하는 현재 동작을 그대로 유지하기로 결정했다 — 물리 센서 분리를 TCP 연결
                // 생존 여부보다 더 빠르고 정확하게 감지할 수 있어 레거시보다 개선된 동작으로 판단.
                val currentLaneSet = laneOffsets.filterValues { offset -> PacketDiffer.isLaneConnected(packet.raw, offset) }.keys

                // 상태 전이(오프라인→온라인) 시에만 net_state를 큐잉한다(적대적 리뷰 지적) — 매 폴링마다
                // 전 레인을 무조건 다시 쓰면 DB 쓰기 큐(샤드당 1000)가 대수/레인 수가 많을 때 곧바로
                // 포화되어 오히려 조용히 드롭당한다. 이미 온라인으로 기록한 레인은 다시 쓰지 않고,
                // 새로 나타난 레인(최초 접속 또는 재연결로 온라인 집합에 없던 레인)만 기록한다.
                //
                // 축소된 레인도 함께 처리해야 한다(적대적 리뷰 재지적) — authoritative 상태 패킷의
                // 레인 집합이 줄어들면(레인 일부만 내려가는 경우 등) 사라진 레인은 온라인 상태로
                // 영구히 남아 대시보드가 실제 구성과 어긋난다. 그 레인은 laneSnapshot(위 replaceLaneNumbers로
                // 이미 교체됨)에서도 빠지므로 커넥션 종료 시의 오프라인 일괄 처리 대상에도 잡히지 않는다.
                // 사라진 레인을 여기서 명시적으로 오프라인 처리하고 onlineLanesRecorded에서도 제거해야,
                // 그 레인이 나중에 다시 나타났을 때도 "새로 나타난 레인"으로 인식되어 온라인 갱신이
                // 정상적으로 다시 큐잉된다. (센서만 끊겨 currentLaneSet에서 빠진 레인도 동일하게
                // 오프라인으로 전이된다.)
                val newlyOnlineLanes = currentLaneSet - state.onlineLanesRecorded
                val newlyOfflineLanes = state.onlineLanesRecorded - currentLaneSet
                if (newlyOnlineLanes.isNotEmpty()) {
                    newlyOnlineLanes.forEach { lane -> registry.enqueueNetStateUpdate(state.dtlIp, lane, online = true) }
                }
                if (newlyOfflineLanes.isNotEmpty()) {
                    newlyOfflineLanes.forEach { lane -> registry.enqueueNetStateUpdate(state.dtlIp, lane, online = false) }
                }
                if (newlyOnlineLanes.isNotEmpty() || newlyOfflineLanes.isNotEmpty()) {
                    state.onlineLanesRecorded = currentLaneSet
                }
            }
            val changes = PacketDiffer.diff(state.lastStatusPacket, packet.raw)
            if (changes.anyChanged) {
                state.lastStatusPacket = packet.raw
                logger.info(
                    "커넥션[{}] 상태 변경 감지: 레인={}, changedLanes={}",
                    state.dtlIp, lanes, changes.laneChanges.count { it.anyChanged },
                )
            }
        } else if (packet.objectCode == SpeedGateProtocolConstants.ObjectCode.GATE_LOG) {
            gateLogService.handle(state.dtlIp, packet)
        } else {
            logger.debug("커넥션[{}] objectCode={} 패킷 수신(상세 저장은 후속 작업)", state.dtlIp, packet.objectCode)
        }
    }
}
