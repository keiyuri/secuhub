package kr.co.securance.secuhub.server.tcp

import kr.co.securance.secuhub.domain.entity.GateDetail
import kr.co.securance.secuhub.domain.entity.GateGroup
import kr.co.securance.secuhub.domain.entity.GateLocation
import kr.co.securance.secuhub.domain.repository.GateDetailRepository
import kr.co.securance.secuhub.domain.repository.NetStateRepository
import kr.co.securance.secuhub.protocol.GatePacket
import kr.co.securance.secuhub.protocol.GateProtocolCodec
import kr.co.securance.secuhub.protocol.GateProtocolCodecRegistry
import kr.co.securance.secuhub.protocol.PacketReassembler
import kr.co.securance.secuhub.protocol.SpeedGateControlPayload
import kr.co.securance.secuhub.server.config.GatewayMode
import kr.co.securance.secuhub.server.config.ServerModeConfig
import kr.co.securance.secuhub.server.connection.GateConnectionRegistryImpl
import kr.co.securance.secuhub.server.db.GateDbWriteQueue
import kr.co.securance.secuhub.server.db.GatePacketPersister
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.data.domain.Pageable
import java.net.InetSocketAddress
import java.net.Socket
import java.time.LocalDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Reactor Netty 실제 소켓을 열어 [GateTcpServer]를 검증하는 통합 테스트.
 *
 * 이전까지 "Netty 통합이라 슬라이스 테스트 셋업이 더 필요"하다는 이유로 미뤄뒀지만,
 * 모든 협력 객체가 순수 생성자 주입(Spring 컨텍스트 불필요)이라 실제로는 `TcpServer`를
 * 임의 포트(`port=0`)에 바인딩하고 순수 [Socket] 클라이언트로 접속해 검증할 수 있다.
 */
class GateTcpServerTest {

    private object FakeCodec : GateProtocolCodec {
        override val supportedGateTypes: Set<Int> = setOf(1)
        override val defaultAddress: ByteArray = ByteArray(0)
    override fun verifyChecksum(packet: ByteArray): Boolean = true
        override fun decode(packet: ByteArray): GatePacket =
            GatePacket(0, 0, 0, 0, 0, 0, raw = packet)

        override fun buildStatusRequest(address: ByteArray, dateTime: LocalDateTime): ByteArray = ByteArray(0)
    override fun buildAck(objectCode: Byte, dateTime: LocalDateTime): ByteArray = ByteArray(0)
    override fun buildControlCommand(laneNo: Int, payload: SpeedGateControlPayload): ByteArray = ByteArray(0)

        // 테스트 편의상 한 번의 append 호출로 들어온 바이트를 그대로 패킷 1개로 취급한다.
        override fun newReassembler(): PacketReassembler = object : PacketReassembler {
            override fun append(chunk: ByteArray): List<ByteArray> =
                if (chunk.isEmpty()) emptyList() else listOf(chunk)
        }
    }

    private fun newServer(
        gateDetailRepository: GateDetailRepository,
        actorQueueCapacity: Int = 10,
        idleTimeoutSeconds: Long = 90,
        packetHandler: GatePacketHandler = GatePacketHandler { _, _ -> },
    ): GateTcpServer {
        val config = ServerModeConfig(
            mode = GatewayMode.SERVER, host = "127.0.0.1", port = 0,
            actorQueueCapacity = actorQueueCapacity, idleTimeoutSeconds = idleTimeoutSeconds,
        )
        val registry = GateConnectionRegistryImpl(
            mock(NetStateRepository::class.java),
            mock(GateDbWriteQueue::class.java),
            gateDetailRepository,
            ServerModeConfig(),
        )
        return GateTcpServer(
            config = config,
            registry = registry,
            gateDetailRepository = gateDetailRepository,
            codecRegistry = GateProtocolCodecRegistry(listOf(FakeCodec)),
            inboundProcessor = GateInboundPacketProcessor(packetHandler, mock(GatePacketPersister::class.java)),
        )
    }

    private var server: GateTcpServer? = null

    @AfterTest
    fun tearDown() {
        server?.stop()
    }

