package kr.co.securance.secuhub.server.connection

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kr.co.securance.secuhub.domain.entity.GateDetail
import kr.co.securance.secuhub.domain.entity.GateGroup
import kr.co.securance.secuhub.domain.entity.GateLocation
import kr.co.securance.secuhub.domain.repository.GateDetailRepository
import kr.co.securance.secuhub.domain.repository.NetStateRepository
import kr.co.securance.secuhub.protocol.GatePacket
import kr.co.securance.secuhub.protocol.GateProtocolCodec
import kr.co.securance.secuhub.protocol.PacketReassembler
import kr.co.securance.secuhub.protocol.SpeedGateControlPayload
import kr.co.securance.secuhub.server.config.ServerModeConfig
import kr.co.securance.secuhub.server.db.GateDbWriteQueue
import kr.co.securance.secuhub.server.db.GateDbWriteTask
import org.mockito.Mockito
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import reactor.core.publisher.Mono
import reactor.netty.Connection
import reactor.netty.NettyOutbound
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Kotlin의 non-null 파라미터에 [Mockito.any]를 그대로 넘기면 Mockito가 반환하는 null이
 * Kotlin의 null 체크에 걸려 NPE가 나고, 그 여파로 이후 스텁/검증까지 줄줄이 깨진다
 * (Mockito+Kotlin 조합에서 잘 알려진 함정). 제네릭 함수로 감싸 우회한다.
 */
private fun <T> anyKt(): T {
    Mockito.any<T>()
    @Suppress("UNCHECKED_CAST")
    return null as T
}

/** [NetStateRepository.upsertIfNewer] 호출 1건을 그대로 기록한 값 — 검증을 매처 대신 평범한 값 비교로 한다. */
private data class UpsertCall(
    val dtlIp: String,
    val dtlLaneNo: Int,
    val locId: Long,
    val grpId: Long,
    val dtlState: String,
    val checkTime: String,
    val seq: Long,
    val serverIp: String,
)

/**
 * [NetStateRepository.upsertIfNewer] 호출을 가로채 [into]에 기록한다.
 *
 * Kotlin 이름 붙은 인자 호출에서 `eq(...)`/`anyKt()` 매처를 섞어 쓰면 Mockito가 인자 평가 순서를
 * 잘못 해석해 `InvalidUseOfMatchersException`/NPE로 깨지는 문제(Kotlin+Mockito의 잘 알려진 함정)를
 * 피하기 위해, 매처 기반 `verify` 대신 이 방식으로 실제 호출 인자를 평범한 값으로 기록해 비교한다.
 */
private fun recordUpsertCalls(repository: NetStateRepository, into: MutableList<UpsertCall>) {
    doAnswer { invocation ->
        into += UpsertCall(
            dtlIp = invocation.getArgument(0),
            dtlLaneNo = invocation.getArgument(1),
            locId = invocation.getArgument(2),
            grpId = invocation.getArgument(3),
            dtlState = invocation.getArgument(4),
            checkTime = invocation.getArgument(5),
            seq = invocation.getArgument(6),
            serverIp = invocation.getArgument(7),
        )
        null
    }.`when`(repository).upsertIfNewer(
        org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.anyInt(),
        org.mockito.ArgumentMatchers.anyLong(),
        org.mockito.ArgumentMatchers.anyLong(),
        org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.anyLong(),
        org.mockito.ArgumentMatchers.anyString(),
    )
}

/** 테스트에서 [GateProtocolCodec]의 실제 파싱 로직은 필요 없으므로 최소한만 구현한 페이크. */
private object FakeCodec : GateProtocolCodec {
    override val supportedGateTypes: Set<Int> = setOf(1)
    override val defaultAddress: ByteArray = ByteArray(0)
    override fun verifyChecksum(packet: ByteArray): Boolean = true
    override fun decode(packet: ByteArray): GatePacket = throw UnsupportedOperationException()
    override fun buildStatusRequest(address: ByteArray, dateTime: LocalDateTime): ByteArray = ByteArray(0)
    override fun buildAck(objectCode: Byte, dateTime: LocalDateTime): ByteArray = ByteArray(0)
    override fun buildControlCommand(laneNo: Int, payload: SpeedGateControlPayload): ByteArray = ByteArray(0)
    override fun newReassembler(): PacketReassembler = object : PacketReassembler {
        override fun append(chunk: ByteArray): List<ByteArray> = emptyList()
    }
}

class GateConnectionRegistryImplTest {

