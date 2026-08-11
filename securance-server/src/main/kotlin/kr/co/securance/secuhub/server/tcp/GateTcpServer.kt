package kr.co.securance.secuhub.server.tcp

import io.netty.channel.ChannelOption
import io.netty.handler.timeout.ReadTimeoutHandler
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kr.co.securance.secuhub.common.exception.UnsupportedGateTypeException
import kr.co.securance.secuhub.domain.repository.GateDetailRepository
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
import reactor.netty.DisposableServer
import reactor.netty.NettyInbound
import reactor.netty.NettyOutbound
import reactor.netty.tcp.TcpServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/**
 * SERVER 모드(backend ← gate) TCP 서버 — Reactor Netty 스켈레톤(계획서 3.1/7절).
 *
 * 레거시 `SpeedServer.StartTcpListener`/`AcceptCallback`에 대응한다. 연결이 들어오면
 * (1) 원격 IP로 `tb_gate_dtl`을 조회해 게이트 타입을 알아내고, (2) 그 타입에 맞는 프로토콜 코덱을
 * [GateProtocolCodecRegistry]에서 찾아, (3) 커넥션별 [GateConnectionActor]와 [GateConnectionState]를
 * 만들어 등록한 뒤, (4) 인바운드 바이트를 코덱의 프레임 재조립기에 흘려보내 완성된 패킷을
 * [GatePacketHandler]로 전달한다. 지원하지 않는 게이트 타입은 연결을 거부한다(침묵 실패 금지,
 * 계획서 3.4절).
 *
 * 인증은 소스 IP가 `tb_gate_dtl`에 등록돼 있는지 여부뿐이다(토큰/인증서 없음) — 의도적인 설계
 * 결정이며, 전제 조건과 배포 시 유의사항은 README의 "보안 전제 — 게이트 연결의 IP 기반 신뢰 모델" 참고.
 */
