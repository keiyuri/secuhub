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
    fun `각 커넥션의 대표 레인으로 상태 조회 패킷을 전송한다`() {
        val registry = FakeGateConnectionRegistry(sendResult = true)
        registry.addConnection(buildState("192.168.0.10", lanes = setOf(3, 1, 2)))
        registry.addConnection(buildState("192.168.0.11", lanes = setOf(5)))

        val job = buildJob(registry)
        job.execute(context)

        assertEquals(2, registry.sentCalls.size)
        val byIp = registry.sentCalls.associateBy { it.first }
        assertEquals(1, byIp.getValue("192.168.0.10").second) // minOrNull() == 1
        assertEquals(5, byIp.getValue("192.168.0.11").second)
        // 상태 조회 패킷은 비어있지 않아야 한다.
        assertTrue(byIp.getValue("192.168.0.10").third.isNotEmpty())
    }

    @Test
    fun `레인 정보가 없으면 기본 레인 1로 전송한다`() {
        val registry = FakeGateConnectionRegistry(sendResult = true)
        registry.addConnection(buildState("192.168.0.20", lanes = emptySet()))

        val job = buildJob(registry)
        job.execute(context)

        assertEquals(1, registry.sentCalls.single().second)
    }

    @Test
    fun `전송 실패(대기열 초과 등)여도 다른 커넥션 처리에 영향을 주지 않는다`() {
        val registry = FakeGateConnectionRegistry(sendResult = false)
        registry.addConnection(buildState("192.168.0.30"))
        registry.addConnection(buildState("192.168.0.31"))

        val job = buildJob(registry)
        job.execute(context) // 예외 없이 완료되어야 한다.

        assertEquals(2, registry.sentCalls.size)
    }
}
