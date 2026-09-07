package kr.co.securance.secuhub.server.connection

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.withContext
import kr.co.securance.secuhub.common.exception.GateTaskRejectedException
import kr.co.securance.secuhub.common.util.HexCodec
import kr.co.securance.secuhub.domain.entity.NetStateId
import kr.co.securance.secuhub.domain.repository.GateDetailRepository
import kr.co.securance.secuhub.domain.repository.NetStateRepository
import kr.co.securance.secuhub.server.config.ServerModeConfig
import kr.co.securance.secuhub.server.control.localServerId
import kr.co.securance.secuhub.server.db.GateDbWriteQueue
import kr.co.securance.secuhub.server.db.GateDbWriteTask
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono
import java.time.Duration
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeoutException

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
    // (실행 시점이 아니라!) 파티션키(dtlIp, dtlLaneNo)별 단조증가 시퀀스를 발급해두고, 실제 저장은
    // [NetStateRepository.upsertIfNewer]의 `WHERE applied_seq <= VALUES(applied_seq)` 조건부
    // UPSERT로 넘긴다.
    //
    // **코드 리뷰 지적 R-8(2026-08-20)**: 이전에는 이 순서 보장을 인메모리 시퀀스 맵
    // (`lastAppliedNetStateSeq`) + 인메모리 락(`netStateWriteLocks`, `ReentrantLock`)으로 구현했다.
    // "클레임 후 findById/save"를 락 없이 순서대로만 하면, 오래된 실행이 클레임에는 통과했지만 그
    // 뒤 findById/save가 DB 지연으로 느려지는 동안 더 최신 실행이 끼어들어 먼저 클레임+저장을 끝내고
    // 그 다음 오래된 실행이 재개돼 최신 값을 덮어쓰는 TOCTOU 윈도우가 있었다 — 그래서 클레임+조회+
    // 저장 전체를 (dtlIp, dtlLaneNo)별로 락으로 상호 배제했었다. 이 방식은 두 가지 근본적인 약점이
    // 있었다: (1) 락은 인스턴스 로컬이라 다중 인스턴스 배포에서는 애초에 순서 역전을 막지 못했고,
    // (2) 락 맵/시퀀스 맵에 완전한 TTL/크기 상한 evict을 넣을 수 없었다(사용 중인 락을 다른 스레드가
    // 임의로 제거하면, 그 사이 새로 들어온 호출이 computeIfAbsent로 별도의 새 Lock 인스턴스를 얻어
    // 같은 키에 대해 서로 다른 락 객체로 "동시에" 임계구역에 들어갈 수 있어 상호 배제 자체가 깨진다).
    //
    // V31 마이그레이션으로 `tb_net_state.applied_seq` 컬럼을 추가해 이 조건부 검증을 DB의 단일
    // 원자적 UPSERT 문장(`INSERT ... ON DUPLICATE KEY UPDATE ... IF(applied_seq <= ...)`) 안으로
    // 옮겼다 — 인메모리 상태가 전혀 없으므로 두 약점이 모두 해소된다: 다중 인스턴스에서도 DB 행
    // 자체가 진실의 원천이라 정확하고, evict을 걱정할 캐시도 없다. `netStateWriteSequence`(seq
    // 발급기)만 남기고, 시퀀스 맵과 락은 전부 제거한다.
    //
    // **코드 리뷰 지적(codex, P1, 2026-08-20)**: `AtomicLong(0)`으로 초기화하면 DB에는
    // `applied_seq`가 영구 보존되는데 이 카운터는 프로세스 재시작마다 0부터 다시 시작한다.
    // 재시작 직후 발급되는 seq(1, 2, 3, ...)는 재시작 전 이미 DB에 적용된 값보다 작으므로,
    // `upsertIfNewer`의 `applied_seq <= VALUES(applied_seq)` 조건에 걸려 재시작 후 한동안(과거
    // 최대 seq를 다시 따라잡을 때까지) 모든 온라인/오프라인 net_state 갱신이 조용히 거부된다.
    // 다중 인스턴스 배포에서도 인스턴스마다 카운터가 독립적이라 크기 비교가 이벤트 발생 순서와
    // 무관해져 같은 문제가 재발한다 — 벽시계 기반(currentTimeMillis) 시드 + 인스턴스 판별자로
    // 한 차례 완화를 시도했으나(이 주석의 이전 버전), 아래 최종 지적으로 그 완화안 자체가
    // 근본적으로 불충분함이 드러나 전역 DB 시퀀스로 교체했다.
    //
    // **Codex 적대적 리뷰 재지적(2026-08-20, [P1], 최종)**: 벽시계+판별자 조합은 "값 충돌"만
    // 줄일 뿐 "실제 발생 순서"는 보장하지 못한다 — 두 인스턴스가 같은 밀리초에 같은 레인의
    // 상태를 갱신하면, 나중에 발생한 이벤트가 우연히 더 작은 판별자를 뽑아 더 작은 seq를 받을
    // 수 있고, 그러면 `upsertIfNewer`가 그 최신 이벤트를 "더 오래된 쓰기"로 오판해 거부한다.
    // 인스턴스 로컬 카운터로는 인스턴스 간 순서를 원천적으로 표현할 수 없다는 뜻이므로, seq
    // 발급 자체를 DB로 옮겼다 — [NetStateRepository.nextSeq]가 MariaDB `SEQUENCE`(V32
    // 마이그레이션, `tb_net_state_seq`)에서 `NEXT VALUE FOR`로 전역 단조증가 값을 발급받는다.
    // `enqueueNetStateUpdate` 호출부는 GATE_STATUS 패킷마다가 아니라 온라인/오프라인 "전이"가
    // 있을 때만 호출되므로(적대적 리뷰 지적, DefaultGatePacketHandler.kt 참고) 매 호출마다
    // DB 왕복이 하나 늘어도 고빈도 패킷 처리 경로(Dispatchers.IO 기반 커넥션 코루틴)에 실질적인
    // 부담이 되지 않는다.

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

    private suspend fun finalizeClose(dtlIp: String, state: GateConnectionState, updateNetState: Boolean) {
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
    suspend fun enqueueNetStateUpdate(dtlIp: String, dtlLaneNo: Int, online: Boolean, rawPacket: ByteArray? = null) {
        // 실행 시점이 아니라 "이 이벤트가 실제로 발생한 순서"를 반영해야 하므로 큐에 넣기 전,
        // 즉 호출 시점에 시퀀스를 발급한다(GateDbWriteQueue의 타임아웃/버려진 실행 재시도로 인한
        // 순서 역전 방지 — 클래스 상단 주석 참고). DB가 발급하는 전역 시퀀스라 블로킹 JDBC 호출을
        // Dispatchers.IO로 옮긴다(GateTcpServer/GateTcpClient의 다른 DB 조회 호출과 동일한 패턴).
        val seq = withContext(Dispatchers.IO) { netStateRepository.nextSeq() }
        enqueueGuardedNetStateWrite(dtlIp, dtlLaneNo, online, seq, rawPacket) {
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
                NetStateWriteTarget(
                    id = NetStateId(
                        dtlIp = dtlIp,
                        dtlLaneNo = dtlLaneNo,
                        locId = requireNotNull(gateDetail.location.locId),
                        grpId = requireNotNull(gateDetail.group.grpId),
                    ),
                    dtlType = gateDetail.dtlType,
                    dtlId = gateDetail.dtlId,
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
    suspend fun enqueueNetStateUpdate(
        state: GateConnectionState,
        dtlLaneNo: Int,
        online: Boolean,
        rawPacket: ByteArray? = null,
    ) {
        val info = state.laneInfoOf(dtlLaneNo) ?: state.primaryLaneInfo
        if (info == null) {
            // 캐시가 비어 있는 커넥션(연결 수립 직후 등) — DB에서 직접 확인하는 경로로 넘긴다.
            enqueueNetStateUpdate(state.dtlIp, dtlLaneNo, online, rawPacket)
            return
        }
        val seq = withContext(Dispatchers.IO) { netStateRepository.nextSeq() }
        val id = NetStateId(dtlIp = state.dtlIp, dtlLaneNo = dtlLaneNo, locId = info.locId, grpId = info.grpId)
        val target = NetStateWriteTarget(id = id, dtlType = info.dtlType, dtlId = info.dtlId)
        enqueueGuardedNetStateWrite(state.dtlIp, dtlLaneNo, online, seq, rawPacket) { target }
    }

    /** [enqueueGuardedNetStateWrite]가 실제 UPSERT에 필요한 식별 정보를 한데 묶은 결과. */
    private data class NetStateWriteTarget(val id: NetStateId, val dtlType: Int, val dtlId: Long?)

    /**
     * `tb_net_state` 한 행의 갱신을 DB 쓰기 큐에 넣는다. [resolveTarget]은 실제 쓰기 직전(워커
     * 스레드)에 평가되며, null을 반환하면 갱신을 건너뛴다.
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
        rawPacket: ByteArray?,
        resolveTarget: () -> NetStateWriteTarget?,
    ) {
        dbWriteQueue.enqueue(
            GateDbWriteTask(
                partitionKey = dtlIp,
                operationName = "UpdateNetState($dtlIp,$dtlLaneNo,$online)",
            ) {
                val target = resolveTarget() ?: return@GateDbWriteTask
                val id = target.id
                // 순서 역전 방지는 이제 DB의 조건부 UPSERT(`applied_seq <= VALUES(applied_seq)`)가
                // 전담한다 — 인메모리 락/시퀀스 맵 없이도 원자적이다(R-8, NetStateRepository.upsertIfNewer
                // KDoc 참고). 이미 더 최신 seq가 적용된 뒤라면 이 UPSERT는 조용히 no-op이 된다.
                netStateRepository.upsertIfNewer(
                    dtlIp = id.dtlIp,
                    dtlLaneNo = id.dtlLaneNo,
                    locId = id.locId,
                    grpId = id.grpId,
                    dtlState = if (online) "Y" else "N",
                    // 2026-09-07 tb_net_state 재점검 — 예전에는 이 두 컬럼을 쿼리에 전혀 싣지 않아
                    // 신규 서버 경로가 만든 행은 영구히 NULL로 남았다. 호출부가 이미 캐시해 둔 값을
                    // 그대로 전달한다(NetStateRepository.upsertIfNewer KDoc "컬럼 누락 수정 2" 참고).
                    dtlType = target.dtlType,
                    dtlId = target.dtlId,
                    // 레거시 usp_net_check_data와 동일한 포맷(초 단위, yyyyMMddHHmmss)으로 통일한다
                    // (사용자 확인, 2026-09-07) — 예전엔 이 경로만 분 단위(yyyyMMddHHmm)를 써서 같은
                    // 컬럼에 어느 쓰기 경로가 마지막으로 갱신했는지에 따라 자릿수가 달라졌다.
                    checkTime = java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")),
                    seq = seq,
                    // `tb_net_state.server_ip`도 `tb_data_snd.snd_server`와 동일하게 VARCHAR(20)이다
                    // (2026-08-26 dev DB 실측 확인). 여기서 InetAddress.getLocalHost().hostAddress를
                    // 직접 다시 쓰면 IPv6 환경에서 길이 초과로 이 UPSERT 자체가 매번 실패하는(코드
                    // 리뷰로 발견, a3faf12가 snd_server에서 이미 겪은 것과 동일한 버그) 회귀가 생긴다
                    // — [GateControlService.localServerId]가 이미 IPv4 우선 탐지 + 20자 강제 절단을
                    // 처리해 두었으므로 새로 만들지 않고 그대로 재사용한다.
                    serverIp = localServerId,
                    // 2026-09-07 사용자 확인 — server_cd는 이 인스턴스의 연결 방향(SERVER/CLIENT)을
                    // 담는다. 코드베이스 전체에서 이 컬럼을 쓰는 곳이 전혀 없어 항상 NULL이었다.
                    serverCd = serverModeConfig.mode.name,
                    // 커넥션 종료로 인한 오프라인 전이처럼 관련 패킷이 없으면 null — 레거시와 달리
                    // 매 상태 확인마다가 아니라 온라인/오프라인 "전이" 시에만 기록하므로, 그 전이를
                    // 유발한 패킷이 있을 때만(주로 GATE_STATUS 패킷) 채운다.
                    sndRaw = rawPacket?.let { HexCodec.toHex(it) },
                )
            },
        )
    }
}