    /** 클라이언트 소켓이 서버 쪽에서 닫힐 때까지(EOF) 기다린다. 열린 채로 남으면 타임아웃 실패한다. */
    private fun Socket.assertClosedByServer(timeoutSeconds: Long = 5) {
        soTimeout = (timeoutSeconds * 1000).toInt()
        val eof = getInputStream().read() // 서버가 dispose()하면 -1(EOF)이 온다.
        assertEquals(-1, eof, "서버가 연결을 닫지 않았습니다(자원 누수 회귀).")
    }

    @Test
    fun `tb_gate_dtl에 없는 IP의 연결은 즉시 닫힌다`() {
        // 회귀 방지 테스트: 예전에는 gateDetail==null이어도 connection.dispose()를 호출하지 않아
        // 미등록 IP의 소켓이 무한정 열린 채로 방치됐다(파일 디스크립터 누수).
        val gateDetailRepository = mock(GateDetailRepository::class.java)
        `when`(gateDetailRepository.findFirstByDtlIpAndUseYnTrueOrderByDtlLaneNo(eqOf("127.0.0.1"), anyPageable()))
            .thenReturn(emptyList())

        val server = newServer(gateDetailRepository).also { this.server = it; it.start() }
        val port = requireNotNull(server.boundPort)

        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 2000)
            socket.assertClosedByServer()
        }
    }

    @Test
    fun `지원하지 않는 게이트 타입의 연결은 즉시 닫힌다`() {
        val location = GateLocation(locId = 1L, locName = "loc")
        val group = GateGroup(grpId = 1L, location = location, grpName = "grp", gateTypeCode = 3)
        val gateDetail = GateDetail(
            dtlId = 1L, location = location, group = group,
            dtlIp = "127.0.0.1", dtlLaneNo = 1, dtlType = 3, // FakeCodec은 1만 지원 — 3은 미지원.
        )
        val gateDetailRepository = mock(GateDetailRepository::class.java)
        `when`(gateDetailRepository.findFirstByDtlIpAndUseYnTrueOrderByDtlLaneNo(eqOf("127.0.0.1"), anyPageable()))
            .thenReturn(listOf(gateDetail))

        val server = newServer(gateDetailRepository).also { this.server = it; it.start() }
        val port = requireNotNull(server.boundPort)

        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 2000)
            socket.assertClosedByServer()
        }
    }

    @Test
    fun `등록된 게이트가 패킷을 보내면 핸들러가 호출된다`() {
        val location = GateLocation(locId = 1L, locName = "loc")
        val group = GateGroup(grpId = 1L, location = location, grpName = "grp", gateTypeCode = 1)
        val gateDetail = GateDetail(
            dtlId = 1L, location = location, group = group,
            dtlIp = "127.0.0.1", dtlLaneNo = 1, dtlType = 1,
        )
        val gateDetailRepository = mock(GateDetailRepository::class.java)
        `when`(gateDetailRepository.findFirstByDtlIpAndUseYnTrueOrderByDtlLaneNo(eqOf("127.0.0.1"), anyPageable()))
            .thenReturn(listOf(gateDetail))

        val handled = CountDownLatch(1)
        var receivedBytes: ByteArray? = null
        val server = newServer(gateDetailRepository) { _, packet ->
            receivedBytes = packet.raw
            handled.countDown()
        }.also { this.server = it; it.start() }
        val port = requireNotNull(server.boundPort)

        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 2000)
            socket.getOutputStream().write(byteArrayOf(0x01, 0x02, 0x03))
            socket.getOutputStream().flush()

            assertTrue(handled.await(5, TimeUnit.SECONDS), "패킷 핸들러가 호출되지 않았습니다.")
            assertEquals(listOf<Byte>(0x01, 0x02, 0x03), receivedBytes?.toList())

            // 등록된 게이트의 연결은 서버가 임의로 닫지 않는다 — 여전히 열려 있어야 한다
            // (열려 있으면 read()가 데이터도 EOF도 못 받고 타임아웃돼야 정상).
            socket.soTimeout = 300
            assertFailsWith<java.net.SocketTimeoutException>("서버가 등록된 게이트 연결을 예상치 못하게 닫았습니다.") {
                socket.getInputStream().read()
            }
        }
    }

    @Test
    fun `액터 대기열이 가득 차 패킷을 드롭해도 연결은 끊지 않는다`() {
        // 적대적 리뷰 지적: 예전에는 GateTaskRejectedException(큐 포화)이 onChunkReceived 밖으로
        // 전파되어 mono{} 전체가 에러로 끝나 소켓이 통째로 닫혔다 — 가장 트래픽이 많은(=큐가 자주
        // 차는) 게이트일수록 재접속 폭풍에 빠지는 구조적 결함이었다. 이제는 큐 포화가 나면 그
        // 패킷만 드롭하고 연결은 유지되어야 한다.
        val location = GateLocation(locId = 1L, locName = "loc")
        val group = GateGroup(grpId = 1L, location = location, grpName = "grp", gateTypeCode = 1)
        val gateDetail = GateDetail(
            dtlId = 1L, location = location, group = group,
            dtlIp = "127.0.0.1", dtlLaneNo = 1, dtlType = 1,
        )
        val gateDetailRepository = mock(GateDetailRepository::class.java)
        `when`(gateDetailRepository.findFirstByDtlIpAndUseYnTrueOrderByDtlLaneNo(eqOf("127.0.0.1"), anyPageable()))
            .thenReturn(listOf(gateDetail))

        val firstHandlerStarted = CountDownLatch(1)
        val releaseFirstHandler = CountDownLatch(1)
        val lastHandled = CountDownLatch(1)
        var handledCount = 0

        // 큐 용량 1: 첫 패킷이 액터 스레드를 붙잡고 있는 동안 그 다음 여러 패킷을 연달아 보내면
        // 채널(capacity=1)에 하나만 들어가고 나머지는 즉시 GateTaskRejectedException으로 거부된다.
        val server = newServer(gateDetailRepository, actorQueueCapacity = 1) { _, packet ->
            handledCount++
            // TCP는 write() 호출 경계를 보존하지 않으므로(Nagle 등으로 인접 write가 한 청크로 합쳐질 수
            // 있음), 정확히 일치하는 배열 대신 마커 바이트의 포함 여부로 판별해 테스트를 안정화한다.
            if (packet.raw.contains(0x01.toByte())) {
                firstHandlerStarted.countDown()
                releaseFirstHandler.await(5, TimeUnit.SECONDS)
            }
            if (packet.raw.contains(0x09.toByte())) {
                lastHandled.countDown()
            }
        }.also { this.server = it; it.start() }
        val port = requireNotNull(server.boundPort)

        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 2000)
            val out = socket.getOutputStream()

            out.write(byteArrayOf(0x01)) // 액터를 붙잡는 첫 패킷
            out.flush()
            assertTrue(firstHandlerStarted.await(2, TimeUnit.SECONDS))

            // 큐가 가득 찬 상태에서 여러 패킷을 더 보낸다 — 전부(혹은 대부분) 드롭되어야 정상이며,
            // 여기서 예외가 밖으로 새 나가 소켓이 닫히면 안 된다.
            repeat(5) { i ->
                out.write(byteArrayOf((0x02 + i).toByte()))
                out.flush()
            }

            releaseFirstHandler.countDown() // 액터를 풀어준다.
            Thread.sleep(200) // 풀린 액터가 큐에 남아있던 항목(있다면)을 다 소비할 시간을 준다.

            out.write(byteArrayOf(0x09)) // 큐가 비워진 뒤의 정상 패킷 — 처리돼야 한다.
            out.flush()
            assertTrue(lastHandled.await(5, TimeUnit.SECONDS), "큐 포화 이후 연결이 살아있지 않습니다.")

            // 여전히 연결이 열려 있어야 한다(서버가 임의로 닫지 않음).
            socket.soTimeout = 300
            assertFailsWith<java.net.SocketTimeoutException>("서버가 큐 포화 이후 연결을 예상치 못하게 닫았습니다.") {
                socket.getInputStream().read()
            }
        }
    }

    @Test
    fun `idleTimeoutSeconds 동안 아무 데이터도 없으면 연결을 닫는다`() {
        // 적대적 리뷰 지적: 예전에는 keepalive/read timeout이 전혀 없어 half-open 커넥션(케이블
        // 단절 등)이 isChannelActive=true인 채로 무기한 남았다 — NetCheckJob도 이를 감지할 방법이
        // 없었다. ReadTimeoutHandler 도입 이후에는 idle 시간이 지나면 서버가 스스로 소켓을 닫아야 한다.
        val location = GateLocation(locId = 1L, locName = "loc")
        val group = GateGroup(grpId = 1L, location = location, grpName = "grp", gateTypeCode = 1)
        val gateDetail = GateDetail(
            dtlId = 1L, location = location, group = group,
            dtlIp = "127.0.0.1", dtlLaneNo = 1, dtlType = 1,
        )
        val gateDetailRepository = mock(GateDetailRepository::class.java)
        `when`(gateDetailRepository.findFirstByDtlIpAndUseYnTrueOrderByDtlLaneNo(eqOf("127.0.0.1"), anyPageable()))
            .thenReturn(listOf(gateDetail))

        val server = newServer(gateDetailRepository, idleTimeoutSeconds = 1)
            .also { this.server = it; it.start() }
        val port = requireNotNull(server.boundPort)

        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 2000)
            // 아무 데이터도 보내지 않고 idleTimeoutSeconds(1초)를 넘길 때까지 기다린다.
            socket.assertClosedByServer(timeoutSeconds = 5)
        }
    }

    @Test
    fun `서버 종료 시 등록된 커넥션을 모두 닫는다`() {
        // 적대적 리뷰 지적 회귀 테스트: 예전에는 stop()이 서버 소켓만 닫고 이미 등록된 커넥션은
        // 방치했다 — 클라이언트 입장에서는 소켓이 계속 열려 있는 것처럼 보였다.
        val location = GateLocation(locId = 1L, locName = "loc")
        val group = GateGroup(grpId = 1L, location = location, grpName = "grp", gateTypeCode = 1)
        val gateDetail = GateDetail(
            dtlId = 1L, location = location, group = group,
            dtlIp = "127.0.0.1", dtlLaneNo = 1, dtlType = 1,
        )
        val gateDetailRepository = mock(GateDetailRepository::class.java)
        `when`(gateDetailRepository.findFirstByDtlIpAndUseYnTrueOrderByDtlLaneNo(eqOf("127.0.0.1"), anyPageable()))
            .thenReturn(listOf(gateDetail))

        val handled = CountDownLatch(1)
        val server = newServer(gateDetailRepository) { _, _ -> handled.countDown() }
            .also { this.server = it; it.start() }
        val port = requireNotNull(server.boundPort)

        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 2000)
            socket.getOutputStream().write(byteArrayOf(0x01))
            socket.getOutputStream().flush()
            assertTrue(handled.await(2, TimeUnit.SECONDS), "커넥션 등록이 완료되지 않았습니다.")

            server.stop()

            socket.assertClosedByServer()
        }
    }
}

// Mockito의 Java any(Class)는 null을 반환하는데, Kotlin에서 선언된 파라미터 타입(Pageable, 비-null)에
// 그대로 대입되면 즉시 null 체크 예외("any(...) must not be null")를 던진다 — 이 프로젝트는
// mockito-kotlin을 쓰지 않으므로 제네릭 소거를 이용한 표준 우회법을 쓴다(AccessReportControllerTest의
// anyOf()와 동일한 패턴).
private fun <T> anyOf(): T {
    Mockito.any<T>()
    @Suppress("UNCHECKED_CAST")
    return null as T
}
private fun anyPageable(): Pageable = anyOf()

// eq(value)도 Mockito 내부적으로는 null을 반환한다(매처를 기록만 하고 실제 스텁 값은 실인자를 그대로
// 씀) — Pageable 매처와 섞어 쓰려면 String 인자도 매처로 감싸야 하는데, ArgumentMatchers.eq는
// 구체 타입(String) 반환이라 위와 동일한 CHECKCAST/null-체크 문제가 난다. 제네릭 T로 우회한다.
private fun <T> eqOf(value: T): T {
    Mockito.eq(value)
    @Suppress("UNCHECKED_CAST")
    return null as T
}