    /** 실제 Netty 소켓 없이도 [GateConnectionState]를 만들기 위한 mock 기반 헬퍼. */
    private fun newState(dtlIp: String, queueCapacity: Int = 10): GateConnectionState {
        val connection = mock(Connection::class.java)
        `when`(connection.isDisposed).thenReturn(false)
        val outbound = mock(NettyOutbound::class.java)
        `when`(outbound.sendByteArray(anyKt())).thenReturn(outbound)
        `when`(outbound.then()).thenReturn(Mono.empty())
        return GateConnectionState(
            dtlIp = dtlIp,
            gateTypeCode = 1,
            codec = FakeCodec,
            connection = connection,
            outbound = outbound,
            actor = GateConnectionActor(dtlIp, Dispatchers.Default, queueCapacity),
        )
    }

    /** [NetStateRepository]/[GateDetailRepository]는 이 테스트들에서 실제로 조회되지 않는다
     * (dbWriteQueue를 mock으로 가로채므로 enqueueNetStateUpdate의 실행 블록 자체가 호출되지
     * 않는다) — 스텁 없이 빈 mock으로 충분하다. */
    private fun newRegistry(
        netStateRepository: NetStateRepository = mock(NetStateRepository::class.java),
        dbWriteQueue: GateDbWriteQueue = mock(GateDbWriteQueue::class.java),
        gateDetailRepository: GateDetailRepository = mock(GateDetailRepository::class.java),
    ) = GateConnectionRegistryImpl(netStateRepository, dbWriteQueue, gateDetailRepository, ServerModeConfig())

    @Test
    fun `register는 새 커넥션을 조회 가능하게 만든다`() {
        val registry = newRegistry()
        val state = newState("192.168.0.10")

        registry.register(state)

        assertSame(state, registry.findConnection("192.168.0.10"))
        assertEquals(1, registry.allConnections().size)
    }

    @Test
    fun `같은 IP로 재등록하면 이전 커넥션의 액터와 소켓을 정리한다`() {
        val registry = newRegistry()
        val previous = newState("192.168.0.11")
        val next = newState("192.168.0.11")

        registry.register(previous)
        registry.register(next)

        assertTrue(previous.actor.isClosed, "이전 커넥션의 액터는 닫혀야 한다")
        verify(previous.connection).dispose()
        // 새 커넥션은 그대로 살아있어야 한다 — 재연결 교체가 새 소켓까지 닫아버리면 안 된다.
        assertFalse(next.actor.isClosed)
        verify(next.connection, never()).dispose()
        assertSame(next, registry.findConnection("192.168.0.11"))
    }

    @Test
    fun `closeConnectionIfCurrent는 이미 재연결로 교체된 오래된 state에 대해서는 아무 것도 하지 않는다`() {
        // GateTcpServer.registerDisposeGuard/NetCheckJob이 쓰는 원자적 가드를 검증한다 —
        // 이전 소켓의 지연된 dispose 콜백이 최신 커넥션을 잘못 제거/오프라인 처리하면 안 된다.
        val registry = newRegistry()
        val oldState = newState("192.168.0.12")
        val newState = newState("192.168.0.12")

        registry.register(oldState)
        registry.register(newState) // 재연결 — registry는 이제 newState를 가리킨다.

        // oldState 소켓의 onDispose 콜백이 뒤늦게 도착했다고 가정한 시나리오.
        val closed = runBlocking { registry.closeConnectionIfCurrent(oldState.dtlIp, oldState, updateNetState = true) }

        assertFalse(closed, "oldState는 더 이상 registry의 현재 커넥션이 아니므로 정리 대상이 아니다")
        assertFalse(newState.actor.isClosed, "최신 커넥션의 액터가 잘못 닫히면 안 된다")
        assertSame(newState, registry.findConnection("192.168.0.12"))
    }

