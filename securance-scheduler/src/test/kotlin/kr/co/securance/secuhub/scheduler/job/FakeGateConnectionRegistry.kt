package kr.co.securance.secuhub.scheduler.job

import kr.co.securance.secuhub.server.connection.GateConnectionRegistry
import kr.co.securance.secuhub.server.connection.GateConnectionState
import org.mockito.Mockito
import org.quartz.JobExecutionContext
import org.quartz.Scheduler
import org.quartz.SchedulerContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [GateConnectionRegistry] 테스트 더블. `SendControlJob`/`ReqStatusJob` 단위 테스트에서
 * `GateConnectionActor`/Netty 내부를 실제로 띄우지 않고 전송 성공/실패를 자유롭게 시뮬레이션한다.
 */
class FakeGateConnectionRegistry(
    @Volatile var sendResult: Boolean = true,
) : GateConnectionRegistry {

    /** (dtlIp, dtlLaneNo, packet) 순서로 기록되는 전송 호출 이력 — 병렬 실행 테스트에서도 안전하게 기록. */
    val sentCalls = CopyOnWriteArrayList<Triple<String, Int, ByteArray>>()

    /** [sendToConnection] 호출 이력(레인 무관 커넥션 단위 전송, `ReqStatusJob`용). */
    val sentToConnectionCalls = CopyOnWriteArrayList<Pair<String, ByteArray>>()

    private val states = ConcurrentHashMap<String, GateConnectionState>()

    fun addConnection(state: GateConnectionState) {
        states[state.dtlIp] = state
    }

    override fun allConnections(): Collection<GateConnectionState> = states.values.toList()

    override fun findConnection(dtlIp: String): GateConnectionState? = states[dtlIp]

    override suspend fun closeConnection(dtlIp: String, updateNetState: Boolean) {
        states.remove(dtlIp)
    }

    override suspend fun closeConnectionIfCurrent(
        dtlIp: String,
        expected: GateConnectionState,
        updateNetState: Boolean,
    ): Boolean {
        val removed = states.remove(dtlIp, expected)
        return removed
    }

    override suspend fun sendToLane(dtlIp: String, dtlLaneNo: Int, packet: ByteArray): Boolean {
        sentCalls.add(Triple(dtlIp, dtlLaneNo, packet))
        return sendResult
    }

    override suspend fun sendToConnection(dtlIp: String, packet: ByteArray): Boolean {
        sentToConnectionCalls.add(dtlIp to packet)
        return sendResult
    }
}

/** 테스트에서 `private lateinit var` 필드에 값을 주입하기 위한 리플렉션 헬퍼. */
fun <T : Any> injectField(target: Any, fieldName: String, value: T) {
    val field = target.javaClass.getDeclaredField(fieldName)
    field.isAccessible = true
    field.set(target, value)
}

/**
 * `QuartzJobBean.execute(context)`는 `executeInternal` 호출 전에
 * `context.getScheduler().getContext()`로 `SchedulerContext`를 병합한다. 실제 Quartz 스케줄러 없이
 * 단위 테스트하려면 이 호출 경로가 NPE 없이 통과하도록 `Scheduler`/`SchedulerContext`까지 최소한으로
 * 스텁한 [JobExecutionContext] 목을 만들어야 한다.
 */
fun fakeJobExecutionContext(): JobExecutionContext {
    val scheduler = Mockito.mock(Scheduler::class.java)
    Mockito.`when`(scheduler.context).thenReturn(SchedulerContext())
    val context = Mockito.mock(JobExecutionContext::class.java)
    Mockito.`when`(context.scheduler).thenReturn(scheduler)
    return context
}
