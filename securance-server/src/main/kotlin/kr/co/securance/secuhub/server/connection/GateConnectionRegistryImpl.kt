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
            state.laneSnapshot().forEach { lane -> enqueueNetStateUpdate(dtlIp, lane, online = false) }
        }
    }

    override suspend fun sendToLane(dtlIp: String, dtlLaneNo: Int, packet: ByteArray): Boolean {
        val state = connections[dtlIp] ?: return false
        if (!state.ownsLane(dtlLaneNo) && state.hasAuthoritativeLaneInfo) return false
        return enqueueSend(state, packet, "lane=$dtlLaneNo")
    }

    override suspend fun sendToConnection(dtlIp: String, packet: ByteArray): Boolean {
        val state = connections[dtlIp] ?: return false
        return enqueueSend(state, packet, "connection")
    }

    /**
     * 액터 큐에 전송 작업을 넣고, 실제 소켓 쓰기가 완료(성공/실패)될 때까지 대기한 뒤 결과를
     * 반환한다 — 큐잉 성공 여부만 보고 반환하던 예전 구현은 [GateConnectionRegistry.sendToLane]
     * 문서의 Codex 리뷰 수정 사유를 참고.
     */
    private suspend fun enqueueSend(state: GateConnectionState, packet: ByteArray, logContext: String): Boolean =
        try {
            state.actor.submitAndAwait {
                state.outbound.sendByteArray(Mono.just(packet)).then().awaitFirstOrNull()
            }
        } catch (ex: GateTaskRejectedException) {
            logger.warn("커넥션[{}] 전송 거부(대기열 초과): {}", state.dtlIp, logContext, ex)
            false
        }

    /** `tb_net_state` 갱신을 파티션 큐(3.5절)에 위임한다 — 디바이스 IP당 순서가 보장된다. */
    fun enqueueNetStateUpdate(dtlIp: String, dtlLaneNo: Int, online: Boolean) {
        dbWriteQueue.enqueue(
            GateDbWriteTask(
                partitionKey = dtlIp,
                operationName = "UpdateNetState($dtlIp,$dtlLaneNo,$online)",
            ) {
                // 1차 스캐폴드에서는 loc_id/grp_id를 조회하지 않고 (0,0)으로 남겨 패턴만 증명한다.
                // 실제 구현에서는 GateDetailRepository로 loc_id/grp_id를 조회해 채운다(후속 작업).
                val id = NetStateId(dtlIp = dtlIp, dtlLaneNo = dtlLaneNo, locId = 0, grpId = 0)
                val entity = netStateRepository.findById(id).orElseGet { NetState(id = id) }
                entity.dtlState = if (online) "Y" else "N"
                entity.checkTime = java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmm"))
                netStateRepository.save(entity)
                Unit
            },
        )
    }
}
