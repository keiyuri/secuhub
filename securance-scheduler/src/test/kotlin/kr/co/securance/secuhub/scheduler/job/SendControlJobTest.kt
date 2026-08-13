package kr.co.securance.secuhub.scheduler.job

import kr.co.securance.secuhub.server.control.GateControlDispatcher
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import kotlin.test.Test

/**
 * [SendControlJob] 검증 — 이전까지 이 잡만 유일하게 전용 테스트가 없었다(2026-08-13 코드 리뷰
 * 지적, 다른 Quartz 잡들은 모두 [NetCheckJobTest] 등으로 커버됨). 로직 대부분은
 * [GateControlDispatcher]에 위임되어 이미 검증되어 있으므로, 여기서는 잡 자체의 책임 —
 * 매 실행마다 dispatcher를 정확히 한 번 호출하는지, 그리고 dispatcher가 예외를 던져도
 * KDoc이 명시한 대로 잡 실행 자체는 실패하지 않고 삼켜지는지 — 만 검증한다.
 */
class SendControlJobTest {

    private fun buildJob(dispatcher: GateControlDispatcher): SendControlJob {
        val job = SendControlJob()
        injectField(job, "dispatcher", dispatcher)
        return job
    }

    private val context = fakeJobExecutionContext()

    @Test
    fun `실행하면 dispatcher dispatchPending을 한 번 호출한다`() {
        val dispatcher = mock(GateControlDispatcher::class.java)
        val job = buildJob(dispatcher)

        job.execute(context)

        verify(dispatcher, times(1)).dispatchPending()
    }

    @Test
    fun `dispatcher가 예외를 던져도 잡 실행 자체는 실패하지 않는다`() {
        // DisallowConcurrentExecution인 잡이 예외로 죽으면 다음 트리거까지 재스케줄이 불확실해질
        // 수 있다 — 한 주기의 실패가 잡 자체를 멈추면 안 된다는 KDoc 의도를 회귀 방지한다.
        val dispatcher = mock(GateControlDispatcher::class.java)
        `when`(dispatcher.dispatchPending()).thenThrow(RuntimeException("소켓 전송 실패"))
        val job = buildJob(dispatcher)

        job.execute(context)

        verify(dispatcher, times(1)).dispatchPending()
    }
}
