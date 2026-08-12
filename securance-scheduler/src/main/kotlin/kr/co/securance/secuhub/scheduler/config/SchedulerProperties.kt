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

    /**
     * D5 데이터 보관 정책(2026-08-12, `docs/작업일지.md` 참고) — `RetentionCleanupJob`이 삭제
     * 대상으로 삼는 보관 기간(일). 사용자 결정: 365일(회계연도/연간 감사 대응).
     */
    val retentionDays: Long = 365,

    /** 보관 정책 잡 자체를 끌 수 있는 스위치(운영 중 긴급 비활성화용). */
    val retentionEnabled: Boolean = true,

    /**
     * 한 번의 삭제 쿼리(`DELETE ... LIMIT`)가 지우는 최대 행 수. 대상 테이블이 수백만 건 규모일 수
     * 있어, 한 트랜잭션에서 전부 지우면 락을 오래 쥐게 되므로 작은 배치로 나눠 반복 삭제한다.
     */
    val retentionBatchSize: Int = 5_000,

    /**
     * 한 번의 잡 실행(`RetentionCleanupJob.executeInternal`)에서 테이블당 반복할 최대 배치 수.
     * 컷오프보다 오래된 행이 이 상한보다 많으면 남은 행은 다음 실행(다음날)에 마저 지운다 —
     * 무한 루프 방지 및 스케줄러 스레드 장시간 점유 방지.
     */
    val retentionMaxBatchesPerRun: Int = 200,

    /**
     * 보관 정리 잡 실행 시각(Quartz cron 표현식). 트래픽이 가장 적을 새벽 시간대 기본값.
     */
    val retentionCron: String = "0 0 3 * * ?",
)
