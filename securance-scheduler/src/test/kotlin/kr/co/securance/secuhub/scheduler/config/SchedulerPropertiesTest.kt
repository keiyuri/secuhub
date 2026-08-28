package kr.co.securance.secuhub.scheduler.config

import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * [SchedulerProperties] 기동 시점 검증 — Opus 검증 리뷰 지적(2026-08-28)에 대한 회귀 테스트.
 * `java.util.TimeZone.getTimeZone`은 알 수 없는 ID에도 예외 없이 GMT를 반환하므로, 오타를 잡으려면
 * `ZoneId.of()`로 별도 검증해야 한다.
 */
class SchedulerPropertiesTest {

    @Test
    fun `기본값은 정상 구성된다`() {
        SchedulerProperties()
    }

    @Test
    fun `retentionTimeZone이 알 수 없는 ID면 기동을 막는다`() {
        assertFailsWith<IllegalArgumentException> {
            SchedulerProperties(retentionTimeZone = "Asia/Seuol")
        }
    }

    @Test
    fun `retentionTimeZone에 UTC 오프셋도 허용된다`() {
        SchedulerProperties(retentionTimeZone = "+09:00")
    }
}
