package kr.co.securance.secuhub.server.tcp

import kr.co.securance.secuhub.domain.entity.GateDetail
import kr.co.securance.secuhub.domain.entity.GateGroup
import kr.co.securance.secuhub.domain.entity.GateLocation
import kr.co.securance.secuhub.domain.repository.GateDetailRepository
import kr.co.securance.secuhub.domain.repository.GateLaneInfo
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
import java.net.ServerSocket
import java.net.Socket
import java.time.LocalDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [GateTcpClient](CLIENT 모드 아웃바운드 연결) 검증 — 코드 리뷰 지적 T-1(2026-08-20): 이 클래스는
 * 도입 당시(2026-08-11, D1) 테스트가 전무했다. [GateTcpServerTest]와 동일하게, 모든 협력 객체가
 * 순수 생성자 주입(Spring 컨텍스트 불필요)이라 실제 [ServerSocket]으로 "게이트 장비" 역할을 하는
 * accept 측을 열어 실제 TCP 라운드트립으로 검증한다.
 */
class GateTcpClientTest {

    private object FakeCodec : GateProtocolCodec {
        override val supportedGateTypes: Set<Int> = setOf(1)
        override val defaultAddress: ByteArray = ByteArray(0)
        override fun verifyChecksum(packet: ByteArray): Boolean = true
        override fun decode(packet: ByteArray): GatePacket = GatePacket(0, 0, 0, 0, 0, 0, raw = packet)
        override fun buildStatusRequest(address: ByteArray, dateTime: LocalDateTime): ByteArray = byteArrayOf(0x53, 0x52)
        override fun buildAck(objectCode: Byte, dateTime: LocalDateTime): ByteArray = ByteArray(0)
        override fun buildControlCommand(laneNo: Int, payload: SpeedGateControlPayload): ByteArray = ByteArray(0)

        // 테스트 편의상 한 번의 append 호출로 들어온 바이트를 그대로 패킷 1개로 취급한다(GateTcpServerTest와 동일).
        override fun newReassembler(): PacketReassembler = object : PacketReassembler {
            override fun append(chunk: ByteArray): List<ByteArray> =
                if (chunk.isEmpty()) emptyList() else listOf(chunk)
        }
    }

    private var client: GateTcpClient? = null
    private var fakeGateServer: ServerSocket? = null

    @AfterTest
    fun tearDown() {
        client?.stop()
        runCatching { fakeGateServer?.close() }
    }

    private fun gateDetail(dtlIp: String, dtlLaneNo: Int, dtlType: Int = 1) = GateDetail(
        dtlId = dtlLaneNo.toLong(),
        location = GateLocation(locId = 1L, locName = "loc"),
        group = GateGroup(grpId = 1L, location = GateLocation(locId = 1L, locName = "loc"), grpName = "grp", gateTypeCode = 1),
        dtlIp = dtlIp,
        dtlLaneNo = dtlLaneNo,
        dtlType = dtlType,
    )

    private fun newRegistry(gateDetailRepository: GateDetailRepository): GateConnectionRegistryImpl =
        GateConnectionRegistryImpl(
            mock(NetStateRepository::class.java),
            mock(GateDbWriteQueue::class.java),
            gateDetailRepository,
            ServerModeConfig(),
        )

    private fun newClient(
        gateDetailRepository: GateDetailRepository,
        clientPort: Int,
        registry: GateConnectionRegistryImpl = newRegistry(gateDetailRepository),
        reconnectIntervalSeconds: Long = 1,
        packetHandler: GatePacketHandler = GatePacketHandler { _, _ -> },
    ): GateTcpClient {
        val config = ServerModeConfig(
            mode = GatewayMode.CLIENT,
            clientPort = clientPort,
            clientReconnectIntervalSeconds = reconnectIntervalSeconds,
            actorQueueCapacity = 10,
        )
        return GateTcpClient(
            config = config,
            registry = registry,
            gateDetailRepository = gateDetailRepository,
            codecRegistry = GateProtocolCodecRegistry(listOf(FakeCodec)),
            inboundProcessor = GateInboundPacketProcessor(packetHandler, mock(GatePacketPersister::class.java)),
        ).also { this.client = it }
    }

