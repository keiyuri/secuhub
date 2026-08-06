package kr.co.securance.secuhub.server.connection

import kotlinx.coroutines.reactive.awaitFirstOrNull
import kr.co.securance.secuhub.common.exception.GateTaskRejectedException
import kr.co.securance.secuhub.domain.entity.NetState
import kr.co.securance.secuhub.domain.entity.NetStateId
import kr.co.securance.secuhub.domain.repository.GateDetailRepository
import kr.co.securance.secuhub.domain.repository.NetStateRepository
import kr.co.securance.secuhub.server.db.GateDbWriteQueue
import kr.co.securance.secuhub.server.db.GateDbWriteTask
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock

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
    private val gateDetailRepository: GateDetailRepository,
) : GateConnectionRegistry {

    private val logger = LoggerFactory.getLogger(GateConnectionRegistryImpl::class.java)
    private val connections = ConcurrentHashMap<String, GateConnectionState>()

    // 적대적 리뷰(codex) 지적: GateDbWriteQueue는 타임아웃된 시도를 백그라운드에 "버려둔 채" 다음
    // 시도/작업으로 넘어간다(GateDbWriteQueue.kt KDoc 참고) — 그 버려진 실행이 뒤늦게 실제로 DB에
    // 도달하면, 이미 재시도나 그 뒤의 더 최신 이벤트(예: OFFLINE 다음에 온 ONLINE)가 먼저 반영된
    // net_state 행을 오래된 값으로 덮어써 순서가 역전될 수 있다. enqueueNetStateUpdate 호출 시점에
    // (실행 시점이 아니라!) 파티션키(NetStateId)별 단조증가 시퀀스를 발급해두고, 실제 저장 직전에
    // "내 시퀀스가 이미 적용된 시퀀스보다 새롭지 않으면 쓰지 않는다"를 강제해 역전을 막는다.
    //
    // **2차 적대적 리뷰 지적**: "클레임 후 findById/save"를 락 없이 순서대로만 하면, 오래된 실행이
    // 클레임에는 통과했지만 그 뒤 findById/save가 DB 지연으로 느려지는 동안 더 최신 실행이 끼어들어
    // 먼저 클레임+저장을 끝내고, 그 다음 오래된 실행이 재개돼 181행에서 최신 값을 덮어쓰는 TOCTOU
    // 윈도우가 남는다. 시퀀스 값 자체를 DB 행에 저장해 조건부 UPDATE(`WHERE applied_seq < :seq`)를
    // 쓰는 것이 정석이지만 스키마 변경이 필요하다 — 대신 [netStateWriteLocks]로 "클레임+조회+저장"
    // 전체를 같은 [NetStateId]에 대해 상호 배제해, 그 사이에 다른 실행이 끼어들 수 없게 한다.
    // (이 실행들은 이미 [GateDbWriteQueue]의 전용 블로킹 스레드풀에서 돌기 때문에, 여기서 블로킹
    // 락을 잡아도 다른 파티션/샤드의 처리량에는 영향이 없다.)
    private val netStateWriteSequence = AtomicLong(0)
    private val lastAppliedNetStateSeq = ConcurrentHashMap<NetStateId, Long>()
    private val netStateWriteLocks = ConcurrentHashMap<NetStateId, ReentrantLock>()

    private fun lockFor(id: NetStateId): ReentrantLock = netStateWriteLocks.computeIfAbsent(id) { ReentrantLock() }

    /**
     * [id]에 대해 [seq]가 지금까지 적용된 시퀀스보다 새로울 때만(또는 같은 작업 자신의 재시도일 때만)
     * 원자적으로 "적용됨"으로 표시한다. 실패하면 이미 더 최신(또는 동일 시점의 경쟁) 쓰기가 적용됐다는
     * 뜻이므로 호출자는 실제 DB 쓰기를 건너뛰어야 한다. 반드시 [lockFor]로 해당 [id]를 잠근 상태에서만
     * 호출해야 한다 — 클레임과 실제 저장이 같은 락 구간 안에 있어야 그 사이에 다른 실행이 끼어들지 못한다.
     */
    private fun tryClaimNetStateSeq(id: NetStateId, seq: Long): Boolean {
        var claimed = false
        lastAppliedNetStateSeq.compute(id) { _, current ->
            if (current == null || seq >= current) {
                claimed = true
                seq
            } else {
                current
            }
        }
        return claimed
    }

    /**
     * 새 커넥션을 등록한다. 같은 IP의 기존 커넥션이 있으면 (DB 오프라인 반영 없이) 먼저 닫는다 —
     * 새 소켓이 이미 살아있는 상태에서 옛 소켓의 종료 콜백이 뒤늦게 "오프라인"을 기록해 온라인
     * 상태를 덮어쓰는 레거시 버그 패턴을 피하기 위함(계획서 3.3절 재연결 레이스 가드).
     */
    fun register(state: GateConnectionState) {
        val previous = connections.put(state.dtlIp, state)
        if (previous != null && previous !== state) {
            if (!previous.actor.isClosed) {
                previous.actor.close()
            }
            // 액터만 닫으면 이전 물리 소켓은 자연 종료될 때까지 열린 채로 남는다 — 여기서 명시적으로
            // dispose해 재연결이 잦을 때 소켓/파일 디스크립터가 누적되지 않도록 한다.
            if (!previous.connection.isDisposed) {
                previous.connection.dispose()
            }
            logger.info("커넥션[{}] 재연결 감지 — 이전 커넥션을 교체합니다.", state.dtlIp)
        }
    }

    override fun allConnections(): Collection<GateConnectionState> = connections.values.toList()

    override fun findConnection(dtlIp: String): GateConnectionState? = connections[dtlIp]

    override suspend fun closeConnection(dtlIp: String, updateNetState: Boolean) {
        val state = connections.remove(dtlIp) ?: return
        finalizeClose(dtlIp, state, updateNetState)
    }

    override suspend fun closeConnectionIfCurrent(
        dtlIp: String,
        expected: GateConnectionState,
        updateNetState: Boolean,
    ): Boolean {
        // ConcurrentHashMap.remove(key, value)는 "현재 값이 value와 같을 때만 제거"를 원자적으로
        // 수행한다 — findConnection()으로 먼저 확인하고 나중에 closeConnection()을 호출하는 방식과
        // 달리, 그 사이에 재연결이 끼어들 여지가 없다.
        if (!connections.remove(dtlIp, expected)) return false
        finalizeClose(dtlIp, expected, updateNetState)
        return true
    }

    private fun finalizeClose(dtlIp: String, state: GateConnectionState, updateNetState: Boolean) {
        state.actor.close()
        // 물리 소켓도 함께 dispose한다(적대적 리뷰 지적) — 예전에는 여기서 registry/액터만 정리하고
        // 실제 Reactor Netty 커넥션은 그대로 열어뒀다. 보통은 onDispose(소켓이 이미 닫혀서 이 경로가
        // 호출된 경우)에서는 무해한 중복 호출이지만, closeConnection()/closeConnectionIfCurrent()가
        // "소켓은 열려있는데 애플리케이션 쪽에서 먼저 끊기로 결정한" 경우(예: NetCheckJob의 유휴 감지,
        // 서버 shutdown)에는 이 dispose()가 없으면 소켓이 실제로 닫히지 않아 리소스가 샌다.
        // register()의 재연결 교체 로직과 동일한 패턴(isDisposed 가드 후 dispose).
        if (!state.connection.isDisposed) {
            state.connection.dispose()
        }
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

    /**
     * `tb_net_state` 갱신을 파티션 큐(3.5절)에 위임한다 — 디바이스 IP당 순서가 보장된다.
     *
     * `tb_net_state`의 복합키는 `(dtl_ip, dtl_lane_no, loc_id, grp_id)`이므로, `tb_gate_dtl`에
     * 등록된 실제 loc_id/grp_id로 채워야 한다(loc_id=0/grp_id=0으로 고정하면 위치/그룹별로
     * `tb_net_state`를 조회·집계하는 화면이 항상 빈 결과를 받는다).
     */
    fun enqueueNetStateUpdate(dtlIp: String, dtlLaneNo: Int, online: Boolean) {
        // 실행 시점이 아니라 "이 이벤트가 실제로 발생한 순서"를 반영해야 하므로 큐에 넣기 전,
        // 즉 호출 시점에 시퀀스를 발급한다(GateDbWriteQueue의 타임아웃/버려진 실행 재시도로 인한
        // 순서 역전 방지 — 클래스 상단 주석 참고).
        val seq = netStateWriteSequence.incrementAndGet()
        dbWriteQueue.enqueue(
            GateDbWriteTask(
                partitionKey = dtlIp,
                operationName = "UpdateNetState($dtlIp,$dtlLaneNo,$online)",
            ) {
                val gateDetail = gateDetailRepository.findByDtlIpAndDtlLaneNo(dtlIp, dtlLaneNo)
                if (gateDetail == null) {
                    // tb_gate_dtl에 없는 레인 — 접속은 됐지만 아직(혹은 더 이상) 등록되지 않은
                    // 상태다. loc_id/grp_id를 알 수 없으므로 net_state 갱신 자체를 건너뛴다
                    // (0으로 잘못 채워 넣어 실제 데이터와 섞이는 것보다 안전하다).
                    logger.warn(
                        "net_state 갱신을 건너뜁니다: tb_gate_dtl에 없는 레인(dtlIp={}, lane={})",
                        dtlIp, dtlLaneNo,
                    )
                    return@GateDbWriteTask
                }
                val id = NetStateId(
                    dtlIp = dtlIp,
                    dtlLaneNo = dtlLaneNo,
                    locId = requireNotNull(gateDetail.location.locId),
                    grpId = requireNotNull(gateDetail.group.grpId),
                )
                // 클레임부터 실제 저장까지를 같은 [id]에 대해 통째로 상호 배제한다(2차 적대적 리뷰
                // 지적) — 클레임만 원자적으로 하고 조회/저장은 락 밖에서 하면, 그 사이의 DB 지연
                // 동안 더 최신 실행이 끼어들어 먼저 끝낼 수 있고 이후 오래된 실행이 재개돼 최신
                // 값을 덮어쓰는 순서 역전 창이 남는다.
                val lock = lockFor(id)
                lock.lock()
                try {
                    // 이미 더 최신(더 큰 seq) 쓰기가 적용된 뒤라면(예: 이 실행이 타임아웃 후
                    // 버려졌다가 뒤늦게 여기 도달한 경우) 쓰지 않고 건너뛴다. 같은 작업 자신의
                    // 재시도(seq 동일)는 정상적으로 다시 클레임된다.
                    if (!tryClaimNetStateSeq(id, seq)) {
                        logger.warn(
                            "net_state 갱신을 건너뜁니다: 더 최신 갱신이 이미 적용되었습니다(dtlIp={}, lane={}, seq={})",
                            dtlIp, dtlLaneNo, seq,
                        )
                        return@GateDbWriteTask
                    }
                    val entity = netStateRepository.findById(id).orElseGet { NetState(id = id) }
                    entity.dtlState = if (online) "Y" else "N"
                    entity.checkTime = java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmm"))
                    netStateRepository.save(entity)
                } finally {
                    lock.unlock()
                }
                Unit
            },
        )
    }
}
