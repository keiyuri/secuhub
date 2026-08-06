package kr.co.securance.secuhub.scheduler.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * `securance.scheduler.*` 설정(계획서 3.6/3.7절).
 *
 * `securance.server.mode`(SERVER/CLIENT)나 `securance.control.dispatch-mode`(QUEUED/DIRECT)와는
 * 완전히 독립된 축이다 — 이 모듈은 오직 [kr.co.securance.secuhub.server.connection.GateConnectionRegistry]
 * 인터페이스만 보고 동작하며 연결 방향을 알지 못한다.
 */
@ConfigurationProperties(prefix = "securance.scheduler")
data class SchedulerProperties(
    /** 잡 내부 팬아웃 동시성 상한(레거시 `SemaphoreSlim(16)` 대응, 1,000+ 연결 규모에 맞춰 상향 가능). */
    val jobConcurrency: Int = 64,

    /** `NetCheckJob` 반복 주기(초). */
    val netCheckIntervalSeconds: Long = 5,

    /** `SendControlJob` 반복 주기(초) — 레거시 `INTERVAL_SEND_CONTROL`(ini 기본값 1초) 대응. */
    val sendControlIntervalSeconds: Long = 1,

    /** `ReqStatusJob` 반복 주기(초) — 레거시 `INTERVAL_REQ_STATUS`(ini 기본값 5초) 대응. */
    val reqStatusIntervalSeconds: Long = 5,
)