    @Test
    fun `CLIENT 모드가 아니면 연결을 시도하지 않는다`() {
        val gateDetailRepository = mock(GateDetailRepository::class.java)
        val config = ServerModeConfig(mode = GatewayMode.SERVER, clientPort = 1)
        val gateTcpClient = GateTcpClient(
            config = config,
            registry = newRegistry(gateDetailRepository),
            gateDetailRepository = gateDetailRepository,
            codecRegistry = GateProtocolCodecRegistry(listOf(FakeCodec)),
            inboundProcessor = GateInboundPacketProcessor(GatePacketHandler { _, _ -> }, mock(GatePacketPersister::class.java)),
        ).also { client = it }

        gateTcpClient.start()
        Thread.sleep(300)

        Mockito.verify(gateDetailRepository, Mockito.never()).findByUseYnTrueOrderByDtlIp()
    }

    @Test
    fun `CLIENT 모드에서 대상 디바이스에 연결해 registry에 등록한다`() {
        val fakeGate = ServerSocket(0).also { fakeGateServer = it }
        val accepted = CountDownLatch(1)
        var acceptedSocket: Socket? = null
        Thread {
            runCatching { acceptedSocket = fakeGate.accept() }
            accepted.countDown()
        }.apply { isDaemon = true; start() }

        val gateDetailRepository = mock(GateDetailRepository::class.java)
        `when`(gateDetailRepository.findByUseYnTrueOrderByDtlIp())
            .thenReturn(listOf(gateDetail("127.0.0.1", 1)))
        `when`(gateDetailRepository.findLaneInfoByDtlIp(eqOf("127.0.0.1"))).thenReturn(
            listOf(GateLaneInfo(locId = 1L, grpId = 1L, dtlId = 1L, dtlLaneNo = 1, dtlType = 1, analysisYn = true)),
        )

        val registry = newRegistry(gateDetailRepository)
        val gateTcpClient = newClient(gateDetailRepository, fakeGate.localPort, registry = registry)
        gateTcpClient.start()

        assertTrue(accepted.await(5, TimeUnit.SECONDS), "CLIENT가 게이트(fake 서버)에 연결을 시도하지 않았습니다.")
        assertTrue(waitUntil { registry.findConnection("127.0.0.1") != null }, "커넥션이 registry에 등록되지 않았습니다.")
        acceptedSocket?.close()
    }

    @Test
    fun `연결 직후 상태 요청 패킷을 즉시 전송한다`() {
        val fakeGate = ServerSocket(0).also { fakeGateServer = it }
        var acceptedSocket: Socket? = null
        val accepted = CountDownLatch(1)
        Thread {
            runCatching { acceptedSocket = fakeGate.accept() }
            accepted.countDown()
        }.apply { isDaemon = true; start() }

        val gateDetailRepository = mock(GateDetailRepository::class.java)
        `when`(gateDetailRepository.findByUseYnTrueOrderByDtlIp())
            .thenReturn(listOf(gateDetail("127.0.0.1", 1)))
        `when`(gateDetailRepository.findLaneInfoByDtlIp(eqOf("127.0.0.1"))).thenReturn(
            listOf(GateLaneInfo(locId = 1L, grpId = 1L, dtlId = 1L, dtlLaneNo = 1, dtlType = 1, analysisYn = true)),
        )

        newClient(gateDetailRepository, fakeGate.localPort).start()

        assertTrue(accepted.await(5, TimeUnit.SECONDS))
        val socket = requireNotNull(acceptedSocket)
        socket.soTimeout = 5000
        val buf = ByteArray(2)
        val read = socket.getInputStream().read(buf)
        assertEquals(2, read)
        assertEquals(listOf(0x53.toByte(), 0x52.toByte()), buf.toList(), "FakeCodec.buildStatusRequest()의 바이트와 달라야 할 이유가 없습니다.")
        socket.close()
    }

