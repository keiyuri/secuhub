package kr.co.securance.secuhub.server.connection

import kotlinx.coroutines.reactive.awaitFirstOrNull
import kr.co.securance.secuhub.common.exception.GateTaskRejectedException
import kr.co.securance.secuhub.domain.entity.NetState
import kr.co.securance.secuhub.domain.entity.NetStateId
import kr.co.securance.secuhub.domain.repository.GateDetailRepository
import kr.co.securance.secuhub.domain.repository.NetStateRepository
import kr.co.securance.secuhub.server.config.ServerModeConfig
import kr.co.securance.secuhub.server.db.GateDbWriteQueue
import kr.co.securance.secuhub.server.db.GateDbWriteTask
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono
import java.time.Duration
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeoutException
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
    private val serverModeConfig: ServerModeConfig,
) : GateConnectionRegistry {

    private val logger = LoggerFactory.getLogger(GateConnectionRegistryImpl::class.java)
    private val connections = ConcurrentHashMap<String, GateConnectionState>()
    private val writeTimeout: Duration get() = Duration.ofSeconds(serverModeConfig.writeTimeoutSeconds)

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
    // 전체를 같은 키에 대해 상호 배제해, 그 사이에 다른 실행이 끼어들 수 없게 한다.
    // (이 실행들은 이미 [GateDbWriteQueue]의 전용 블로킹 스레드풀에서 돌기 때문에, 여기서 블로킹
    // 락을 잡아도 다른 파티션/샤드의 처리량에는 영향이 없다.)
    //
    // **3차 리뷰 지적(캐시 무제한 증가)**: 예전에는 이 두 맵의 키가 [NetStateId](dtlIp, dtlLaneNo,
    // locId, grpId) 전체였다 — 같은 물리 장치가 그룹/위치를 재배정받을 때마다(tb_gate_dtl.loc_id/
    // grp_id 변경) NetStateId가 바뀌어 새 엔트리가 쌓이고, 옛 엔트리는 영원히 남는 단순 메모리
    // 누수였을 뿐 아니라 **정합성 버그**이기도 했다: 재배정 직후 첫 쓰기는 새 NetStateId 기준
    // lastAppliedNetStateSeq가 비어 있으니 무조건 통과되어, 재배정 전에 이미 적용된 더 최신 시퀀스를
    // 무시하고 순서 역전 가드가 사실상 리셋되는 셈이었다. 시퀀스/락의 대상은 "이 물리 장치·레인에 대한
    // 쓰기 순서"이지 tb_gate_dtl의 loc_id/grp_id 소속이 아니므로, 키를 (dtlIp, dtlLaneNo)로 정규화해
    // 두 문제를 함께 해결한다 — 카디널리티도 이제 "지금까지 존재했던 물리 장치·레인 수"로 묶여
    // NetStateId보다 훨씬 느리게 증가한다.
    //
    // 완전한 TTL/크기 상한 evict은 도입하지 않는다: [netStateWriteLocks]에서 사용 중인 락을 다른
    // 스레드가 임의로 제거하면, 그 사이 새로 들어온 호출이 computeIfAbsent로 별도의 새 Lock 인스턴스를
    // 얻어 같은 키에 대해 서로 다른 락 객체로 "동시에" 임계구역에 들어갈 수 있다 — 상호 배제 자체가
    // 깨지는 레이스라 이번 라운드에서는 채택하지 않는다. 정석 해법(228행 주석 참고: DB에 applied_seq
    // 컬럼을 두고 조건부 UPDATE)은 스키마 변경이 필요해 범위 밖으로 남겨둔다.
    private val netStateWriteSequence = AtomicLong(0)
    private val lastAppliedNetStateSeq = ConcurrentHashMap<Pair<String, Int>, Long>()
    private val netStateWriteLocks = ConcurrentHashMap<Pair<String, Int>, ReentrantLock>()

    private fun lockFor(key: Pair<String, Int>): ReentrantLock = netStateWriteLocks.computeIfAbsent(key) { ReentrantLock() }

    /**
     * [key](dtlIp, dtlLaneNo)에 대해 [seq]가 지금까지 적용된 시퀀스보다 새로울 때만(또는 같은 작업
     * 자신의 재시도일 때만) 원자적으로 "적용됨"으로 표시한다. 실패하면 이미 더 최신(또는 동일 시점의
     * 경쟁) 쓰기가 적용됐다는 뜻이므로 호출자는 실제 DB 쓰기를 건너뛰어야 한다. 반드시 [lockFor]로
     * 해당 [key]를 잠근 상태에서만 호출해야 한다 — 클레임과 실제 저장이 같은 락 구간 안에 있어야
     * 그 사이에 다른 실행이 끼어들지 못한다.
     */
    private fun tryClaimNetStateSeq(key: Pair<String, Int>, seq: Long): Boolean {
        var claimed = false
        lastAppliedNetStateSeq.compute(key) { _, current ->
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
                awaitWrite(state, packet)
            }
            true
        } catch (ex: GateTaskRejectedException) {
            logger.warn("커넥션[{}] 전송 거부(대기열 초과)", state.dtlIp, ex)
            false
        }

    /**
     * 실제 소켓 쓰기 1건을 수행하고, [writeTimeout] 안에 끝나지 않으면 커넥션을 닫는다.
     *
     * 코드 리뷰 지적(2026-08-14): 원격이 응답 없이 멈추거나 TCP 송신 버퍼가 계속 가득 차 있으면
     * `sendByteArray(...)`가 영원히 완료되지 않아, 이 write가 실행 중인 [GateConnectionActor]의
     * 워커 코루틴이 무기한 블로킹되고 뒤이은 ACK/제어 명령 전송이 전부 밀린다(head-of-line
     * blocking). [ServerModeConfig.writeTimeoutSeconds] 안에 끝나지 않으면 타임아웃 예외를
     * 던지는 대신 여기서 잡아 커넥션을 dispose한다 — dispose는 `GateTcpServer`의 `onDispose`
     * 가드를 트리거해 registry/net_state 정리를 기존 경로 그대로 따라가게 한다.
     */
    private suspend fun awaitWrite(state: GateConnectionState, packet: ByteArray) {
        try {
            state.outbound.sendByteArray(Mono.just(packet)).then().timeout(writeTimeout).awaitFirstOrNull()
        } catch (ex: TimeoutException) {
            logger.warn("커넥션[{}] 소켓 쓰기가 {}초 안에 끝나지 않아 연결을 닫습니다.", state.dtlIp, writeTimeout.seconds, ex)
            if (!state.connection.isDisposed) state.connection.dispose()
            throw ex
        }
    }

    override fun sendToLane(dtlIp: String, dtlLaneNo: Int, packet: ByteArray, trackForAck: Boolean): Boolean {
        val state = connections[dtlIp] ?: return false
        if (!state.ownsLane(dtlLaneNo) && state.hasAuthoritativeLaneInfo) return false
        val accepted = sendRaw(state, packet)
        // ACK 프레임 자체엔 대상 레인 정보가 없으므로, 나중에 도착할 ACK를 이 레인과 상관시킬 수
        // 있도록 전송 순서를 기록해 둔다(Codex 리뷰 P1 — GateConnectionState.recordSentLane 참고).
        // trackForAck=false(상태 폴링 등)인 전송은 기록하지 않는다 — 상관관계가 필요 없는 전송이
        // 이 큐에 섞이면 뒤이어 도착한 제어 명령 ACK가 엉뚱한 레인으로 잘못 귀속된다.
        if (accepted && trackForAck) state.recordSentLane(dtlLaneNo)
        return accepted
    }

    /**
     * 레인 소유권 검사 없이, 커넥션(디바이스 IP) 하나에 패킷을 전송하고 실제 소켓 쓰기가 완료될
     * 때까지 대기한다 — 반환값이 "큐잉됨"이 아니라 물리 전송 결과를 의미한다.
     *
     * `ReqStatusJob`의 상태 조회 요청처럼 패킷이 특정 레인이 아니라 커넥션(장치) 전체를 대상으로
     * 할 때 쓴다. [sendToLane]은 `hasAuthoritativeLaneInfo=true`인데 레인 집합이 비어 있는(예:
     * 장치가 `GATE_STATUS` 패킷에서 레인 수 0을 보고한) 특이 케이스에서 임의로 고른 대표 레인이
     * 소유권 검사에 걸려 영구적으로 전송이 거부될 수 있다 — 레인 종속적이지 않은 요청은 애초에
     * 레인 번호로 라우팅할 이유가 없으므로 이 메서드로 그 문제를 원천적으로 피한다. ACK 상관관계
     * FIFO도 오염시키지 않는다([GateConnectionState.recordSentLane] 참고).
     */
    override suspend fun sendToConnection(dtlIp: String, packet: ByteArray): Boolean {
        val state = connections[dtlIp] ?: return false
        return enqueueSend(state, packet, "connection")
    }

    /**
     * 액터 큐에 전송 작업을 넣고, 실제 소켓 쓰기가 완료(성공/실패)될 때까지 대기한 뒤 결과를
     * 반환한다([GateConnectionActor.submitAndAwait] 참고).
     */
    private suspend fun enqueueSend(state: GateConnectionState, packet: ByteArray, logContext: String): Boolean =
        try {
            state.actor.submitAndAwait {
                awaitWrite(state, packet)
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
        enqueueGuardedNetStateWrite(dtlIp, dtlLaneNo, online, seq) {
            val gateDetail = gateDetailRepository.findByDtlIpAndDtlLaneNo(dtlIp, dtlLaneNo)
            if (gateDetail == null) {
                // tb_gate_dtl에 없는 레인 — 접속은 됐지만 아직(혹은 더 이상) 등록되지 않은
                // 상태다. loc_id/grp_id를 알 수 없으므로 net_state 갱신 자체를 건너뛴다
                // (0으로 잘못 채워 넣어 실제 데이터와 섞이는 것보다 안전하다).
                logger.warn(
                    "net_state 갱신을 건너뜁니다: tb_gate_dtl에 없는 레인(dtlIp={}, lane={})",
                    dtlIp, dtlLaneNo,
                )
                null
            } else {
                NetStateId(
                    dtlIp = dtlIp,
                    dtlLaneNo = dtlLaneNo,
                    locId = requireNotNull(gateDetail.location.locId),
                    grpId = requireNotNull(gateDetail.group.grpId),
                )
            }
        }
    }

    /**
     * 커넥션 상태 객체를 이미 들고 있는 호출자(패킷 핸들러/커넥션 정리 경로)용 오버로드.
     *
     * `loc_id`/`grp_id`는 커넥션 수립 시 캐시해 둔 [GateConnectionState.laneInfo]에서 읽는다
     * (레거시 M-8: 패킷마다 `tb_gate_dtl`을 재조회하던 N+1 제거). 캐시에 해당 레인이 없고 대표
     * 레인 정보조차 없을 때만 DB 조회 경로([enqueueNetStateUpdate])로 위임한다.
     */
    fun enqueueNetStateUpdate(state: GateConnectionState, dtlLaneNo: Int, online: Boolean) {
        val info = state.laneInfoOf(dtlLaneNo) ?: state.primaryLaneInfo
        if (info == null) {
            // 캐시가 비어 있는 커넥션(연결 수립 직후 등) — DB에서 직접 확인하는 경로로 넘긴다.
            enqueueNetStateUpdate(state.dtlIp, dtlLaneNo, online)
            return
        }
        val seq = netStateWriteSequence.incrementAndGet()
        val id = NetStateId(dtlIp = state.dtlIp, dtlLaneNo = dtlLaneNo, locId = info.locId, grpId = info.grpId)
        enqueueGuardedNetStateWrite(state.dtlIp, dtlLaneNo, online, seq) { id }
    }

    /**
     * `tb_net_state` 한 행의 갱신을 DB 쓰기 큐에 넣는다. [resolveId]는 실제 쓰기 직전(워커 스레드)
     * 에 평가되며, null을 반환하면 갱신을 건너뛴다.
     *
     * 시퀀스/락 키는 (dtlIp, dtlLaneNo)로 정규화한다 — NetStateId 전체를 키로 쓰면 같은 물리
     * 장치가 그룹/위치를 재배정받아 locId/grpId가 바뀔 때마다 시퀀스 기준선이 리셋돼 순서 역전
     * 가드가 무력화된다(클래스 상단 3차 리뷰 지적 주석 참고).
     */
    private fun enqueueGuardedNetStateWrite(
        dtlIp: String,
        dtlLaneNo: Int,
        online: Boolean,
        seq: Long,
        resolveId: () -> NetStateId?,
    ) {
        dbWriteQueue.enqueue(
            GateDbWriteTask(
                partitionKey = dtlIp,
                operationName = "UpdateNetState($dtlIp,$dtlLaneNo,$online)",
            ) {
                val id = resolveId() ?: return@GateDbWriteTask
                val writeKey = dtlIp to dtlLaneNo
                // 클레임부터 실제 저장까지를 같은 [writeKey]에 대해 통째로 상호 배제한다(2차 적대적
                // 리뷰 지적) — 클레임만 원자적으로 하고 조회/저장은 락 밖에서 하면, 그 사이의 DB 지연
                // 동안 더 최신 실행이 끼어들어 먼저 끝낼 수 있고 이후 오래된 실행이 재개돼 최신
                // 값을 덮어쓰는 순서 역전 창이 남는다.
                val lock = lockFor(writeKey)
                lock.lock()
                try {
                    // 이미 더 최신(더 큰 seq) 쓰기가 적용된 뒤라면(예: 이 실행이 타임아웃 후
                    // 버려졌다가 뒤늦게 여기 도달한 경우) 쓰지 않고 건너뛴다. 같은 작업 자신의
                    // 재시도(seq 동일)는 정상적으로 다시 클레임된다.
                    if (!tryClaimNetStateSeq(writeKey, seq)) {
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
