package kr.co.securance.secuhub.scheduler.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * `securance.scheduler.*` 설정(계획서 3.6/3.7절).
 *
 * `securance.server.mode`(SERVER/CLIENT, [kr.co.securance.secuhub.server.config.ServerModeConfig] 참고)와는
 * 완전히 독립된 축이다 — 이 모듈은 오직 [kr.co.securance.secuhub.server.connection.GateConnectionRegistry]
 * 인터페이스만 보고 동작하며 연결 방향을 알지 못한다. (예전엔 여기서 `securance.control.dispatch-mode`도
 * 함께 언급했지만, 그 설정을 바인딩하는 코드가 없어 죽은 설정이었다 — 제거했다. ServerModeConfig.kt 참고.)
 */
@ConfigurationProperties(prefix = "securance.scheduler")
data class SchedulerProperties(
    /** 잡 내부 팬아웃 동시성 상한(레거시 `SemaphoreSlim(16)` 대응, 1,000+ 연결 규모에 맞춰 상향 가능). */
    val jobConcurrency: Int = 64,

    /** `NetCheckJob` 반복 주기(초). */
    val netCheckIntervalSeconds: Long = 5,

    /**
     * `ReqStatusJob`(상태 폴링) 반복 주기(초).
     * 레거시 ini의 `REQ_STATUS_INTERVAL` 기본값과 동일한 10초로 시작한다.
     *
     * `SendControlJob`의 폴링 주기/배치 크기는 여기가 아니라 `securance.control.*`
     * ([kr.co.securance.secuhub.server.control.ControlProperties])에서 관리한다 — 제어 명령
     * 디스패치 설정(재전송 가드·ACK 타임아웃 등)과 한곳에 모아두기 위함이다.
     */
    val reqStatusIntervalSeconds: Long = 10,
)
