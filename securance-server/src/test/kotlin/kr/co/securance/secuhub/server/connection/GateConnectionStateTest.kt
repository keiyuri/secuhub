package kr.co.securance.secuhub.server.connection

import kotlinx.coroutines.Dispatchers
import kr.co.securance.secuhub.protocol.GatePacket
import kr.co.securance.secuhub.protocol.GateProtocolCodec
import kr.co.securance.secuhub.protocol.PacketReassembler
import org.mockito.Mockito.mock
import reactor.netty.Connection
import reactor.netty.NettyOutbound
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [GateConnectionRegistryImplTest]와 동일한 최소 페이크 — 파싱 로직은 이 테스트에서 필요 없다. */
private object StateTestFakeCodec : GateProtocolCodec {
    override val supportedGateTypes: Set<Int> = setOf(1)
    override fun verifyChecksum(packet: ByteArray): Boolean = true
    override fun decode(packet: ByteArray): GatePacket = throw UnsupportedOperationException()
    override fun buildStatusRequest(address: ByteArray, dateTime: LocalDateTime): ByteArray = ByteArray(0)
    override fun newReassembler(): PacketReassembler = object : PacketReassembler {
        override fun append(chunk: ByteArray): List<ByteArray> = emptyList()
    }
}

class GateConnectionStateTest {

    private fun newState(): GateConnectionState =
        GateConnectionState(
            dtlIp = "192.168.0.20",
            gateTypeCode = 1,
            codec = StateTestFakeCodec,
            connection = mock(Connection::class.java),
            outbound = mock(NettyOutbound::class.java),
            actor = GateConnectionActor("192.168.0.20", Dispatchers.Default, 10),
        )

    @Test
    fun `replaceLaneNumbers는 레인 집합을 완전히 교체한다`() {
        val state = newState()
        state.replaceLaneNumbers(listOf(1, 2, 3))
        assertEquals(setOf(1, 2, 3), state.laneSnapshot())

        state.replaceLaneNumbers(listOf(4, 5)) // 축소도 허용된다(0x4D는 authoritative).
        assertEquals(setOf(4, 5), state.laneSnapshot())
        assertFalse(state.ownsLane(1))
        assertTrue(state.ownsLane(4))
    }

    @Test
    fun `hasAuthoritativeLaneInfo는 replaceLaneNumbers 호출 전까지 false다`() {
        val state = newState()
        assertFalse(state.hasAuthoritativeLaneInfo)

        state.ensureLaneKnown(7) // union-add만으로는 authoritative가 되지 않는다.
        assertFalse(state.hasAuthoritativeLaneInfo)

        state.replaceLaneNumbers(listOf(1))
        assertTrue(state.hasAuthoritativeLaneInfo)
    }

    @Test
    fun `ensureLaneKnown은 기존 레인을 유지한 채 추가만 한다`() {
        val state = newState()
        state.replaceLaneNumbers(listOf(1, 2))

        state.ensureLaneKnown(3)
        assertEquals(setOf(1, 2, 3), state.laneSnapshot())

        state.ensureLaneKnown(3) // 중복 추가는 무해하다.
        assertEquals(setOf(1, 2, 3), state.laneSnapshot())
    }

    @Test
    fun `여러 스레드가 동시에 레인 집합을 교체해도 중간에 빈 집합이 절대 관측되지 않는다`() {
        // 적대적 리뷰 지적: 예전 구현(ConcurrentHashMap.newKeySet()의 clear()+addAll())은 그 사이
        // 창(window)에서 laneSnapshot()이 빈 집합을 반환할 수 있었다 — 그 창에서 sendToLane이 제어
        // 명령을 침묵 드롭하거나 finalizeClose가 오프라인 net_state 갱신을 전혀 큐잉하지 못했다.
        // AtomicReference로 교체한 뒤에는 항상 두 집합 중 하나만 관측되어야 한다(빈 집합 없음).
        val state = newState()
        val setA = setOf(1, 2, 3)
        val setB = setOf(4, 5, 6)
        state.replaceLaneNumbers(setA)

        val stop = AtomicBoolean(false)
        val emptyObservations = AtomicInteger(0)
        val iterations = 20_000

        val writer = Thread {
            repeat(iterations) { i -> state.replaceLaneNumbers(if (i % 2 == 0) setA else setB) }
            stop.set(true)
        }
        val reader = Thread {
            while (!stop.get()) {
                if (state.laneSnapshot().isEmpty()) emptyObservations.incrementAndGet()
            }
        }

        reader.start()
        writer.start()
        writer.join(10_000)
        stop.set(true)
        reader.join(2_000)

        assertEquals(0, emptyObservations.get(), "레인 집합 교체 도중 빈 집합이 관측되었습니다(원자성 위반).")
    }
}
