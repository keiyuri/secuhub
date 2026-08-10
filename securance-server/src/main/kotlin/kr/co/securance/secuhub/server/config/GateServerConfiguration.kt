package kr.co.securance.secuhub.server.config

import kr.co.securance.secuhub.protocol.GateProtocolCodecRegistry
import kr.co.securance.secuhub.protocol.SpeedFlapGateProtocolCodec
import kr.co.securance.secuhub.server.control.ControlProperties
import kr.co.securance.secuhub.server.db.GateDbWriteQueue
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration
@EnableConfigurationProperties(ServerModeConfig::class, ControlProperties::class)
class GateServerConfiguration {

    /**
     * 게이트 타입 → 프로토콜 코덱 레지스트리(계획서 3.4절).
     * Turn/Fast Gate 코덱이 준비되면 이 리스트에 구현체만 추가하면 된다.
     */
    @Bean
    fun gateProtocolCodecRegistry(): GateProtocolCodecRegistry =
        GateProtocolCodecRegistry(listOf(SpeedFlapGateProtocolCodec()))

    @Bean(destroyMethod = "shutdown")
    fun gateDbWriteQueue(config: ServerModeConfig): GateDbWriteQueue =
        GateDbWriteQueue(shardCount = config.dbWriterShards)

    /**
     * 시각 제공자.
     *
     * [kr.co.securance.secuhub.server.control.GateControlDispatcher]가 쿨다운/ACK 타임아웃을
     * 계산할 때 `Instant.now()`를 직접 부르지 않고 이 빈을 쓰도록 해, 테스트에서 시간을
     * 결정적으로 진행시킬 수 있게 한다. 애플리케이션이 이미 `Clock` 빈을 정의했다면
     * 그것을 그대로 쓴다.
     */
    @Bean
    @ConditionalOnMissingBean(Clock::class)
    fun systemClock(): Clock = Clock.systemDefaultZone()
}
