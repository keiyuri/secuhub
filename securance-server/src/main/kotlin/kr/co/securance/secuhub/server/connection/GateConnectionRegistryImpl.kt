package kr.co.securance.secuhub.server.connection

import kotlinx.coroutines.reactive.awaitFirstOrNull
import kr.co.securance.secuhub.common.exception.GateTaskRejectedException
import kr.co.securance.secuhub.domain.entity.NetState
import kr.co.securance.secuhub.domain.entity.NetStateId
import kr.co.securance.secuhub.domain.repository.NetStateRepository
import kr.co.securance.secuhub.server.db.GateDbWriteQueue
import kr.co.securance.secuhub.server.db.GateDbWriteTask
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * [GateConnectionRegistry]의 기본 구현. [kr.co.securance.secuhub.server.tcp.GateTcpServer]/
 * `GateTcpClient`(CLIENT 모드, 후속 구현)가 커넥션을 등록/해제하는 데도 사용한다.
 *
 * 커넥션 키는 소켓 포트가 아니라 **디바이스 IP**다 — 재연결 시 소스 포트가 바뀌어도 하나의
 * 논리적 커넥션으로 취급해 레이스 컨디션을 원천 차단한다(계획서 3.3절).
 */
@Component
class GateConnectionRegistryImpl(
    private val netStateRepository: NetStateRepository,
    private val dbWriteQueue: GateDbWriteQueue,
) : GateConnectionRegistry {

    private val logger = LoggerFactory.getLogger(GateConnectionRegistryImpl::class.java)
    private val connections = ConcurrentHashMap<String, GateConnectionState>()

    /**
     * 새 커넥션을 등록한다. 같은 IP의 기존 커넥션이 있으면 (DB 오프라인 반영 없이) 먼저 닫는다 —
     * 새 소켓이 이미 살아있는 상태에서 옛 소켓의 종료 콜백이 뒤늦게 "오프라인"을 기록해 온라인
     * 상태를 덮어쓰는 레거시 버그 패턴을 피하기 위함(계획서 3.3절 재연결 레이스 가드).
     */
    fun register(state: GateConnectionState) {
        val previous = connections.put(state.dtlIp, state)
        if (previous != null && !previous.actor.isClosed) {
            previous.actor.close()
            logger.info("커넥션[{}] 재연결 감지 — 이전 커넥션을 교체합니다.", state.dtlIp)
        }
    }

    override fun allConnections(): Collection<GateConnectionState> = connections.values.toList()

    override fun findConnection(dtlIp: String): GateConnectionState? = connections[dtlIp]

    override suspend fun closeConnection(dtlIp: String, updateNetState: Boolean) {
        val state = connections.remove(dtlIp) ?: return
        state.actor.close()
        if (updateNetState) {
            state.laneSnapshot().forEach { lane -> enqueueNetStateUpdate(state, lane, online = false) }
        }
    }

    /**
     * 커넥션의 액터 체인에 소켓 write를 태운다.
     *
     * 레거시 H-4 버그(ACK를 DB 워커 스레드에서 직접 `Socket.Send`로 내보내 DB 지연이 그대로
     * 통신 지연으로 번지던 문제)의 재발 방지 지점이다 — **모든 송신은 반드시 이 경로를 통한다.**
     * 레인 소유 검사를 하지 않으므로, 레인이 특정되지 않는 ACK 회신 등에 쓴다.
     */
    fun sendRaw(state: GateConnectionState, packet: ByteArray): Boolean =
        try {
            state.actor.submit {
                state.outbound.sendByteArray(Mono.just(packet)).then().awaitFirstOrNull()
            }
            true
        } catch (ex: GateTaskRejectedException) {
            logger.warn("커넥션[{}] 전송 거부(대기열 초과)", state.dtlIp, ex)
            false
        }

    override fun sendToLane(dtlIp: String, dtlLaneNo: Int, packet: ByteArray, trackForAck: Boolean): Boolean {
        val state = connections[dtlIp] ?: return false
        if (!state.ownsLane(dtlLaneNo) && state.hasAuthoritativeLaneInfo) return false
        val accepted = sendRaw(state, packet)
        // ACK 프레임 자체엔 대상 레인 정보가 없으므로, 나중에 도착할 ACK를 이 레인과 상관시킬 수
        // 있도록 전송 순서를 기록해 둔다(Codex 리뷰 P1 — GateConnectionState.recordSentLane 참고).
        // trackForAck=false(상태 폴링 등)인 전송은 기록하지 않는다 — 상관관계가 필요 없는 전송이
        // 이 큐에 섞이면 뒤이어 도착한 제어 명령 ACK가 엉뚱한 레인으로 잘못 귀속된다
        // (Codex 어드버서리얼 리뷰 대응).
        if (accepted && trackForAck) state.recordSentLane(dtlLaneNo)
        return accepted
    }

    /**
     * `tb_net_state` 갱신을 파티션 큐(3.5절)에 위임한다 — 디바이스 IP당 순서가 보장된다.
     *
     * `loc_id`/`grp_id`는 커넥션 수립 시 캐시해 둔 [GateConnectionState.laneInfo]에서 읽는다
     * (레거시 M-8: 패킷마다 `tb_gate_dtl`을 재조회하던 N+1 제거). 레인이 `tb_gate_dtl`에
     * 아직 등록되지 않은 경우에만 대표 레인 값으로 대체하고, 그것도 없으면 기록을 건너뛴다 —
     * (0,0)이라는 존재하지 않는 키로 행을 만들면 화면 조인에서 영영 보이지 않기 때문이다.
     */
    fun enqueueNetStateUpdate(state: GateConnectionState, dtlLaneNo: Int, online: Boolean) {
        val dtlIp = state.dtlIp
        val info = state.laneInfoOf(dtlLaneNo) ?: state.primaryLaneInfo
        if (info == null) {
            logger.warn("커넥션[{}] 레인 {}의 게이트 정보가 없어 tb_net_state 갱신을 건너뜁니다.", dtlIp, dtlLaneNo)
            return
        }
        val locId = info.locId
        val grpId = info.grpId

        dbWriteQueue.enqueue(
            GateDbWriteTask(
                partitionKey = dtlIp,
                operationName = "UpdateNetState($dtlIp,$dtlLaneNo,$online)",
            ) {
                val id = NetStateId(dtlIp = dtlIp, dtlLaneNo = dtlLaneNo, locId = locId, grpId = grpId)
                val entity = netStateRepository.findById(id).orElseGet { NetState(id = id) }
                entity.dtlState = if (online) "Y" else "N"
                entity.checkTime = java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmm"))
                netStateRepository.save(entity)
                Unit
            },
        )
    }
}
