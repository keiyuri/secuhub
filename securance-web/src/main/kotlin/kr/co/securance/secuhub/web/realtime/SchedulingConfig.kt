package kr.co.securance.secuhub.web.realtime

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler

/** [DashboardPushService]의 `@Scheduled` 폴링을 위해 필요. `securance-scheduler`(Quartz)와는
 * 별개 축이다 — 대시보드 push 폴링은 Quartz 잡으로 관리할 만큼 무겁지 않고, `securance-web`은
 * `securance-scheduler`에 의존하지 않는다(모듈 경계, 계획서 4절 결정 근거 참고). */
@Configuration
@EnableScheduling
class SchedulingConfig {

    /**
     * 코드 리뷰 지적 R-4(2026-08-20): `@EnableScheduling`만 있으면 Spring이 기본으로 스레드 1개짜리
     * 스케줄러를 만든다 — [DashboardPushService.pushSummary](대시보드 5쿼리)가 느려지면 3초 주기
     * 장애 알림([DashboardPushService.pushNewAlerts])까지 그만큼 밀린다(알림 지연이 요약 조회
     * 성능에 종속됨). 풀을 2로 나눠 두 폴링이 서로를 기다리지 않게 한다 — 같은 세션에 동시에
     * 쓰기 시도가 생길 수 있는데, 이는 [DashboardWebSocketHandler]가
     * `ConcurrentWebSocketSessionDecorator`로 이미 안전하게 처리한다.
     */
    @Bean
    fun taskScheduler(): TaskScheduler =
        ThreadPoolTaskScheduler().apply {
            poolSize = 2
            setThreadNamePrefix("dashboard-push-")
            setDaemon(true)
            initialize()
        }
}
