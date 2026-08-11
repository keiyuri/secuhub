package kr.co.securance.secuhub.server.tcp

import io.netty.channel.ChannelOption
import io.netty.handler.timeout.ReadTimeoutHandler
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.withContext
import kr.co.securance.secuhub.common.exception.UnsupportedGateTypeException
import kr.co.securance.secuhub.domain.entity.GateDetail
import kr.co.securance.secuhub.domain.repository.GateDetailRepository
import kr.co.securance.secuhub.protocol.GateProtocolCodec
import kr.co.securance.secuhub.protocol.GateProtocolCodecRegistry
import kr.co.securance.secuhub.server.config.GatewayMode
import kr.co.securance.secuhub.server.config.ServerModeConfig
import kr.co.securance.secuhub.server.connection.GateConnectionActor
import kr.co.securance.secuhub.server.connection.GateConnectionRegistryImpl
import kr.co.securance.secuhub.server.connection.GateConnectionState
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono
import reactor.netty.Connection
import reactor.netty.NettyInbound
import reactor.netty.NettyOutbound
import reactor.netty.tcp.TcpClient
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * CLIENT 모드(backend → gate) TCP 아웃바운드 연결 — 레거시 `SpeedServer.ClientModeMonitor`/
 * `ConnectToAllDevices`/`ConnectToDevice`(`SpeedServer.cs:1388/1431/1495`, 약 290줄)에 대응한다.
 *
 * 2026-08-11: 서버 전환 계획서 D1 — 현장에 백엔드가 먼저 접속해야 하는 게이트가 있다고 확인되어
 * 신규 구현했다(계획서 3.1절에 이미 설계는 있었으나 구현이 없었다).
 *
 * ### SERVER 모드([GateTcpServer])와의 관계
 * 연결을 여는 방향만 다를 뿐, 커넥션 등록/패킷 처리/종료 정리는 [GateConnectionState]·
 * [GateConnectionRegistryImpl]·[GateInboundPacketProcessor]를 그대로 공유한다(계획서 3.1절 —
 * "연결 방향과 패킷 처리는 독립적인 축"). `@ConditionalOnProperty` 대신 [start]에서
 * `config.mode`로 직접 분기하는 이유는 [GateTcpServer]와 대칭을 맞추고, 두 컴포넌트가 항상 함께
 * Spring 컨텍스트에 등록되어(둘 다 존재하되 하나만 활성 동작) 모드 전환 시 재배포만으로 즉시
 * 반영되게 하기 위함이다.
 *
 * ### 레거시와의 의도적 차이
 * - 레거시는 `Parallel.ForEach`(스레드 풀, 동시성 10)로 병렬 연결한다. 여기서는 코루틴
 *   `launch`(IO 디스패처)로 동일한 효과를 낸다 — 스레드를 블로킹하지 않아 대상이 많을 때 더
 *   가볍다.
 * - 레거시는 IP로 미리 그룹핑해 같은 IP의 여러 레인이 중복 연결을 시도하지 않게 막는다
 *   (Opus 리뷰 수정 이력, `SpeedServer.cs:1444` 주석). 여기서도 동일하게 `dtlIp`로 그룹핑한 뒤
 *   레인 목록 전체를 [GateConnectionState.laneInfo]에 담는다.
 * - 레거시는 연결 직후 상태 요청 패킷을 동기 전송한다. 여기서도 동일하게 즉시 전송하되, 실패해도
 *   연결 자체는 유지한다 — `ReqStatusJob`이 주기적으로도 요청하므로 치명적이지 않다.
 */
