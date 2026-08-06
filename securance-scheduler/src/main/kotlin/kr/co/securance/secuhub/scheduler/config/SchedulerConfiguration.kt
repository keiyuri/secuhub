package kr.co.securance.secuhub.scheduler.config

import kr.co.securance.secuhub.scheduler.job.NetCheckJob
import kr.co.securance.secuhub.scheduler.job.ReqStatusJob
import kr.co.securance.secuhub.scheduler.job.SendControlJob
import org.quartz.JobDetail
import org.quartz.SimpleTrigger
import org.quartz.Trigger
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
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
    fun sendControlJobDetail(): JobDetail =
        JobDetailFactoryBean().apply {
            setJobClass(SendControlJob::class.java)
            setName("sendControlJob")
            setDurability(true)
        }.also { it.afterPropertiesSet() }.`object`!!

    /** staggered start: netCheckJob(+15초)과 겹치지 않도록 +20초로 살짝 더 지연시킨다. */
    @Bean
    fun sendControlJobTrigger(sendControlJobDetail: JobDetail, properties: SchedulerProperties): Trigger =
        SimpleTriggerFactoryBean().apply {
            setJobDetail(sendControlJobDetail)
            setStartTime(Date(System.currentTimeMillis() + 20_000))
            setRepeatInterval(properties.sendControlIntervalSeconds * 1000)
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

    /** staggered start: netCheckJob(+15초)/sendControlJob(+20초)과 겹치지 않도록 +25초로 지연시킨다. */
    @Bean
    fun reqStatusJobTrigger(reqStatusJobDetail: JobDetail, properties: SchedulerProperties): Trigger =
        SimpleTriggerFactoryBean().apply {
            setJobDetail(reqStatusJobDetail)
            setStartTime(Date(System.currentTimeMillis() + 25_000))
            setRepeatInterval(properties.reqStatusIntervalSeconds * 1000)
            setRepeatCount(SimpleTrigger.REPEAT_INDEFINITELY)
            setMisfireInstruction(SimpleTrigger.MISFIRE_INSTRUCTION_RESCHEDULE_NEXT_WITH_REMAINING_COUNT)
        }.also { it.afterPropertiesSet() }.`object`!!
}