@Component
class GateTcpServer(
    private val config: ServerModeConfig,
    private val registry: GateConnectionRegistryImpl,
    private val gateDetailRepository: GateDetailRepository,
    private val codecRegistry: GateProtocolCodecRegistry,
    private val inboundProcessor: GateInboundPacketProcessor,
) {
    private val logger = LoggerFactory.getLogger(GateTcpServer::class.java)

    private var disposableServer: DisposableServer? = null

    /** 실제로 바인딩된 포트(테스트/모니터링용) — `config.port=0`으로 OS가 임의 포트를 고를 때 유용하다. */
    val boundPort: Int? get() = disposableServer?.port()

    private val actorExecutorLazy = lazy {
        Executors.newFixedThreadPool(config.actorDispatcherParallelism.coerceAtLeast(1))
    }
    private val actorExecutor by actorExecutorLazy
    private val actorDispatcher by lazy { actorExecutor.asCoroutineDispatcher() }

    /**
     * dispose 콜백 전용 코루틴 스코프(적대적 리뷰 지적) — 예전에는 커넥션이 끊길 때마다
     * `CoroutineScope(Dispatchers.IO)`를 새로 만들어, 아무도 소유/취소하지 않는 루트 스코프를 계속
     * 찍어냈다. 그 안에서 예외가 나면 기본 처리기로 흘러가 조용히 삼켜지고(registry/net_state 정리가
     * 부분적으로만 수행된 채 흔적도 안 남음), 애플리케이션 종료 시에도 이 스코프들을 취소할 방법이
     * 없었다. 서버가 소유하는 단일 SupervisorJob 스코프로 교체해 [stop]에서 명시적으로 취소한다.
     */
    private val disposeScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, ex ->
            logger.error("커넥션 dispose 정리 중 처리되지 않은 예외가 발생했습니다.", ex)
        },
    )

    @PostConstruct
    fun start() {
        // 2026-08-11: GateTcpClient(CLIENT 모드) 구현 완료 — 이제 mode에 따라 두 컴포넌트 중
        // 하나만 활성화되면 되므로, 예전처럼 CLIENT를 기동 실패로 막을 필요가 없다. CLIENT
        // 모드에서는 이 서버를 그냥 띄우지 않고 GateTcpClient가 아웃바운드 연결을 담당한다.
        // stop()의 registry 정리는 mode와 무관하게 계속 수행한다 — GateTcpClient가 만든
        // 커넥션도 같은 registry를 공유하기 때문이다.
        if (config.mode != GatewayMode.SERVER) {
            logger.info("securance.server.mode={} — SERVER 모드 리스너를 기동하지 않습니다(GateTcpClient가 담당).", config.mode)
            return
        }

        disposableServer = TcpServer.create()
            .host(config.host)
            .port(config.port)
            .option(ChannelOption.SO_BACKLOG, config.acceptBacklog)
            // SO_KEEPALIVE(적대적 리뷰 지적): OS TCP 스택이 유휴 소켓에 keepalive 프로브를 보내게 한다.
            // 기본 유휴시간이 보통 2시간이라 이것만으론 느리지만, 최소한의 백스톱으로 함께 켜둔다 —
            // 실질적인 감지는 아래 ReadTimeoutHandler(idleTimeoutSeconds)가 담당한다.
            .childOption(ChannelOption.SO_KEEPALIVE, true)
            .doOnConnection(::onNewConnection)
            .handle(::handleConnection)
            .bindNow()

        logger.info(
            "게이트 TCP 서버(SERVER 모드) 시작: {}:{} (backlog={})",
            config.host, config.port, config.acceptBacklog,
        )
    }

    @PreDestroy
    fun stop() {
        disposableServer?.disposeNow()

        // 등록된 커넥션 정리(적대적 리뷰 지적): 예전에는 서버 소켓만 닫고 registry에 남아있는
        // 커넥션(및 그 액터)은 그대로 방치했다 — 진행 중이던 액터 작업이 실행 도중 스레드풀이 죽어
        // 유실되고, registry에는 죽은 커넥션 엔트리가 계속 남았다. disposeNow()가 각 소켓의
        // onDispose(dispose 가드)를 비동기로 트리거하므로 그것과 별개로, 아직 registry에 남아있는
        // 항목을 여기서 직접 순회하며 정리한다(closeConnectionIfCurrent는 원자적 조회+제거라 dispose
        // 가드와 경합해도 중복 처리되지 않는다). updateNetState=false: 이 시점엔 DB 쓰기 큐도 함께
        // 내려가는 중일 수 있어, 굳이 새 쓰기를 큐잉하지 않는다(애플리케이션 종료이지 장애가 아니므로
        // net_state가 잠시 stale해도 다음 기동 시 첫 상태 패킷으로 다시 authoritative하게 갱신된다).
        runBlocking {
            registry.allConnections().forEach { state ->
                registry.closeConnectionIfCurrent(state.dtlIp, state, updateNetState = false)
            }
        }
        disposeScope.cancel()

        // actorExecutor는 지연 초기화이므로, 실제로 커넥션을 한 번이라도 처리해 생성된 경우에만 종료한다.
        if (actorExecutorLazy.isInitialized()) {
            actorExecutor.shutdown()
        }
        logger.info("게이트 TCP 서버를 종료합니다.")
    }

    private fun onNewConnection(connection: Connection) {
        val remoteIp = remoteIpOf(connection)
        if (remoteIp == null) {
            logger.warn("원격 주소를 확인할 수 없어 연결을 닫습니다.")
            connection.dispose()
            return
        }
        // 실제 dispose 콜백은 handleConnection에서 GateConnectionState가 만들어진 뒤,
        // 그 state 인스턴스를 직접 캡처해 등록한다(아래 registerDisposeGuard 참고) —
        // 여기서는 registry에 등록할 state가 아직 없어 IP만으로는 재연결 레이스를 구분할 수 없다.

        // 죽은 커넥션 탐지(적대적 리뷰 지적): idleTimeoutSeconds 동안 아무 것도 못 읽으면
        // ReadTimeoutException이 파이프라인에 던져진다. 그 예외가 inbound Flux까지 전파되어
        // handleConnection의 mono{} 체인이 에러로 끝나면서 소켓이 실제로 닫히고, dispose 가드가
        // registry/net_state를 정리한다 — 이게 없으면 half-open 커넥션(케이블 단절 등)이
        // isChannelActive=true인 채로 무기한 남아 NetCheckJob도 이를 절대 감지하지 못했다.
        connection.addHandlerLast(ReadTimeoutHandler(config.idleTimeoutSeconds, java.util.concurrent.TimeUnit.SECONDS))
    }

    /**
     * 소켓 종료 시 registry에서 정리한다. [NetCheckJob]과 동일한 재연결 레이스 가드를 적용한다:
     * registry에 등록된 커넥션이 이 소켓(state)과 동일한 경우에만 제거하고, 이미 새 소켓으로
     * 교체되었다면(레이스로 인한 지연 dispose) 최신 커넥션을 잘못 지우지 않도록 건너뛴다(계획서 3.3절).
     *
     * `findConnection(ip) === state` 확인 후 별도로 `closeConnection`을 호출하는 두 단계 방식은
     * 그 사이에 재연결이 끼어드는 TOCTOU 레이스가 있어 [GateConnectionRegistryImpl.closeConnectionIfCurrent]
     * (조회+제거 원자적 수행)로 대체했다.
     */
    private fun registerDisposeGuard(connection: Connection, state: GateConnectionState) {
        connection.onDispose {
            disposeScope.launch {
                registry.closeConnectionIfCurrent(state.dtlIp, state, updateNetState = true)
            }
        }
    }

    /** 신규 연결마다 호출된다: 게이트 조회 → 코덱 선택 → 커넥션 등록 → 인바운드 패킷 처리 루프. */
    private fun handleConnection(inbound: NettyInbound, outbound: NettyOutbound): Mono<Void> {
        var rawConnection: Connection? = null
        inbound.withConnection { rawConnection = it }
        val connection = rawConnection ?: return Mono.empty()
        val remoteIp = remoteIpOf(connection) ?: return Mono.empty()

        return mono {
            val gateDetail = withContext(Dispatchers.IO) {
                gateDetailRepository.findFirstByDtlIpAndUseYnTrueOrderByDtlLaneNo(remoteIp)
            }
            if (gateDetail == null) {
                logger.warn("등록되지 않은 게이트 IP[{}]의 연결을 거부합니다.", remoteIp)
                // 소켓을 닫지 않으면 미등록 IP가 연결을 계속 열어둔 채 방치되어(패킷 처리 루프만
                // 시작하지 않을 뿐) 파일 디스크립터가 무한정 쌓인다 — 반드시 명시적으로 닫는다.
                connection.dispose()
                return@mono
            }

            // 이 IP가 실어나르는 전체 레인의 loc_id/grp_id/dtl_id를 **연결 시 1회만** 조회해 캐시한다.
            // 패킷마다 재조회하면 고빈도 수신 경로에서 N+1 조회가 된다(레거시 M-8 성능 버그).
            val laneInfo = withContext(Dispatchers.IO) {
                gateDetailRepository.findLaneInfoByDtlIp(remoteIp)
            }

            val codec = try {
                codecRegistry.resolve(gateDetail.dtlType)
            } catch (ex: UnsupportedGateTypeException) {
                logger.warn("게이트[{}] 연결 거부: {}", remoteIp, ex.message)
                connection.dispose()
                return@mono
            }

            val actor = GateConnectionActor(remoteIp, actorDispatcher, config.actorQueueCapacity)
            val state = GateConnectionState(
                dtlIp = remoteIp,
                gateTypeCode = gateDetail.dtlType,
                codec = codec,
                connection = connection,
                outbound = outbound,
                actor = actor,
                laneInfo = laneInfo,
            )
            registry.register(state)
            registerDisposeGuard(connection, state)
            logger.info("게이트[{}] 연결 수락 (dtlType={}, 레인수={})", remoteIp, gateDetail.dtlType, laneInfo.size)

            inbound.receive().asByteArray().asFlow().collect { chunk -> onChunkReceived(state, chunk) }
        }.doOnError { ex ->
            logger.error("커넥션[{}] 인바운드 처리 중 처리되지 않은 예외로 소켓을 닫습니다.", remoteIp, ex)
        }.then()
    }

    /** 실제 처리는 [GateInboundPacketProcessor]로 위임한다(CLIENT 모드와 공유, 클래스 KDoc 참고). */
    private fun onChunkReceived(state: GateConnectionState, chunk: ByteArray) =
        inboundProcessor.onChunkReceived(state, chunk)

    private fun remoteIpOf(connection: Connection): String? =
        (connection.channel().remoteAddress() as? InetSocketAddress)?.address?.hostAddress
}