@Component
class GateTcpClient(
    private val config: ServerModeConfig,
    private val registry: GateConnectionRegistryImpl,
    private val gateDetailRepository: GateDetailRepository,
    private val codecRegistry: GateProtocolCodecRegistry,
    private val inboundProcessor: GateInboundPacketProcessor,
) {
    private val logger = LoggerFactory.getLogger(GateTcpClient::class.java)

    private val actorExecutorLazy = lazy {
        Executors.newFixedThreadPool(config.actorDispatcherParallelism.coerceAtLeast(1))
    }
    private val actorExecutor by actorExecutorLazy
    private val actorDispatcher by lazy { actorExecutor.asCoroutineDispatcher() }

    /** 재연결 모니터 루프 + 커넥션 dispose 콜백을 함께 소유하는 스코프([GateTcpServer]의 disposeScope와 동일 취지). */
    private val clientScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, ex ->
            logger.error("[CLIENT 모드] 처리되지 않은 예외가 발생했습니다.", ex)
        },
    )
    private var monitorJob: Job? = null

    @PostConstruct
    fun start() {
        if (config.mode != GatewayMode.CLIENT) {
            logger.info("securance.server.mode={} — CLIENT 모드 연결 모니터를 기동하지 않습니다(GateTcpServer가 담당).", config.mode)
            return
        }

        logger.info(
            "게이트 CLIENT 모드 연결 모니터 시작: 대상 포트={}, 재확인 주기={}초",
            config.clientPort, config.clientReconnectIntervalSeconds,
        )
        monitorJob = clientScope.launch {
            while (isActive) {
                try {
                    connectToAllDevices()
                } catch (ex: Exception) {
                    logger.error("[CLIENT 모드] 디바이스 연결 사이클 중 오류가 발생했습니다.", ex)
                }
                delay(config.clientReconnectIntervalSeconds.coerceAtLeast(1) * 1000)
            }
        }
    }

    @PreDestroy
    fun stop() {
        // registry에 등록된 실제 커넥션 정리는 GateTcpServer.stop()이 mode와 무관하게 수행한다
        // (이 클라이언트가 만든 커넥션도 같은 registry를 공유하므로 중복 정리할 필요가 없다).
        monitorJob?.cancel()
        clientScope.cancel()
        if (actorExecutorLazy.isInitialized()) {
            actorExecutor.shutdown()
        }
        logger.info("게이트 CLIENT 모드 연결 모니터를 종료합니다.")
    }

    /**
     * `tb_gate_dtl`에서 연결 대상 전체를 조회해, 아직 등록되지 않은 IP에 대해서만 연결을 시도한다.
     * 레거시 `ConnectToAllDevices`에 대응.
     */
    private suspend fun connectToAllDevices() {
        val devices = withContext(Dispatchers.IO) {
            gateDetailRepository.findByUseYnTrueAndAnalysisYnTrueOrderByDtlIp()
        }
        if (devices.isEmpty()) {
            logger.debug("[CLIENT 모드] 연결 대상 디바이스가 없습니다(tb_gate_dtl 조회 결과 없음).")
            return
        }

        // IP로 먼저 그룹핑 — 같은 IP의 여러 레인이 중복 연결을 시도하지 않도록(클래스 KDoc 참고).
        val targets = devices.groupBy { it.dtlIp.trim() }
            .filterKeys { ip -> registry.findConnection(ip) == null }
        if (targets.isEmpty()) return

        targets.forEach { (deviceIp, laneRows) ->
            clientScope.launch { connectToDevice(deviceIp, laneRows) }
        }
    }

    /** 단일 디바이스에 아웃바운드 연결을 시도한다. 레거시 `ConnectToDevice`에 대응. */
    private fun connectToDevice(deviceIp: String, laneRows: List<GateDetail>) {
        val representative = laneRows.first()
        val codec = try {
            codecRegistry.resolve(representative.dtlType)
        } catch (ex: UnsupportedGateTypeException) {
            logger.warn("[CLIENT 모드] 게이트[{}] 연결 건너뜀: {}", deviceIp, ex.message)
            return
        }

        TcpClient.create()
            .host(deviceIp)
            .port(config.clientPort)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS)
            // ReadTimeoutHandler는 SERVER 모드와 동일하게 애플리케이션 레벨에서 half-open 커넥션을
            // 감지한다(GateTcpServer.onNewConnection 참고) — SO_KEEPALIVE만으로는 OS 기본 유휴시간이
            // 너무 길다.
            .doOnConnected { conn -> conn.addHandlerLast(ReadTimeoutHandler(config.idleTimeoutSeconds, TimeUnit.SECONDS)) }
            .handle { inbound, outbound -> onConnected(deviceIp, representative, codec, inbound, outbound) }
            .connect()
            .subscribe(
                { },
                { ex -> logger.warn("[CLIENT 모드] 디바이스 연결 실패: {}:{} - {}", deviceIp, config.clientPort, ex.toString()) },
            )
    }

    /**
     * 연결 성공 시 호출된다 — 커넥션 등록 → 상태 요청 전송 → 인바운드 처리 루프. [GateTcpServer.handleConnection]과
     * 동일한 mono{} 패턴을 쓴다.
     */
    private fun onConnected(
        deviceIp: String,
        representative: GateDetail,
        codec: GateProtocolCodec,
        inbound: NettyInbound,
        outbound: NettyOutbound,
    ): Mono<Void> {
        var rawConnection: Connection? = null
        inbound.withConnection { rawConnection = it }
        val connection = rawConnection ?: return Mono.empty()

        return mono {
            // 재확인 주기 사이 레이스로 이미 다른 시도가 먼저 등록됐다면(예: 이전 사이클의 연결
            // 시도가 이번 사이클보다 늦게 완료) 방금 연 소켓을 버린다 — 레거시
            // `ConnectedClients.TryAdd` 실패("중복 연결 키") 처리와 동일 취지.
            if (registry.findConnection(deviceIp) != null) {
                logger.warn("[CLIENT 모드] 연결 경쟁 감지 — 방금 연 소켓을 닫습니다: {}", deviceIp)
                connection.dispose()
                return@mono
            }

            // SERVER 모드와 동일하게, analysis_yn 무관 전체 레인 목록을 연결 시 1회만 조회해
            // 캐시한다 — analysis_yn='N' 레인의 ACK/실패 기록 누락을 막기 위함(레거시 Codex 리뷰
            // 수정 이력, `SpeedServer.cs:1556` 주석과 동일한 이유).
            val laneInfo = withContext(Dispatchers.IO) { gateDetailRepository.findLaneInfoByDtlIp(deviceIp) }

            val actor = GateConnectionActor(deviceIp, actorDispatcher, config.actorQueueCapacity)
            val state = GateConnectionState(
                dtlIp = deviceIp,
                gateTypeCode = representative.dtlType,
                codec = codec,
                connection = connection,
                outbound = outbound,
                actor = actor,
                laneInfo = laneInfo,
            )
            registry.register(state)
            connection.onDispose {
                clientScope.launch { registry.closeConnectionIfCurrent(deviceIp, state, updateNetState = true) }
            }
            logger.info(
                "[CLIENT 모드] 디바이스 연결 성공: {}:{} (dtlType={}, 레인수={})",
                deviceIp, config.clientPort, representative.dtlType, laneInfo.size,
            )

            // 연결 직후 상태 요청 패킷을 즉시 보낸다(레거시와 동일 취지) — 실패해도 ReqStatusJob이
            // 주기적으로 재시도하므로 연결 자체는 유지한다.
            if (!registry.sendToConnection(deviceIp, codec.buildStatusRequest())) {
                logger.warn("[CLIENT 모드] 연결 직후 상태 요청 전송 실패: {}", deviceIp)
            }

            inbound.receive().asByteArray().asFlow().collect { chunk -> inboundProcessor.onChunkReceived(state, chunk) }
        }.doOnError { ex ->
            logger.error("[CLIENT 모드] 커넥션[{}] 인바운드 처리 중 처리되지 않은 예외로 소켓을 닫습니다.", deviceIp, ex)
        }.then()
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 3000
    }
}
