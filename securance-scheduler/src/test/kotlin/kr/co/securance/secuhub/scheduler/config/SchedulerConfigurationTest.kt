package kr.co.securance.secuhub.scheduler.config

import kr.co.securance.secuhub.scheduler.job.NetCheckJob
import kr.co.securance.secuhub.scheduler.job.ReqStatusJob
import org.quartz.SimpleTrigger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Quartz 잡 등록 설정 검증(1차 스프린트 3번 항목 — `ReqStatusJob`).
 *
 * 잡 클래스/주기/반복 설정이 실수로 빠지거나 다른 잡을 가리키면 상태 폴링이 통째로 멈추는데,
 * 애플리케이션을 띄우기 전에는 드러나지 않는다. 설정 수준에서 고정해 둔다.
 */
class SchedulerConfigurationTest {

    private val configuration = SchedulerConfiguration()

    @Test
    fun `ReqStatusJob이 durable JobDetail로 등록된다`() {
        val detail = configuration.reqStatusJobDetail()

        assertEquals(ReqStatusJob::class.java, detail.jobClass)
        assertEquals("reqStatusJob", detail.key.name)
        assertTrue(detail.isDurable)
    }

    @Test
    fun `ReqStatusJob 트리거는 설정된 주기로 무한 반복한다`() {
        val properties = SchedulerProperties(reqStatusIntervalSeconds = 7)
        val trigger = configuration.reqStatusJobTrigger(configuration.reqStatusJobDetail(), properties)
            as SimpleTrigger

        assertEquals(7_000L, trigger.repeatInterval)
        assertEquals(SimpleTrigger.REPEAT_INDEFINITELY, trigger.repeatCount)
    }

    @Test
    fun `NetCheckJob보다 늦게 시작해 기동 직후 잡이 겹치지 않게 한다`() {
        val properties = SchedulerProperties()
        val netCheck = configuration.netCheckJobTrigger(configuration.netCheckJobDetail(), properties) as SimpleTrigger
        val reqStatus = configuration.reqStatusJobTrigger(configuration.reqStatusJobDetail(), properties) as SimpleTrigger

        assertTrue(
            reqStatus.startTime.after(netCheck.startTime),
            "ReqStatusJob은 NetCheckJob보다 나중에 시작해야 한다(staggered start)",
        )
    }

    @Test
    fun `기본 폴링 주기는 레거시와 동일한 10초다`() {
        assertEquals(10L, SchedulerProperties().reqStatusIntervalSeconds)
        assertEquals(NetCheckJob::class.java, configuration.netCheckJobDetail().jobClass)
    }
}
