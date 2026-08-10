package kr.co.securance.secuhub.scheduler.job

import kr.co.securance.secuhub.server.control.GateControlDispatcher
import org.quartz.DisallowConcurrentExecution
import org.quartz.JobExecutionContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.scheduling.quartz.QuartzJobBean

/**
 * 제어 명령 전송 잡(계획서 3.6/5.5절) — 레거시 `ClsQuartzJobSendControl`에 대응한다.
 *
 * 실제 로직은 전부 [GateControlDispatcher]에 있고 이 잡은 주기적 호출만 담당한다.
 * 레거시는 DB 조회·소켓 전송·재시도 가드·장애 해제가 567줄짜리 Job 클래스 하나에 뒤엉켜 있어
 * 단위 테스트가 불가능했다 — 그래서 타임아웃/중복 전송 버그가 실장비에서만 드러났다.
 *
 * [DisallowConcurrentExecution]은 필수다. 폴링 주기(기본 1초)보다 한 사이클이 길어지면
 * 두 실행이 같은 `snd_id`를 동시에 집어 **같은 제어 명령이 물리적으로 두 번** 나갈 수 있다
 * (게이트 리셋·개폐 같은 비멱등 명령에서 치명적).
 */
@DisallowConcurrentExecution
class SendControlJob : QuartzJobBean() {

    @Autowired
    private lateinit var dispatcher: GateControlDispatcher

    private val logger = LoggerFactory.getLogger(SendControlJob::class.java)

    override fun executeInternal(context: JobExecutionContext) {
        try {
            dispatcher.dispatchPending()
        } catch (ex: Exception) {
            // 한 주기의 실패가 잡 자체를 멈추면 안 된다 — 다음 주기에 다시 시도한다.
            logger.error("제어 명령 전송 잡 실행 중 오류가 발생했습니다.", ex)
        }
    }
}
