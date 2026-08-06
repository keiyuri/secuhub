package kr.co.securance.secuhub.scheduler.job

import kotlinx.coroutines.Dispatchers
import kr.co.securance.secuhub.protocol.SpeedFlapGateProtocolCodec
import kr.co.securance.secuhub.scheduler.config.SchedulerProperties
import kr.co.securance.secuhub.server.connection.GateConnectionActor
import kr.co.securance.secuhub.server.connection.GateConnectionState
import org.mockito.Mockito.mock
import org.quartz.JobExecutionContext
import reactor.netty.Connection
import reactor.netty.NettyOutbound
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReqStatusJobTest {

    private fun buildState(dtlIp: String, lanes: Set<Int> = setOf(1)): GateConnectionState {
        val state = GateConnectionState(
            dtlIp = dtlIp,
            gateTypeCode = 1,
            codec = SpeedFlapGateProtocolCodec(),
            connection = mock(Connection::class.java),
            outbound = mock(NettyOutbound::class.java),
            actor = GateConnectionActor(dtlIp, Dispatchers.Default, queueCapacity = 10),
        )
        state.replaceLaneNumbers(lanes)
        return state
    }

    private fun buildJob(registry: FakeGateConnectionRegistry, properties: SchedulerProperties = SchedulerProperties()): ReqStatusJob {
        val job = ReqStatusJob()
        injectField(job, "registry", registry)
        injectField(job, "properties", properties)
        return job
    }

    private val context: JobExecutionContext = fakeJobExecutionContext()

    @Test
    fun `연결이 없으면 아무 것도 전송하지 않는다`() {
        val registry = FakeGateConnectionRegistry()
        val job = buildJob(registry)

        job.execute(context)

        assertTrue(registry.sentCalls.isEmpty())
    }

    @Test
    fun `각 커넥션 단위로(레인 무관) 상태 조회 패킷을 전송한다`() {
        val registry = FakeGateConnectionRegistry(sendResult = true)
        registry.addConnection(buildState("192.168.0.10", lanes = setOf(3, 1, 2)))
        registry.addConnection(buildState("192.168.0.11", lanes = setOf(5)))

        val job = buildJob(registry)
        job.execute(context)

        assertEquals(2, registry.sentToConnectionCalls.size)
        assertTrue(registry.sentCalls.isEmpty()) // 레인 단위 sendToLane은 쓰지 않는다.
        val byIp = registry.sentToConnectionCalls.associate { it.first to it.second }
        // 상태 조회 패킷은 비어있지 않아야 한다.
        assertTrue(byIp.getValue("192.168.0.10").isNotEmpty())
        assertTrue(byIp.getValue("192.168.0.11").isNotEmpty())
    }

    @Test
    fun `레인 집합이 authoritative하게 비어 있어도 레인 소유권과 무관하게 전송한다`() {
        // 회귀 방지: 예전 구현은 "대표 레인"을 임의로 골라 sendToLane을 호출했는데, 레인 집합이
        // authoritative하게 비어 있는 특이 케이스에서는 실제 GateConnectionRegistryImpl의 레인
        // 소유권 검사에 걸려 영구적으로 전송이 거부될 수 있었다. sendToConnection은 레인 소유권을
        // 보지 않으므로 이 케이스에서도 정상 전송되어야 한다.
        val registry = FakeGateConnectionRegistry(sendResult = true)
        registry.addConnection(buildState("192.168.0.20", lanes = emptySet()))

        val job = buildJob(registry)
        job.execute(context)

        assertEquals("192.168.0.20", registry.sentToConnectionCalls.single().first)
    }

    @Test
    fun `전송 실패(대기열 초과 등)여도 다른 커넥션 처리에 영향을 주지 않는다`() {
        val registry = FakeGateConnectionRegistry(sendResult = false)
        registry.addConnection(buildState("192.168.0.30"))
        registry.addConnection(buildState("192.168.0.31"))

        val job = buildJob(registry)
        job.execute(context) // 예외 없이 완료되어야 한다.

        assertEquals(2, registry.sentToConnectionCalls.size)
    }
}
