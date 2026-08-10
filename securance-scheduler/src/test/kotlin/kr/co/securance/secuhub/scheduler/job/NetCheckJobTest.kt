package kr.co.securance.secuhub.scheduler.job

import io.netty.channel.Channel
import kotlinx.coroutines.Dispatchers
import kr.co.securance.secuhub.protocol.SpeedFlapGateProtocolCodec
import kr.co.securance.secuhub.scheduler.config.SchedulerProperties
import kr.co.securance.secuhub.server.connection.GateConnectionActor
import kr.co.securance.secuhub.server.connection.GateConnectionState
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.quartz.JobExecutionContext
import reactor.netty.Connection
import reactor.netty.NettyOutbound
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [NetCheckJob] 검증 — 전체 프로젝트 재감사(2026-08-10)에서 "완전한 예시(계획서 7절 스캐폴드
 * 항목)"라고 문서에 명시된 잡인데도 테스트가 없었던 항목의 최소 커버리지. 채널이 닫힌 커넥션만
 * 골라 정리하고, 살아있는 커넥션은 건드리지 않는지를 우선 검증한다.
 */
class NetCheckJobTest {

    private fun buildState(dtlIp: String, channelActive: Boolean): GateConnectionState {
        val channel = mock(Channel::class.java)
        `when`(channel.isActive).thenReturn(channelActive)
        val connection = mock(Connection::class.java)
        `when`(connection.channel()).thenReturn(channel)

        return GateConnectionState(
            dtlIp = dtlIp,
            gateTypeCode = 1,
            codec = SpeedFlapGateProtocolCodec(),
            connection = connection,
            outbound = mock(NettyOutbound::class.java),
            actor = GateConnectionActor(dtlIp, Dispatchers.Default, queueCapacity = 10),
        )
    }

    private fun buildJob(registry: FakeGateConnectionRegistry, properties: SchedulerProperties = SchedulerProperties()): NetCheckJob {
        val job = NetCheckJob()
        injectField(job, "registry", registry)
        injectField(job, "properties", properties)
        return job
    }

    private val context: JobExecutionContext = fakeJobExecutionContext()

    @Test
    fun `연결이 없으면 아무 것도 정리하지 않는다`() {
        val registry = FakeGateConnectionRegistry()
        val job = buildJob(registry)

        job.execute(context)

        assertTrue(registry.allConnections().isEmpty())
    }

    @Test
    fun `채널이 살아있는 커넥션은 정리하지 않는다`() {
        val registry = FakeGateConnectionRegistry()
        registry.addConnection(buildState("192.168.0.10", channelActive = true))

        val job = buildJob(registry)
        job.execute(context)

        assertNotNull(registry.findConnection("192.168.0.10"))
    }

    @Test
    fun `채널이 닫힌 커넥션은 정리한다`() {
        val registry = FakeGateConnectionRegistry()
        registry.addConnection(buildState("192.168.0.20", channelActive = false))

        val job = buildJob(registry)
        job.execute(context)

        assertNull(registry.findConnection("192.168.0.20"))
    }

    @Test
    fun `살아있는 커넥션과 닫힌 커넥션이 섞여 있어도 닫힌 것만 정리한다`() {
        val registry = FakeGateConnectionRegistry()
        registry.addConnection(buildState("192.168.0.30", channelActive = true))
        registry.addConnection(buildState("192.168.0.31", channelActive = false))

        val job = buildJob(registry)
        job.execute(context)

        assertNotNull(registry.findConnection("192.168.0.30"))
        assertNull(registry.findConnection("192.168.0.31"))
    }
}
