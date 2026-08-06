package kr.co.securance.secuhub.server.config

import kr.co.securance.secuhub.protocol.GateProtocolCodecRegistry
import kr.co.securance.secuhub.protocol.SpeedFlapGateProtocolCodec
import kr.co.securance.secuhub.server.db.GateDbWriteQueue
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(ServerModeConfig::class, LocationProperties::class, CloudProperties::class)
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
}
