package kr.co.securance.secuhub.server.tcp

import kr.co.securance.secuhub.protocol.GatePacket
import kr.co.securance.secuhub.protocol.PacketDiffer
import kr.co.securance.secuhub.protocol.SpeedGateProtocolConstants
import kr.co.securance.secuhub.server.connection.GateConnectionRegistryImpl
import kr.co.securance.secuhub.server.connection.GateConnectionState
import kr.co.securance.secuhub.server.control.GateControlDispatcher
import kr.co.securance.secuhub.server.db.GatePacketPersister
import kr.co.securance.secuhub.server.db.OprStatusPersister
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 기본 패킷 핸들러 — 수신 패킷 1개의 전체 처리 흐름을 담당한다.
 *
 * 레거시 `SpeedServer.ProcessReceiveData`에 대응하며, 처리 순서는 다음과 같다.
 * 1. ACK 패킷(CMD1 0x07/0x08)은 `tb_data_rcv_ack`에 적재하고 종료한다(ACK에 ACK를 보내지 않는다).
 * 2. 대표 레인 번호를 구해 커넥션의 레인 집합에 **합집합으로만** 추가한다.
 * 3. `GATE_STATUS`(0x4D)일 때만 레인 집합을 authoritative하게 교체하고 `tb_net_state`를 온라인으로 기록한다.
 * 4. 직전 패킷과 비교해 변경이 있을 때만 객체 코드별 상세 저장을 큐에 넣는다.
 * 5. **변경 여부와 무관하게 항상 ACK를 회신한다.**
 *
 * 5번은 레거시의 실제 버그를 명시적으로 되풀이하지 않기 위한 것이다 — 레거시는 "변경 없음"
 * 분기의 `return`이 메서드 전체를 빠져나가 정상 운영 중 대부분의 주기 상태 패킷에 ACK가 전혀
 * 나가지 않았다(`SpeedServer.cs:841` 주석 참고). 여기서는 ACK 전송을 [handle]의 마지막에 두고
 * 중간 분기는 조기 종료하지 않는 구조로 만들어 구조적으로 재발을 막는다.
 *
 * `GATE_STATUS`(0x4D) 패킷 수신 시 레인 집합을 authoritative하게 갱신하고 `tb_net_state`를
 * 온라인으로 기록한다. 같은 패킷 끝에 로그 엔트리가 이어 붙어 있으면(레거시 검증 결과 —
 * [GateLogService] 클래스 KDoc "레이아웃 정정" 참고) [GateLogService.handleEmbedded]에 위임해
 * 저장한다(계획서 3.8절, 신규 설계). 문서상 정의된 독립 `GATE_LOG`(0x61) ObjectCode도 하위 호환
 * 경로로 계속 지원한다([GateLogService.handle]). 그 외 객체 코드는 로그만 남긴다 — 상세 저장
 * (tb_data_rcv 등)은 `GatePacketPersister`가 담당한다.
 */
