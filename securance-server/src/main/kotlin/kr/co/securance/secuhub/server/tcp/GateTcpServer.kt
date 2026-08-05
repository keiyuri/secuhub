package kr.co.securance.secuhub.server.tcp

import io.netty.channel.ChannelOption
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactor.mono
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
 */
@Component
class GateTcpServer(
    private val config: ServerModeConfig,
    private val registry: GateConnectionRegistryImpl,
    private val gateDetailRepository: GateDetailRepository,
    private val codecRegistry: GateProtocolCodecRegistry,
    private val packetHandler: GatePacketHandler,
) {
    private val logger = LoggerFactory.getLogger(GateTcpServer::class.java)

    private var disposableServer: DisposableServer? = null
    private val actorDispatcher by lazy {
        Executors.newFixedThreadPool(config.actorDispatcherParallelism.coerceAtLeast(1)).asCoroutineDispatcher()
    }

    @PostConstruct
    fun start() {
        if (config.mode != GatewayMode.SERVER) {
            logger.info("securance.server.mode={} — GateTcpServer(SERVER 모드)는 기동하지 않습니다.", config.mode)
            return
        }

        disposableServer = TcpServer.create()
            .host(config.host)
            .port(config.port)
            .option(ChannelOption.SO_BACKLOG, config.acceptBacklog)
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
        logger.info("게이트 TCP 서버를 종료합니다.")
    }

    private fun onNewConnection(connection: Connection) {
        val remoteIp = remoteIpOf(connection)
        if (remoteIp == null) {
            logger.warn("원격 주소를 확인할 수 없어 연결을 닫습니다.")
            connection.dispose()
            return
        }
        connection.onDispose {
            CoroutineScope(Dispatchers.IO).launch { registry.closeConnection(remoteIp, updateNetState = true) }
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
                return@mono
            }

            val codec = try {
                codecRegistry.resolve(gateDetail.dtlType)
            } catch (ex: UnsupportedGateTypeException) {
                logger.warn("게이트[{}] 연결 거부: {}", remoteIp, ex.message)
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
            )
            registry.register(state)
            logger.info("게이트[{}] 연결 수락 (dtlType={})", remoteIp, gateDetail.dtlType)

            inbound.receive().asByteArray().asFlow().collect { chunk -> onChunkReceived(state, chunk) }
        }.then()
    }

    /** 인바운드 바이트 조각을 재조립기에 흘려보내고, 완성된 패킷마다 액터에 처리를 위임한다. */
    private fun onChunkReceived(state: GateConnectionState, chunk: ByteArray) {
        val packets = state.reassembler.append(chunk)
        for (raw in packets) {
            if (!state.codec.verifyChecksum(raw)) {
                logger.warn("커넥션[{}] 체크섬 불일치 패킷을 폐기합니다.", state.dtlIp)
                continue
            }
            val decoded = state.codec.decode(raw)
            state.actor.submit { packetHandler.handle(state, decoded) }
        }
    }

    private fun remoteIpOf(connection: Connection): String? =
        (connection.channel().remoteAddress() as? InetSocketAddress)?.address?.hostAddress
}