    @Test
    fun `게이트가 보낸 패킷은 packetHandler로 전달된다`() {
        val fakeGate = ServerSocket(0).also { fakeGateServer = it }
        var acceptedSocket: Socket? = null
        val accepted = CountDownLatch(1)
        Thread {
            runCatching { acceptedSocket = fakeGate.accept() }
            accepted.countDown()
        }.apply { isDaemon = true; start() }

        val gateDetailRepository = mock(GateDetailRepository::class.java)
        `when`(gateDetailRepository.findByUseYnTrueOrderByDtlIp())
            .thenReturn(listOf(gateDetail("127.0.0.1", 1)))
        `when`(gateDetailRepository.findLaneInfoByDtlIp(eqOf("127.0.0.1"))).thenReturn(
            listOf(GateLaneInfo(locId = 1L, grpId = 1L, dtlId = 1L, dtlLaneNo = 1, dtlType = 1, analysisYn = true)),
        )

        val handled = CountDownLatch(1)
        var receivedBytes: ByteArray? = null
        newClient(gateDetailRepository, fakeGate.localPort) { _, packet ->
            receivedBytes = packet.raw
            handled.countDown()
        }.start()

        assertTrue(accepted.await(5, TimeUnit.SECONDS))
        val socket = requireNotNull(acceptedSocket)
        // 상태 요청 2바이트를 먼저 흘려보낸다(연결 직후 CLIENT가 보내는 것 — 무시하고 읽어치운다).
        socket.soTimeout = 5000
        socket.getInputStream().read(ByteArray(2))

        socket.getOutputStream().write(byteArrayOf(0x01, 0x02, 0x03))
        socket.getOutputStream().flush()

        assertTrue(handled.await(5, TimeUnit.SECONDS), "게이트가 보낸 패킷이 packetHandler로 전달되지 않았습니다.")
        assertEquals(listOf<Byte>(0x01, 0x02, 0x03), receivedBytes?.toList())
        socket.close()
    }

    @Test
    fun `같은 IP의 레인이 여러 개여도 연결은 한 번만 시도한다`() {
        // 클래스 KDoc: 레거시도 IP로 미리 그룹핑해 같은 IP의 여러 레인이 중복 연결을 시도하지
        // 않게 막는다 — connectToAllDevices의 groupBy(dtlIp) 회귀 테스트.
        val fakeGate = ServerSocket(0).also { fakeGateServer = it }
        val acceptCount = java.util.concurrent.atomic.AtomicInteger(0)
        val acceptedSockets = java.util.concurrent.CopyOnWriteArrayList<Socket>()
        Thread {
            while (!fakeGate.isClosed) {
                val socket = runCatching { fakeGate.accept() }.getOrNull() ?: break
                acceptedSockets += socket
                acceptCount.incrementAndGet()
            }
        }.apply { isDaemon = true; start() }

        val gateDetailRepository = mock(GateDetailRepository::class.java)
        `when`(gateDetailRepository.findByUseYnTrueOrderByDtlIp())
            .thenReturn(listOf(gateDetail("127.0.0.1", 1), gateDetail("127.0.0.1", 2)))
        `when`(gateDetailRepository.findLaneInfoByDtlIp(eqOf("127.0.0.1"))).thenReturn(
            listOf(
                GateLaneInfo(locId = 1L, grpId = 1L, dtlId = 1L, dtlLaneNo = 1, dtlType = 1, analysisYn = true),
                GateLaneInfo(locId = 1L, grpId = 1L, dtlId = 2L, dtlLaneNo = 2, dtlType = 1, analysisYn = true),
            ),
        )

        newClient(gateDetailRepository, fakeGate.localPort).start()

        // 재확인 주기(1초)를 한 번 더 넘겨서도 추가 연결이 생기지 않는지 확인한다.
        Thread.sleep(1500)

        assertEquals(1, acceptCount.get(), "같은 IP인데도 레인 수만큼 중복 연결을 시도했습니다.")
        acceptedSockets.forEach { it.close() }
    }

