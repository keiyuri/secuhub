package kr.co.securance.secuhub.web.realtime

import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

/** [DashboardPushService]의 `@Scheduled` 폴링을 위해 필요. `securance-scheduler`(Quartz)와는
 * 별개 축이다 — 대시보드 push 폴링은 Quartz 잡으로 관리할 만큼 무겁지 않고, `securance-web`은
 * `securance-scheduler`에 의존하지 않는다(모듈 경계, 계획서 4절 결정 근거 참고). */
@Configuration
@EnableScheduling
class SchedulingConfig
