package kr.co.securance.secuhub.server.connection

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kr.co.securance.secuhub.domain.entity.GateDetail
import kr.co.securance.secuhub.domain.entity.GateGroup
import kr.co.securance.secuhub.domain.entity.GateLocation
import kr.co.securance.secuhub.domain.entity.NetState
import kr.co.securance.secuhub.domain.entity.NetStateId
import kr.co.securance.secuhub.domain.repository.GateDetailRepository
import kr.co.securance.secuhub.domain.repository.NetStateRepository
import kr.co.securance.secuhub.protocol.GatePacket
import kr.co.securance.secuhub.protocol.GateProtocolCodec
import kr.co.securance.secuhub.protocol.PacketReassembler
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
import java.util.Optional
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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

/** 테스트에서 [GateProtocolCodec]의 실제 파싱 로직은 필요 없으므로 최소한만 구현한 페이크. */
private object FakeCodec : GateProtocolCodec {
    override val supportedGateTypes: Set<Int> = setOf(1)
    override fun verifyChecksum(packet: ByteArray): Boolean = true
    override fun decode(packet: ByteArray): GatePacket = throw UnsupportedOperationException()
    override fun buildStatusRequest(address: ByteArray, dateTime: LocalDateTime): ByteArray = ByteArray(0)
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
    ) = GateConnectionRegistryImpl(netStateRepository, dbWriteQueue, gateDetailRepository)

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
    fun `enqueueNetStateUpdate가 큐잉한 작업은 tb_gate_dtl의 실제 loc_id와 grp_id로 저장한다`() {
        // 회귀 방지 테스트: 예전에는 loc_id/grp_id를 항상 0으로 고정해 저장했다(스캐폴드 플레이스홀더).
        // 위치/그룹별로 tb_net_state를 조회하는 화면은 항상 빈 결과를 받게 되는 버그였다.
        val location = GateLocation(locId = 7L, locName = "본관")
        val group = GateGroup(grpId = 3L, location = location, grpName = "1층", gateTypeCode = 1)
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

        val netStateRepository = mock(NetStateRepository::class.java)
        `when`(netStateRepository.findById(anyKt())).thenReturn(Optional.empty())

        val dbWriteQueue = mock(GateDbWriteQueue::class.java)
        var capturedTask: GateDbWriteTask? = null
        doAnswer { invocation -> capturedTask = invocation.getArgument(0); null }
            .`when`(dbWriteQueue).enqueue(anyKt())

        val registry = newRegistry(
            netStateRepository = netStateRepository,
            dbWriteQueue = dbWriteQueue,
            gateDetailRepository = gateDetailRepository,
        )

        registry.enqueueNetStateUpdate("192.168.0.20", 1, online = true)
        runBlocking { capturedTask!!.execute() }

        verify(netStateRepository).save(
            org.mockito.ArgumentMatchers.argThat { saved: NetState ->
                saved.id == NetStateId(dtlIp = "192.168.0.20", dtlLaneNo = 1, locId = 7L, grpId = 3L) &&
                    saved.dtlState == "Y"
            },
        )
    }

    @Test
    fun `enqueueNetStateUpdate는 오래된 시도가 뒤늦게 완료돼도 더 최신 갱신을 덮어쓰지 않는다`() {
        // 적대적 리뷰(codex) 지적 회귀 테스트: GateDbWriteQueue는 타임아웃된 시도를 백그라운드에
        // 버려둔 채 다음 작업으로 넘어간다 — 그 버려진(더 오래된) 시도가 뒤늦게 실제로 DB에 도달하면,
        // 이미 반영된 더 최신 상태를 과거 값으로 되돌려 순서를 역전시킬 수 있었다. 여기서는 OFFLINE
        // 이벤트(먼저 발생)를 나타내는 작업을 ONLINE 이벤트(나중에 발생)보다 "나중에" 실행시켜
        // 그 역전 시나리오를 그대로 재현한다.
        val location = GateLocation(locId = 7L, locName = "본관")
        val group = GateGroup(grpId = 3L, location = location, grpName = "1층", gateTypeCode = 1)
        val gateDetail = GateDetail(
            dtlId = 1L,
            location = location,
            group = group,
            dtlIp = "192.168.0.30",
            dtlLaneNo = 1,
            dtlType = 1,
        )
        val gateDetailRepository = mock(GateDetailRepository::class.java)
        `when`(gateDetailRepository.findByDtlIpAndDtlLaneNo("192.168.0.30", 1)).thenReturn(gateDetail)

        val savedStates = mutableListOf<String>()
        val netStateRepository = mock(NetStateRepository::class.java)
        `when`(netStateRepository.findById(anyKt())).thenReturn(Optional.empty())
        doAnswer { invocation ->
            savedStates.add((invocation.getArgument(0) as NetState).dtlState)
            null
        }.`when`(netStateRepository).save(anyKt())

        val capturedTasks = mutableListOf<GateDbWriteTask>()
        val dbWriteQueue = mock(GateDbWriteQueue::class.java)
        doAnswer { invocation -> capturedTasks.add(invocation.getArgument(0)); null }
            .`when`(dbWriteQueue).enqueue(anyKt())

        val registry = newRegistry(
            netStateRepository = netStateRepository,
            dbWriteQueue = dbWriteQueue,
            gateDetailRepository = gateDetailRepository,
        )

        // 발생 순서(호출 순서) 그대로 시퀀스가 발급된다: OFFLINE(오래된 이벤트) 먼저, ONLINE(최신 이벤트) 나중.
        registry.enqueueNetStateUpdate("192.168.0.30", 1, online = false)
        registry.enqueueNetStateUpdate("192.168.0.30", 1, online = true)
        assertEquals(2, capturedTasks.size)

        // 하지만 "실행"은 역순으로 완료된다고 가정한다 — ONLINE(최신)이 먼저 끝나고, OFFLINE(과거,
        // 타임아웃 후 버려졌던 시도)이 뒤늦게 도착한다.
        runBlocking { capturedTasks[1].execute() } // ONLINE 먼저 반영됨.
        runBlocking { capturedTasks[0].execute() } // 뒤늦게 도착한 OFFLINE — 반영되면 안 됨.

        assertEquals(listOf("Y"), savedStates, "뒤늦게 도착한 과거 이벤트가 최신 상태를 덮어쓰면 안 된다")
    }

    @Test
    fun `실제로 동시에 실행되는 오래된 실행과 최신 실행 사이에서도 클레임과 저장이 끼어들지 않는다`() {
        // 2차 적대적 리뷰(codex) 지적 회귀 테스트: "시퀀스 클레임 → findById → save"를 락 없이
        // 순서대로만 하면, 클레임 직후(오래된 실행이 이미 통과) findById/save가 DB 지연으로 느려지는
        // 동안 더 최신 실행이 끼어들어 먼저 클레임+저장을 끝내고, 그 뒤 오래된 실행이 재개돼 최신
        // 값을 덮어쓰는 TOCTOU 윈도우가 있었다(직전 테스트는 execute() 호출 자체를 순차적으로 완료시켜
        // 이 윈도우를 재현하지 못했다). 여기서는 실제 두 스레드로 오래된 실행을 DB 조회 도중 멈춰
        // 세워두고 최신 실행이 그 사이 끼어들 수 있는지 검증한다 — 락이 없다면 실행 순서가
        // [oldFind, newFind, newSave, oldSave]처럼 뒤섞일 수 있지만, 락이 있으면 절대 뒤섞이지 않는다.
        val location = GateLocation(locId = 7L, locName = "본관")
        val group = GateGroup(grpId = 3L, location = location, grpName = "1층", gateTypeCode = 1)
        val gateDetail = GateDetail(
            dtlId = 1L,
            location = location,
            group = group,
            dtlIp = "192.168.0.31",
            dtlLaneNo = 1,
            dtlType = 1,
        )
        val gateDetailRepository = mock(GateDetailRepository::class.java)
        `when`(gateDetailRepository.findByDtlIpAndDtlLaneNo("192.168.0.31", 1)).thenReturn(gateDetail)

        val events = ConcurrentLinkedQueue<String>()
        val oldFindStarted = CountDownLatch(1)
        val releaseOldFind = CountDownLatch(1)

        val netStateRepository = mock(NetStateRepository::class.java)
        doAnswer { invocation ->
            events.add("find")
            // 첫 호출(오래된 실행)만 인위적으로 지연시킨다 — 그 사이 최신 실행이 락을 뚫고
            // 끼어들 수 있는지가 이 테스트의 핵심이다.
            if (oldFindStarted.count > 0) {
                oldFindStarted.countDown()
                assertTrue(releaseOldFind.await(5, TimeUnit.SECONDS), "오래된 실행이 제때 풀려나지 못했습니다")
            }
            Optional.empty<NetState>()
        }.`when`(netStateRepository).findById(anyKt())
        doAnswer { invocation ->
            events.add("save:" + (invocation.getArgument(0) as NetState).dtlState)
            null
        }.`when`(netStateRepository).save(anyKt())

        val capturedTasks = mutableListOf<GateDbWriteTask>()
        val dbWriteQueue = mock(GateDbWriteQueue::class.java)
        doAnswer { invocation -> capturedTasks.add(invocation.getArgument(0)); null }
            .`when`(dbWriteQueue).enqueue(anyKt())

        val registry = newRegistry(
            netStateRepository = netStateRepository,
            dbWriteQueue = dbWriteQueue,
            gateDetailRepository = gateDetailRepository,
        )

        // OFFLINE(오래된 이벤트, seq=1)을 먼저 큐잉하고, ONLINE(최신 이벤트, seq=2)을 나중에 큐잉한다.
        registry.enqueueNetStateUpdate("192.168.0.31", 1, online = false)
        registry.enqueueNetStateUpdate("192.168.0.31", 1, online = true)
        assertEquals(2, capturedTasks.size)

        val oldThread = Thread { runBlocking { capturedTasks[0].execute() } }
        oldThread.start()
        assertTrue(oldFindStarted.await(5, TimeUnit.SECONDS), "오래된 실행이 findById에 도달하지 못했습니다")

        // 오래된 실행이 findById 안(락을 쥔 채)에 멈춰 있는 동안 최신 실행을 시작한다 — 락이 없다면
        // 여기서 최신 실행의 find/save가 오래된 실행보다 먼저 끝날 수 있다.
        val newThread = Thread { runBlocking { capturedTasks[1].execute() } }
        newThread.start()

        // 락이 정상 동작한다면 최신 실행은 오래된 실행이 unlock할 때까지 진입조차 못 해야 한다.
        Thread.sleep(200)
        assertEquals(listOf("find"), events.toList(), "락이 없으면 최신 실행이 오래된 실행보다 먼저 끼어들 수 있다")

        releaseOldFind.countDown()
        oldThread.join(5000)
        newThread.join(5000)

        assertFalse(oldThread.isAlive)
        assertFalse(newThread.isAlive)
        // 완전히 직렬화되어야 한다: 오래된 실행의 find/save가 전부 끝난 뒤에야 최신 실행의 find/save가 시작된다.
        assertEquals(listOf("find", "save:N", "find", "save:Y"), events.toList())
    }

    @Test
    fun `enqueueNetStateUpdate는 tb_gate_dtl에 없는 레인이면 저장을 건너뛴다`() {
        val gateDetailRepository = mock(GateDetailRepository::class.java)
        `when`(gateDetailRepository.findByDtlIpAndDtlLaneNo("10.0.0.99", 1)).thenReturn(null)

        val netStateRepository = mock(NetStateRepository::class.java)
        val dbWriteQueue = mock(GateDbWriteQueue::class.java)
        var capturedTask: GateDbWriteTask? = null
        doAnswer { invocation -> capturedTask = invocation.getArgument(0); null }
            .`when`(dbWriteQueue).enqueue(anyKt())

        val registry = newRegistry(
            netStateRepository = netStateRepository,
            dbWriteQueue = dbWriteQueue,
            gateDetailRepository = gateDetailRepository,
        )

        registry.enqueueNetStateUpdate("10.0.0.99", 1, online = true)
        runBlocking { capturedTask!!.execute() }

        verify(netStateRepository, never()).save(anyKt())
    }
}