    @Test
    fun `closeConnectionIfCurrent는 확인과 제거 사이의 재연결 레이스에도 새 커넥션을 지우지 않는다`() {
        // findConnection(ip) === state 로 확인한 뒤 별도로 closeConnection(ip)을 호출하던 예전 방식은
        // "확인"과 "제거" 사이에 재연결이 끼어드는 TOCTOU 레이스가 있었다 — closeConnection(ip)이
        // 인스턴스를 가리지 않고 키만으로 지우기 때문에, 그 틈에 등록된 새 커넥션까지 지워버렸다.
        // closeConnectionIfCurrent는 조회+제거가 단일 원자 연산이므로 그 레이스 자체가 성립하지 않는다.
        val registry = newRegistry()
        val oldState = newState("192.168.0.19")
        registry.register(oldState)

        // "확인" 시점에는 oldState가 여전히 현재 커넥션이다 — 하지만 그 직후(제거 호출 전) 재연결이 끼어든다.
        assertSame(oldState, registry.findConnection(oldState.dtlIp))
        val newState = newState("192.168.0.19")
        registry.register(newState) // 레이스 윈도우에 재연결 발생.

        val closed = runBlocking { registry.closeConnectionIfCurrent(oldState.dtlIp, oldState, updateNetState = true) }

        assertFalse(closed, "레이스 윈도우에 교체된 새 커넥션을 지우면 안 된다")
        assertFalse(newState.actor.isClosed, "새 커넥션의 액터가 잘못 닫히면 안 된다")
        assertSame(newState, registry.findConnection("192.168.0.19"))
    }

    @Test
    fun `closeConnectionIfCurrent는 현재 커넥션과 일치하면 닫고 레인별 오프라인 갱신을 큐잉한다`() {
        val enqueuedTasks = mutableListOf<GateDbWriteTask>()
        val dbWriteQueue = mock(GateDbWriteQueue::class.java)
        doAnswer { invocation ->
            enqueuedTasks.add(invocation.getArgument(0))
            null
        }.`when`(dbWriteQueue).enqueue(anyKt())
        val registry = newRegistry(dbWriteQueue = dbWriteQueue)

        val state = newState("192.168.0.21")
        state.ensureLaneKnown(1)
        registry.register(state)

        val closed = runBlocking { registry.closeConnectionIfCurrent("192.168.0.21", state, updateNetState = true) }

        assertTrue(closed)
        assertTrue(state.actor.isClosed)
        assertNull(registry.findConnection("192.168.0.21"))
        assertEquals(1, enqueuedTasks.size)
    }

    @Test
    fun `closeConnection은 커넥션을 제거하고 레인별로 오프라인 갱신을 큐잉한다`() {
        val enqueuedTasks = mutableListOf<GateDbWriteTask>()
        val dbWriteQueue = mock(GateDbWriteQueue::class.java)
        doAnswer { invocation ->
            enqueuedTasks.add(invocation.getArgument(0))
            null
        }.`when`(dbWriteQueue).enqueue(anyKt())
        val registry = newRegistry(dbWriteQueue = dbWriteQueue)

        val state = newState("192.168.0.13")
        state.ensureLaneKnown(1)
        state.ensureLaneKnown(2)
        registry.register(state)

        runBlocking { registry.closeConnection("192.168.0.13", updateNetState = true) }

        assertNull(registry.findConnection("192.168.0.13"))
        assertTrue(state.actor.isClosed)
        assertEquals(2, enqueuedTasks.size)
        assertTrue(enqueuedTasks.all { it.partitionKey == "192.168.0.13" })
        assertTrue(enqueuedTasks.any { it.operationName.contains(",1,") })
        assertTrue(enqueuedTasks.any { it.operationName.contains(",2,") })
    }

    @Test
    fun `closeConnection은 updateNetState가 false면 오프라인 갱신을 큐잉하지 않는다`() {
        val dbWriteQueue = mock(GateDbWriteQueue::class.java)
        val registry = newRegistry(dbWriteQueue = dbWriteQueue)

        val state = newState("192.168.0.14")
        state.ensureLaneKnown(1)
        registry.register(state)

        runBlocking { registry.closeConnection("192.168.0.14", updateNetState = false) }

        assertNull(registry.findConnection("192.168.0.14"))
        verify(dbWriteQueue, never()).enqueue(anyKt())
    }

    @Test
    fun `sendToLane은 소유하지 않은 레인이고 authoritative 정보가 있으면 거부한다`() {
        val registry = newRegistry()
        val state = newState("192.168.0.15")
        state.replaceLaneNumbers(listOf(1, 2)) // authoritative 교체 — 레인 3은 이 커넥션 소유가 아님이 확정됨.
        registry.register(state)

        val result = runBlocking { registry.sendToLane("192.168.0.15", 3, byteArrayOf(0x01)) }

        assertFalse(result)
    }

    @Test
    fun `sendToLane은 소유한 레인이면 전송을 액터에 제출하고 true를 반환한다`() {
        val registry = newRegistry()
        val state = newState("192.168.0.16")
        state.replaceLaneNumbers(listOf(1, 2))
        registry.register(state)

        val result = runBlocking { registry.sendToLane("192.168.0.16", 1, byteArrayOf(0x01)) }

        assertTrue(result)
    }

