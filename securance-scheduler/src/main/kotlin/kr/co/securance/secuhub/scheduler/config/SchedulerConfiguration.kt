package kr.co.securance.secuhub.scheduler.config

import kr.co.securance.secuhub.scheduler.job.NetCheckJob
import kr.co.securance.secuhub.scheduler.job.OprStatusOutboxReplayJob
import kr.co.securance.secuhub.scheduler.job.ReqStatusJob
import kr.co.securance.secuhub.scheduler.job.RetentionCleanupJob
import kr.co.securance.secuhub.scheduler.job.SendControlJob
import kr.co.securance.secuhub.server.control.ControlProperties
import org.quartz.CronTrigger
import org.quartz.JobDetail
import org.quartz.SimpleTrigger
import org.quartz.Trigger
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.quartz.CronTriggerFactoryBean
import org.springframework.scheduling.quartz.JobDetailFactoryBean
import org.springframework.scheduling.quartz.SimpleTriggerFactoryBean
import java.time.ZoneId
import java.util.Date
import java.util.TimeZone

@Configuration
@EnableConfigurationProperties(SchedulerProperties::class)
class SchedulerConfiguration {

    /**
     * `JobDetailFactoryBean` 보일러플레이트 추출(코드 리뷰 지적, 2026-08-28) — 아래 5개 잡이
     * `setJobClass`/`setName`/`setDurability(true)`만 다른 값으로 거의 동일하게 반복하던 것을
     * 하나로 모은다. 잡 이름을 잘못된 잡 클래스에 연결하는 복붙 실수 위험도 줄어든다.
     */
    private fun jobDetail(jobClass: Class<out org.quartz.Job>, name: String): JobDetail =
        JobDetailFactoryBean().apply {
            setJobClass(jobClass)
            setName(name)
            setDurability(true)
        }.also { it.afterPropertiesSet() }.`object`!!

    /** `SimpleTriggerFactoryBean` 보일러플레이트 추출 — 시작 지연/반복 주기만 다른 4개 트리거가 대상. */
    private fun simpleTrigger(jobDetail: JobDetail, startDelayMillis: Long, repeatIntervalMillis: Long): Trigger =
        SimpleTriggerFactoryBean().apply {
            setJobDetail(jobDetail)
            setStartTime(Date(System.currentTimeMillis() + startDelayMillis))
            setRepeatInterval(repeatIntervalMillis)
            setRepeatCount(SimpleTrigger.REPEAT_INDEFINITELY)
            setMisfireInstruction(SimpleTrigger.MISFIRE_INSTRUCTION_RESCHEDULE_NEXT_WITH_REMAINING_COUNT)
        }.also { it.afterPropertiesSet() }.`object`!!

    @Bean
    fun netCheckJobDetail(): JobDetail = jobDetail(NetCheckJob::class.java, "netCheckJob")

    /**
     * 잡 시작을 살짝 지연시켜(+15초) 애플리케이션 기동 직후 커넥션이 아직 없을 때 몰리는 것을 피한다
     * (레거시 Quartz 잡들의 "staggered start" 관례, 계획서 3.6절).
     */
    @Bean
    fun netCheckJobTrigger(netCheckJobDetail: JobDetail, properties: SchedulerProperties): Trigger =
        simpleTrigger(netCheckJobDetail, startDelayMillis = 15_000, repeatIntervalMillis = properties.netCheckIntervalSeconds * 1000)

    @Bean
    fun reqStatusJobDetail(): JobDetail = jobDetail(ReqStatusJob::class.java, "reqStatusJob")

    /**
     * `NetCheckJob`(+15초)보다 조금 더 늦게(+20초) 시작해 기동 직후 두 잡이 같은 커넥션 집합에
     * 동시에 몰리는 것을 피한다(레거시 Quartz 잡들의 "staggered start" 관례, 계획서 3.6절).
     */
    @Bean
    fun reqStatusJobTrigger(reqStatusJobDetail: JobDetail, properties: SchedulerProperties): Trigger =
        simpleTrigger(reqStatusJobDetail, startDelayMillis = 20_000, repeatIntervalMillis = properties.reqStatusIntervalSeconds * 1000)

    // ── 제어 명령 전송 잡(QUEUED 모드 전용) ──────────────────────────

    @Bean
    @ConditionalOnProperty(
        prefix = "securance.control",
        name = ["dispatch-mode"],
        havingValue = "QUEUED",
        matchIfMissing = true,
    )
    fun sendControlJobDetail(): JobDetail = jobDetail(SendControlJob::class.java, "sendControlJob")