@Component
class DefaultGatePacketHandler(
    private val registry: GateConnectionRegistryImpl,
    private val persister: GatePacketPersister,
    private val controlDispatcher: GateControlDispatcher,
    private val gateLogService: GateLogService,
    private val oprStatusPersister: OprStatusPersister,
) : GatePacketHandler {

    private val logger = LoggerFactory.getLogger(DefaultGatePacketHandler::class.java)

    override suspend fun handle(state: GateConnectionState, packet: GatePacket) {
        // 1. 게이트가 보낸 ACK — 저장만 하고 종료(회신 대상이 아니다).
        if (packet.command1 == SpeedGateProtocolConstants.Command1.SEND_ACK ||
            packet.command1 == SpeedGateProtocolConstants.Command1.REQUEST_ACK
        ) {
            val laneNo = ackLaneNo(state, packet)
            state.ensureLaneKnown(laneNo)
            persister.persistAck(state, packet.raw, laneNo)

            // 제어 명령(0x4C)에 대한 ACK면 디스패처에 알려 `chk_yn`을 확정하게 한다.
            // 여기서는 메모리 플래그만 세우고 DB는 건드리지 않는다 — 통신 스레드에서 DB I/O를
            // 수행하던 레거시 H-4 패턴을 재현하지 않기 위함(실제 갱신은 SendControlJob 스레드).
            if (packet.objectCode == SpeedGateProtocolConstants.ObjectCode.GATE_SETTING) {
                controlDispatcher.onDeviceControlAck(state.dtlIp, laneNo)
            }

            logger.debug("커넥션[{}] ACK 수신: objectCode=0x{}", state.dtlIp, "%02X".format(packet.objectCode))
            return
        }

        // 2. 대표 레인 번호 — 합집합 추가만 한다(레거시 H-3: 여기서 교체하면 다중 레인 소켓의
        //    레인 집합이 대표 레인 1개로 축소되어 제어 명령이 특정 레인에 전달되지 않는다).
        val laneNo = representativeLaneNo(state, packet)
        state.ensureLaneKnown(laneNo)

        val isStatusPacket = packet.objectCode == SpeedGateProtocolConstants.ObjectCode.GATE_STATUS

        // 3. 0x4D 상태 패킷만 레인 집합을 authoritative하게 교체한다(레거시 H-1/H-3).
        if (isStatusPacket) {
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
        }

        // 4. 변경분만 저장 — 상태 패킷은 직전 패킷과의 영역 비교로, 그 외 패킷은 항상 저장한다
        //    (설정/모터/스케줄/휴일은 저빈도이고 내용 자체가 곧 변경 이벤트다).
        if (isStatusPacket) {
            val changes = PacketDiffer.diff(state.lastStatusPacket, packet.raw)
            if (changes.anyChanged) {
                state.lastStatusPacket = packet.raw
                persister.persistReceivedPacket(state, packet, laneNo)
                // 원시 저장과 분석 적재를 한 지점에서 함께 호출한다 — 레거시는 원시 INSERT의 DB
                // 트리거가 분석을 수행해 "저장은 됐는데 분석은 안 된" 상태를 추적할 수 없었다.
                persister.persistStatusAnalysis(state, packet.raw)
                // 통행량 집계(tb_opr_status, 2026-08-12 B6) — 레거시 usp_process_status 이식.
                // errType과 무관하게 항상 반영한다(장애 여부와 통행량 집계는 별개).
                oprStatusPersister.persistOprStatus(state, packet.raw)
                logger.debug(
                    "커넥션[{}] 상태 변경 감지: lane={}, changedLanes={}",
                    state.dtlIp, laneNo, changes.laneChanges.count { it.anyChanged },
                )
            }

            // 로그 엔트리는 GATE_STATUS 패킷 끝에 이어 붙어 온다(레거시 SpeedServer.cs 검증 결과 —
            // GateLogService 클래스 KDoc "레이아웃 정정" 참고). 헤더의 DATA_COUNT 필드가 곧 로그
            // 건수이며, 상태 변경 여부(changes.anyChanged)와 무관하게 로그는 항상 확인한다 — 레거시도
            // IsLogChanged를 상태 변경과 별개로 판단했다.
            if (packet.dataCount > 0) {
                val laneCount = PacketDiffer.laneCountOf(packet.raw)
                val logStart = SpeedGateProtocolConstants.HEADER_LENGTH +
                    SpeedGateProtocolConstants.DATA_INFO_LENGTH +
                    laneCount * SpeedGateProtocolConstants.STATUS_DATA_LENGTH
                gateLogService.handleEmbedded(state.dtlIp, packet.raw, logStart, packet.dataCount)
            }
        } else if (packet.objectCode == SpeedGateProtocolConstants.ObjectCode.GATE_LOG) {
            gateLogService.handle(state.dtlIp, packet)
        } else {
            persister.persistReceivedPacket(state, packet, laneNo)
        }

        // 5. ACK 회신 — 변경이 없어도 반드시 보낸다. 소켓 write는 커넥션 액터 체인에서 수행되므로
        //    (registry.sendRaw) DB 쓰기 워커나 다른 커넥션을 막지 않는다(레거시 H-4).
        sendAck(state, packet)
    }

    private fun sendAck(state: GateConnectionState, packet: GatePacket) {
        val ack = try {
            state.codec.buildAck(packet.objectCode)
        } catch (ex: IllegalArgumentException) {
            logger.error("커넥션[{}] ACK 패킷 생성 실패: objectCode=0x{}", state.dtlIp, "%02X".format(packet.objectCode), ex)
            return
        }
        if (!registry.sendRaw(state, ack)) {
            // 전송 자체가 이뤄지지 않은 경우 — 장비는 ACK를 못 받아 같은 패킷을 재전송하며 포화를
            // 심화시킬 수 있으므로 반드시 가시화한다(레거시 SendAckData의 체인 거부 로깅과 동일 취지).
            logger.warn("커넥션[{}] ACK 전송이 거부되었습니다: objectCode=0x{}", state.dtlIp, "%02X".format(packet.objectCode))
        }
    }

    /**
     * 패킷의 대표 레인 번호를 구한다.
     *
     * 상태 패킷은 첫 레인 상태 블록의 첫 바이트가 레인 번호다. 그 외 패킷은 레인 정보를
     * 신뢰할 수 없으므로 커넥션 캐시의 대표 레인을 쓰고, 그것마저 없으면 1로 둔다
     * (레거시도 파싱 실패 시 기본값 1을 사용했다).
     */
    private fun representativeLaneNo(state: GateConnectionState, packet: GatePacket): Int {
        if (packet.objectCode == SpeedGateProtocolConstants.ObjectCode.GATE_STATUS) {
            PacketDiffer.laneNumbersOf(packet.raw).firstOrNull { it > 0 }?.let { return it }
        }
        return state.primaryLaneInfo?.dtlLaneNo ?: 1
    }

    /**
     * ACK 패킷(CMD1 0x07/0x08)의 대상 레인 번호를 구한다(Codex 리뷰 P1 대응).
     *
     * ACK 프레임(Header + 7바이트 타임싱크 DataInfo + Tail)에는 레인 정보가 전혀 없다 — 이전
     * 코드는 `packet.objectCode == GATE_STATUS`일 때 [PacketDiffer.laneNumbersOf]로 74바이트
     * 상태 블록을 찾으려 했지만, ACK 프레임은 애초에 그 구조가 아니라 항상 빈 결과만 나왔고,
     * 그 외 모든 objectCode(예: 제어 명령 GATE_SETTING의 ACK)는 무조건 [GateConnectionState.primaryLaneInfo]
     * (대표 레인 고정)로 귀속되어 다중 레인 소켓에서 레인 2로 보낸 명령의 ACK가 레인 1의 것으로
     * 잘못 확인 처리되던 문제가 있었다.
     *
     * 이제는 [GateConnectionState.pollSentLane]으로, 이 소켓이 직전에 실제로 전송한 **제어 명령**
     * 레인을 FIFO 순서로 상관시킨다 — TCP 순서 보장 + 장비가 받은 순서대로 응답한다는 가정에
     * 기반한다(프로토콜에 상관관계 ID가 없는 이상 이보다 나은 방법은 없다). 추적된 전송이
     * 없으면(예: 재기동 직후 장비가 보낸 잉여 ACK) 대표 레인으로 대체한다.
     *
     * **큐는 `objectCode == GATE_SETTING`(0x4C) ACK에서만 소비한다**(Codex 어드버서리얼 리뷰
     * 대응). 큐에는 [GateConnectionRegistryImpl.sendToLane]의 `trackForAck=true`(기본값, 제어
     * 명령 전송만 해당) 항목만 쌓이지만, 그렇더라도 이 소켓에서 제어 명령이 아닌 다른 종류의
     * ACK(예: 향후 추가될 오브젝트의 ACK)가 먼저 도착하면 그 ACK가 엉뚱하게 제어 명령용 큐
     * 항목을 하나 소비해 버려 정작 그 제어 명령의 ACK가 도착했을 때 상관시킬 항목이 없어진다.
     * ACK의 `objectCode`로 "이것이 제어 명령 ACK인가"를 먼저 확인한 뒤에만 큐를 건드리면 이
     * 상호 오염을 막을 수 있다.
     */
    private fun ackLaneNo(state: GateConnectionState, packet: GatePacket): Int {
        if (packet.objectCode == SpeedGateProtocolConstants.ObjectCode.GATE_SETTING) {
            state.pollSentLane()?.let { return it }
        }
        return state.primaryLaneInfo?.dtlLaneNo ?: 1
    }
}