    @Test
    fun `sendToLane은 등록되지 않은 IP면 false를 반환한다`() {
        val registry = newRegistry()

        assertFalse(runBlocking { registry.sendToLane("10.0.0.1", 1, byteArrayOf()) })
    }

    @Test
    fun `enqueueNetStateUpdate가 큐잉한 작업은 tb_gate_dtl의 실제 loc_id와 grp_id로 upsert한다`() {
        // 회귀 방지 테스트: 예전에는 loc_id/grp_id를 항상 0으로 고정해 저장했다(스캐폴드 플레이스홀더).
        // 위치/그룹별로 tb_net_state를 조회하는 화면은 항상 빈 결과를 받게 되는 버그였다.
        val location = GateLocation(locId = 7L, locName = "본관")
        val group = GateGroup(grpId = 3L, location = location, grpName = "1층")
        val gateDetail = GateDetail(
            dtlId = 1L,
            location = location,
            group = group,
            dtlIp = "192.168.0.20",
            dtlLaneNo = 1,
            dtlType = 1,
        )
        val gateDetailRepository = mock(GateDetailRepository::class.java)
        `when`(gateDetailRepository.findByDtlIpAndDtlLaneNo("192.168.0.20", 1)).thenReturn(gateDetail)

        val upsertCalls = mutableListOf<UpsertCall>()
        val netStateRepository = mock(NetStateRepository::class.java)
        recordUpsertCalls(netStateRepository, upsertCalls)
        `when`(netStateRepository.nextSeq()).thenReturn(1L)

        val dbWriteQueue = mock(GateDbWriteQueue::class.java)
        var capturedTask: GateDbWriteTask? = null
        doAnswer { invocation -> capturedTask = invocation.getArgument(0); null }
            .`when`(dbWriteQueue).enqueue(anyKt())

        val registry = newRegistry(
            netStateRepository = netStateRepository,
            dbWriteQueue = dbWriteQueue,
            gateDetailRepository = gateDetailRepository,
        )

        runBlocking { registry.enqueueNetStateUpdate("192.168.0.20", 1, online = true) }
        runBlocking { capturedTask!!.execute() }

        val call = upsertCalls.single()
        assertEquals("192.168.0.20", call.dtlIp)
        assertEquals(1, call.dtlLaneNo)
        assertEquals(7L, call.locId)
        assertEquals(3L, call.grpId)
        assertEquals("Y", call.dtlState)
        // seq는 이제 DB 전역 시퀀스(NetStateRepository.nextSeq)로 발급된다(코드 리뷰 지적, P1,
        // 2026-08-20 — GateConnectionRegistryImpl 클래스 상단 주석 참고). 고정값 대신 "발급됐다"만
        // 검증한다.
        assertTrue(call.seq > 0L, "seq가 발급돼야 한다")
        assertTrue(call.checkTime.isNotBlank())
        // 컬럼 누락 회귀 방지(2026-08-26 dev DB 실측 검증) — server_ip가 예전에는 upsertIfNewer
        // 호출 자체에 전달되지 않아 DB에 한 번도 쓰인 적이 없었다. 값 자체는 로컬 IP 조회 성공
        // 여부에 따라 환경마다 다를 수 있어 "비어있지 않다"만 검증한다.
        assertTrue(call.serverIp.isNotBlank(), "server_ip가 채워져야 한다")
    }

