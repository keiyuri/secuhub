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
import java.util.Date

@Configuration
@EnableConfigurationProperties(SchedulerProperties::class)
class SchedulerConfiguration {

    @Bean
    fun netCheckJobDetail(): JobDetail =
        JobDetailFactoryBean().apply {
            setJobClass(NetCheckJob::class.java)
            setName("netCheckJob")
            setDurability(true)
        }.also { it.afterPropertiesSet() }.`object`!!

    /**
     * 잡 시작을 살짝 지연시켜(+15초) 애플리케이션 기동 직후 커넥션이 아직 없을 때 몰리는 것을 피한다
     * (레거시 Quartz 잡들의 "staggered start" 관례, 계획서 3.6절).
     */
    @Bean
    fun netCheckJobTrigger(netCheckJobDetail: JobDetail, properties: SchedulerProperties): Trigger =
        SimpleTriggerFactoryBean().apply {
            setJobDetail(netCheckJobDetail)
            setStartTime(Date(System.currentTimeMillis() + 15_000))
            setRepeatInterval(properties.netCheckIntervalSeconds * 1000)
            setRepeatCount(SimpleTrigger.REPEAT_INDEFINITELY)
            setMisfireInstruction(SimpleTrigger.MISFIRE_INSTRUCTION_RESCHEDULE_NEXT_WITH_REMAINING_COUNT)
        }.also { it.afterPropertiesSet() }.`object`!!

    @Bean
    fun reqStatusJobDetail(): JobDetail =
        JobDetailFactoryBean().apply {
            setJobClass(ReqStatusJob::class.java)
            setName("reqStatusJob")
            setDurability(true)
        }.also { it.afterPropertiesSet() }.`object`!!

    /**
     * `NetCheckJob`(+15초)보다 조금 더 늦게(+20초) 시작해 기동 직후 두 잡이 같은 커넥션 집합에
     * 동시에 몰리는 것을 피한다(레거시 Quartz 잡들의 "staggered start" 관례, 계획서 3.6절).
     */
    @Bean
    fun reqStatusJobTrigger(reqStatusJobDetail: JobDetail, properties: SchedulerProperties): Trigger =
        SimpleTriggerFactoryBean().apply {
            setJobDetail(reqStatusJobDetail)
            setStartTime(Date(System.currentTimeMillis() + 20_000))
            setRepeatInterval(properties.reqStatusIntervalSeconds * 1000)
            setRepeatCount(SimpleTrigger.REPEAT_INDEFINITELY)
            setMisfireInstruction(SimpleTrigger.MISFIRE_INSTRUCTION_RESCHEDULE_NEXT_WITH_REMAINING_COUNT)
        }.also { it.afterPropertiesSet() }.`object`!!

    // ── 제어 명령 전송 잡(QUEUED 모드 전용) ──────────────────────────

    @Bean
    @ConditionalOnProperty(
        prefix = "securance.control",
        name = ["dispatch-mode"],
        havingValue = "QUEUED",
        matchIfMissing = true,
    )
    fun sendControlJobDetail(): JobDetail =
        JobDetailFactoryBean().apply {
            setJobClass(SendControlJob::class.java)
            setName("sendControlJob")
            setDurability(true)
        }.also { it.afterPropertiesSet() }.`object`!!

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
        SimpleTriggerFactoryBean().apply {
            setJobDetail(sendControlJobDetail)
            setStartTime(Date(System.currentTimeMillis() + 5_000))
            setRepeatInterval(properties.pollIntervalSeconds.coerceAtLeast(1) * 1000)
            setRepeatCount(SimpleTrigger.REPEAT_INDEFINITELY)
            // 폴링이 밀렸을 때 밀린 횟수만큼 몰아서 실행하면 같은 명령을 반복 조회하게 되므로,
            // 다음 정상 시각으로 재조정만 하고 지나간 실행은 버린다.
            setMisfireInstruction(SimpleTrigger.MISFIRE_INSTRUCTION_RESCHEDULE_NEXT_WITH_REMAINING_COUNT)
        }.also { it.afterPropertiesSet() }.`object`!!

    // ── D5 데이터 보관 정리 잡 ──────────────────────────────────────

    @Bean
    @ConditionalOnProperty(
        prefix = "securance.scheduler",
        name = ["retention-enabled"],
        havingValue = "true",
        matchIfMissing = true,
    )
    fun retentionCleanupJobDetail(): JobDetail =
        JobDetailFactoryBean().apply {
            setJobClass(RetentionCleanupJob::class.java)
            setName("retentionCleanupJob")
            setDurability(true)
        }.also { it.afterPropertiesSet() }.`object`!!

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
    fun oprStatusOutboxReplayJobDetail(): JobDetail =
        JobDetailFactoryBean().apply {
            setJobClass(OprStatusOutboxReplayJob::class.java)
            setName("oprStatusOutboxReplayJob")
            setDurability(true)
        }.also { it.afterPropertiesSet() }.`object`!!

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
        SimpleTriggerFactoryBean().apply {
            setJobDetail(oprStatusOutboxReplayJobDetail)
            setStartTime(Date(System.currentTimeMillis() + 30_000))
            setRepeatInterval(properties.oprStatusOutboxReplayIntervalSeconds * 1000)
            setRepeatCount(SimpleTrigger.REPEAT_INDEFINITELY)
            setMisfireInstruction(SimpleTrigger.MISFIRE_INSTRUCTION_RESCHEDULE_NEXT_WITH_REMAINING_COUNT)
        }.also { it.afterPropertiesSet() }.`object`!!
}