    @Test
    fun `레인이 전부 analysis_yn='N'인 디바이스도 CLIENT 모드 연결 대상에 포함된다`() {
        // 2026-08-20 코드 리뷰 지적 회귀 테스트: connectToAllDevices()가 예전에는
        // findByUseYnTrueAndAnalysisYnTrueOrderByDtlIp()로 analysis_yn='Y' 레인만 조회해, 모든
        // 레인이 analysis_yn='N'인 디바이스가 CLIENT 모드 연결 대상에서 통째로 빠졌었다
        // (ACK/실패 기록 누락으로 이어짐). SERVER 모드(findFirstByDtlIpAndUseYnTrueOrderByDtlLaneNo)와
        // 마찬가지로 analysis_yn 무관 findByUseYnTrueOrderByDtlIp()를 써야 한다.
        val fakeGate = ServerSocket(0).also { fakeGateServer = it }
        val accepted = CountDownLatch(1)
        var acceptedSocket: Socket? = null
        Thread {
            runCatching { acceptedSocket = fakeGate.accept() }
            accepted.countDown()
        }.apply { isDaemon = true; start() }

        val gateDetailRepository = mock(GateDetailRepository::class.java)
        val onlyNonAnalysisLane = gateDetail("127.0.0.1", 1).apply { analysisYn = false }
        `when`(gateDetailRepository.findByUseYnTrueOrderByDtlIp())
            .thenReturn(listOf(onlyNonAnalysisLane))
        `when`(gateDetailRepository.findLaneInfoByDtlIp(eqOf("127.0.0.1"))).thenReturn(
            listOf(GateLaneInfo(locId = 1L, grpId = 1L, dtlId = 1L, dtlLaneNo = 1, dtlType = 1, analysisYn = false)),
        )

        val registry = newRegistry(gateDetailRepository)
        newClient(gateDetailRepository, fakeGate.localPort, registry = registry).start()

        assertTrue(accepted.await(5, TimeUnit.SECONDS), "analysis_yn='N' 레인만 있는 디바이스에 연결을 시도하지 않았습니다.")
        assertTrue(waitUntil { registry.findConnection("127.0.0.1") != null }, "커넥션이 registry에 등록되지 않았습니다.")
        acceptedSocket?.close()
    }

    @Test
    fun `지원하지 않는 게이트 타입이면 연결을 시도하지 않는다`() {
        val fakeGate = ServerSocket(0).also { fakeGateServer = it }
        val accepted = CountDownLatch(1)
        Thread {
            runCatching { fakeGate.accept() }
            accepted.countDown()
        }.apply { isDaemon = true; start() }

        val gateDetailRepository = mock(GateDetailRepository::class.java)
        // FakeCodec은 dtlType=1만 지원 — 3은 미지원.
        `when`(gateDetailRepository.findByUseYnTrueOrderByDtlIp())
            .thenReturn(listOf(gateDetail("127.0.0.1", 1, dtlType = 3)))

        newClient(gateDetailRepository, fakeGate.localPort).start()

        assertFalse(accepted.await(1500, TimeUnit.MILLISECONDS), "미지원 게이트 타입인데도 연결을 시도했습니다.")
    }

    private fun waitUntil(timeoutMs: Long = 5000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return condition()
    }
}

// GateTcpServerTest와 동일한 이유(Mockito Java any(Class)가 null을 반환해 Kotlin 비-null 파라미터에서
// 즉시 NPE가 남)로 제네릭 소거 우회 헬퍼를 그대로 가져온다.
private fun <T> eqOf(value: T): T {
    Mockito.eq(value)
    @Suppress("UNCHECKED_CAST")
    return null as T
}