    /**
     * 코드 리뷰 지적 R-8(2026-08-20) 이후 회귀 테스트 — 순서 역전 방지 로직 자체는
     * [NetStateRepository.upsertIfNewer]의 조건부 UPSERT(`applied_seq <= VALUES(applied_seq)`)로
     * 옮겨졌으므로(그 조건부 로직 검증은 SQL 레벨이라 리포지토리 쪽 책임 — upsertIfNewer KDoc의
     * "테스트 커버리지의 한계" 참고), 이 레지스트리가 여전히 책임지는 부분만 검증한다: 이벤트
     * **발생 순서 그대로** 단조증가하는 seq를 발급해 매번 `upsertIfNewer`에 넘기는지.
     *
     * 예전에는 이 파일에 인메모리 락/시퀀스 맵의 동시성 정확성을 직접 검증하는 테스트 3개
     * (지연 실행 역전 방지, 실제 스레드 경합, 그룹 재배정 시 캐시 정규화)가 있었다 — 그 인메모리
     * 상태 자체가 이번에 제거되었으므로 함께 제거한다.
     */
    @Test
    fun `enqueueNetStateUpdate는 호출(발생) 순서 그대로 단조증가하는 시퀀스를 발급한다`() {
        val location = GateLocation(locId = 7L, locName = "본관")
        val group = GateGroup(grpId = 3L, location = location, grpName = "1층")
        val gateDetail = GateDetail(
            dtlId = 1L, location = location, group = group,
            dtlIp = "192.168.0.30", dtlLaneNo = 1, dtlType = 1,
        )
        val gateDetailRepository = mock(GateDetailRepository::class.java)
        `when`(gateDetailRepository.findByDtlIpAndDtlLaneNo("192.168.0.30", 1)).thenReturn(gateDetail)

        val upsertCalls = mutableListOf<UpsertCall>()
        val netStateRepository = mock(NetStateRepository::class.java)
        recordUpsertCalls(netStateRepository, upsertCalls)
        // DB 전역 시퀀스(NEXT VALUE FOR)를 흉내낸다 — 호출될 때마다 1씩 커지는 값을 반환.
        `when`(netStateRepository.nextSeq()).thenReturn(1L, 2L)
        val capturedTasks = mutableListOf<GateDbWriteTask>()
        val dbWriteQueue = mock(GateDbWriteQueue::class.java)
        doAnswer { invocation -> capturedTasks.add(invocation.getArgument(0)); null }
            .`when`(dbWriteQueue).enqueue(anyKt())

        val registry = newRegistry(
            netStateRepository = netStateRepository,
            dbWriteQueue = dbWriteQueue,
            gateDetailRepository = gateDetailRepository,
        )

        // OFFLINE(먼저 발생) 다음 ONLINE(나중 발생) — 실행 순서와 무관하게 "발생 순서"가 seq에 반영돼야 한다.
        runBlocking {
            registry.enqueueNetStateUpdate("192.168.0.30", 1, online = false)
            registry.enqueueNetStateUpdate("192.168.0.30", 1, online = true)
        }
        assertEquals(2, capturedTasks.size)

        // 실행은 역순으로 완료된다고 가정해도(ONLINE 먼저, OFFLINE 뒤늦게), 발급된 seq 자체는
        // enqueue 호출 시점(=이벤트 발생 순서) 기준으로 이미 고정돼 있어야 한다.
        runBlocking { capturedTasks[1].execute() } // ONLINE(나중 발생, seq 더 큼)을 먼저 실행.
        runBlocking { capturedTasks[0].execute() } // OFFLINE(먼저 발생, seq 더 작음)을 뒤늦게 실행.

        assertEquals(2, upsertCalls.size)
        val offlineCall = upsertCalls.single { it.dtlState == "N" }
        val onlineCall = upsertCalls.single { it.dtlState == "Y" }
        // seq는 이제 DB 전역 시퀀스(NetStateRepository.nextSeq)로 발급된다 — 고정값(1L/2L) 대신
        // "먼저 발생한 쪽이 더 작은 seq를 받았다"는 상대적 순서만 검증한다.
        assertTrue(offlineCall.seq < onlineCall.seq, "먼저 발생한 OFFLINE이 더 작은 seq를 가져야 한다")
    }

    @Test
    fun `enqueueNetStateUpdate는 tb_gate_dtl에 없는 레인이면 upsert를 건너뛴다`() {
        val gateDetailRepository = mock(GateDetailRepository::class.java)
        `when`(gateDetailRepository.findByDtlIpAndDtlLaneNo("10.0.0.99", 1)).thenReturn(null)

        val upsertCalls = mutableListOf<UpsertCall>()
        val netStateRepository = mock(NetStateRepository::class.java)
        recordUpsertCalls(netStateRepository, upsertCalls)
        `when`(netStateRepository.nextSeq()).thenReturn(1L)
        val dbWriteQueue = mock(GateDbWriteQueue::class.java)
        var capturedTask: GateDbWriteTask? = null
        doAnswer { invocation -> capturedTask = invocation.getArgument(0); null }
            .`when`(dbWriteQueue).enqueue(anyKt())

        val registry = newRegistry(
            netStateRepository = netStateRepository,
            dbWriteQueue = dbWriteQueue,
            gateDetailRepository = gateDetailRepository,
        )

        runBlocking { registry.enqueueNetStateUpdate("10.0.0.99", 1, online = true) }
        runBlocking { capturedTask!!.execute() }

        assertTrue(upsertCalls.isEmpty())
    }
}
