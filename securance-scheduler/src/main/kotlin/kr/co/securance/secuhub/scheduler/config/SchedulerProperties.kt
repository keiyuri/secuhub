package kr.co.securance.secuhub.scheduler.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.DateTimeException
import java.time.ZoneId

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

    /**
     * [retentionCron]을 해석할 타임존(코드 리뷰 지적, 2026-08-28) — 명시하지 않으면
     * `CronTriggerFactoryBean`이 JVM 기본 타임존을 쓰는데, 배포 환경(컨테이너/클라우드 VM 이미지)이
     * 시스템 타임존을 UTC로 두고 있으면 "새벽 3시(KST)"를 의도한 대량 삭제 배치가 실제로는
     * UTC 03:00 = KST 정오에 실행돼, 트래픽이 가장 많은 업무 시간대에 `tb_data_rcv`/`tb_gate_log`
     * 삭제 쿼리의 락 경합·DB 부하가 몰릴 수 있다. 이 시스템은 국내 배포 대상이므로 기본값을
     * KST(Asia/Seoul)로 고정한다.
     */
    val retentionTimeZone: String = "Asia/Seoul",

    /**
     * 큐 드롭 durable 재작성(2026-08-12, `docs/작업일지.md` 참고) — `OprStatusOutboxReplayJob` 자체를
     * 끌 수 있는 스위치. 꺼도 [OprStatusPersister]가 드롭/최종실패 시 outbox에 저장하는 동작 자체는
     * 계속되므로(안전장치는 유지), 재처리만 멈춘다.
     */
    val oprStatusOutboxReplayEnabled: Boolean = true,

    /** `OprStatusOutboxReplayJob` 반복 주기(초) — 저빈도 배치라 다른 잡들보다 넉넉하게 잡는다. */
    val oprStatusOutboxReplayIntervalSeconds: Long = 60,

    /** 한 번의 실행에서 재처리할 최대 outbox 행 수. */
    val oprStatusOutboxBatchSize: Int = 200,

    /**
     * 재처리가 몇 번 실패하면 자동 재시도를 포기하고 수동 확인 대상으로 남길지. 값을 넘겨도 행 자체는
     * 지우지 않는다(`processed=false`로 유지) — 운영자가 원인을 파악할 수 있도록 조회 가능한 상태로
     * 남겨두는 것이 목적이다.
     */
    val oprStatusOutboxMaxRetries: Int = 10,
) {
    init {
        // Opus 검증 리뷰 지적(2026-08-28): java.util.TimeZone.getTimeZone(id)는 알 수 없는 ID에
        // 대해 예외 없이 조용히 GMT를 반환한다 — 오타(예: "Asia/Seuol")를 내도 기동은 성공하고,
        // retentionTimeZone을 명시적으로 지정해 막으려던 "새벽 3시 의도가 어긋나는" 문제가 GMT라는
        // 다른 이름으로 재발한다. ZoneId.of()는 존재하지 않는 ID에 예외를 던지므로, 실제 스케줄링에
        // 쓰이는 TimeZone과는 별개로 여기서 검증만 수행해 기동 시점에 즉시 잡아낸다(ServerModeConfig의
        // 다른 fail-fast 검증들과 일관된 스타일).
        try {
            ZoneId.of(retentionTimeZone)
        } catch (ex: DateTimeException) {
            throw IllegalArgumentException(
                "securance.scheduler.retention-time-zone이 올바른 타임존 ID가 아닙니다: $retentionTimeZone",
                ex,
            )
        }
    }
}