    /**
     * 제어 명령 폴링 트리거. 다른 잡들과 달리 시작 지연을 짧게(+5초) 둔다 — 운영자가 누른 제어
     * 명령이 기동 직후에도 최대한 빨리 나가야 하기 때문이다.
     *
     * DIRECT 모드에서는 [GateControlDispatcher]가 쓰이지 않으므로 이 잡을 등록하지 않는다
     * (`@ConditionalOnProperty`). 등록해도 `tb_data_snd`에 대기 행이 없어 무해하지만,
     * 매초 불필요한 DB 폴링이 돌게 된다.
     */
    @Bean
    @ConditionalOnProperty(
        prefix = "securance.control",
        name = ["dispatch-mode"],
        havingValue = "QUEUED",
        matchIfMissing = true,
    )
    fun sendControlJobTrigger(sendControlJobDetail: JobDetail, properties: ControlProperties): Trigger =
        // 폴링이 밀렸을 때 밀린 횟수만큼 몰아서 실행하면 같은 명령을 반복 조회하게 되므로,
        // 다음 정상 시각으로 재조정만 하고 지나간 실행은 버린다(simpleTrigger의 공통 정책).
        simpleTrigger(
            sendControlJobDetail,
            startDelayMillis = 5_000,
            repeatIntervalMillis = properties.pollIntervalSeconds.coerceAtLeast(1) * 1000,
        )

    // ── D5 데이터 보관 정리 잡 ──────────────────────────────────────

    @Bean
    @ConditionalOnProperty(
        prefix = "securance.scheduler",
        name = ["retention-enabled"],
        havingValue = "true",
        matchIfMissing = true,
    )
    fun retentionCleanupJobDetail(): JobDetail = jobDetail(RetentionCleanupJob::class.java, "retentionCleanupJob")

    /**
     * 다른 잡들과 달리 매초/매십초 반복이 아니라 하루 한 번 도는 cron 트리거를 쓴다
     * (기본값 `securance.scheduler.retention-cron` = 매일 03:00) — 대량 삭제라 트래픽이 적은
     * 새벽 시간대에 몰아서 실행하는 편이 낫다.
     */
    @Bean
    @ConditionalOnProperty(
        prefix = "securance.scheduler",
        name = ["retention-enabled"],
        havingValue = "true",
        matchIfMissing = true,
    )
    fun retentionCleanupJobTrigger(retentionCleanupJobDetail: JobDetail, properties: SchedulerProperties): Trigger =
        CronTriggerFactoryBean().apply {
            setJobDetail(retentionCleanupJobDetail)
            setCronExpression(properties.retentionCron)
            // 코드 리뷰 지적(2026-08-28): 명시하지 않으면 JVM 기본 타임존을 쓴다 — 배포 환경이
            // UTC라면 "새벽 3시"가 실제로는 KST 정오에 실행돼 트래픽이 몰리는 시간대에 대량 삭제가
            // 돈다. SchedulerProperties.retentionTimeZone(기본 Asia/Seoul) KDoc 참고.
            //
            // Opus 재검증 지적(2026-08-28): TimeZone.getTimeZone(String) 오버로드는 인식 못 하는
            // ID(예: 오프셋 표기 "+09:00")를 조용히 "GMT"로 대체한다 — SchedulerProperties의 init
            // 검증(ZoneId.of)은 이런 값도 통과시키므로, 검증은 통과했는데 실제로는 GMT로 도는 불일치가
            // 생길 수 있었다. ZoneId를 거쳐 TimeZone.getTimeZone(ZoneId) 오버로드를 쓰면 같은 문자열을
            // 검증(ZoneId.of)과 적용 양쪽에서 동일하게 해석해 이 불일치를 없앤다.
            setTimeZone(TimeZone.getTimeZone(ZoneId.of(properties.retentionTimeZone)))
            setMisfireInstruction(CronTrigger.MISFIRE_INSTRUCTION_DO_NOTHING)
        }.also { it.afterPropertiesSet() }.`object`!!

    // ── 큐 드롭 durable 재작성: outbox 재처리 잡 ──────────────────────

    @Bean
    @ConditionalOnProperty(
        prefix = "securance.scheduler",
        name = ["opr-status-outbox-replay-enabled"],
        havingValue = "true",
        matchIfMissing = true,
    )
    fun oprStatusOutboxReplayJobDetail(): JobDetail = jobDetail(OprStatusOutboxReplayJob::class.java, "oprStatusOutboxReplayJob")

    /**
     * 다른 상시 폴링 잡들(+5~20초)보다 늦게(+30초) 시작한다 — 이 잡은 드물게(드롭/최종실패 시에만)
     * 쌓이는 outbox를 훑는 저빈도 배치라 기동 직후 지연에 민감하지 않다.
     */
    @Bean
    @ConditionalOnProperty(
        prefix = "securance.scheduler",
        name = ["opr-status-outbox-replay-enabled"],
        havingValue = "true",
        matchIfMissing = true,
    )
    fun oprStatusOutboxReplayJobTrigger(
        oprStatusOutboxReplayJobDetail: JobDetail,
        properties: SchedulerProperties,
    ): Trigger =
        simpleTrigger(
            oprStatusOutboxReplayJobDetail,
            startDelayMillis = 30_000,
            repeatIntervalMillis = properties.oprStatusOutboxReplayIntervalSeconds * 1000,
        )
}
