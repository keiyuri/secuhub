package kr.co.securance.secuhub.scheduler.job

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kr.co.securance.secuhub.scheduler.config.SchedulerProperties
import kr.co.securance.secuhub.server.connection.GateConnectionRegistry
import org.quartz.DisallowConcurrentExecution
import org.quartz.JobExecutionContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.scheduling.quartz.QuartzJobBean

/**
 * 연결 생존 확인 잡(계획서 3.6절) — Quartz 잡 패턴의 완전한 예시 1개(계획서 7절 스캐폴드 항목).
 *
 * 레거시 `ClsQuartzJobNetCheck`(`Socket.Poll` 기반 생존 확인 + 상태 전이 시에만 DB 기록)에 대응한다.
 * 이 모듈은 [GateConnectionRegistry] 인터페이스만 알고, `securance-server`의 Netty/액터 내부
 * 구현은 전혀 참조하지 않는다(계획서 3.6절 모듈 경계 원칙).
 *
 * `SendControlJob`/`ReqStatusJob`도 동일한 구조(팬아웃 동시성 제한 + `GateConnectionRegistry`만 의존)로
 * 후속 추가한다 — 1차 스캐폴드는 이 잡 하나로 패턴을 증명한다(계획서 7절).
 */
@DisallowConcurrentExecution
class NetCheckJob : QuartzJobBean() {

    @Autowired
    private lateinit var registry: GateConnectionRegistry

    @Autowired
    private lateinit var properties: SchedulerProperties

    private val logger = LoggerFactory.getLogger(NetCheckJob::class.java)

    override fun executeInternal(context: JobExecutionContext) {
        val connections = registry.allConnections()
        if (connections.isEmpty()) return

        runBlocking(Dispatchers.IO) {
            val semaphore = Semaphore(properties.jobConcurrency.coerceAtLeast(1))
            connections.map { state ->
                async {
                    semaphore.withPermit {
                        if (!state.isChannelActive) {
                            logger.info("커넥션[{}] 채널이 닫혀 있어 정리합니다.", state.dtlIp)
                            // 재연결 레이스 가드(계획서 3.3절): registry가 이미 새 커넥션으로 교체했다면
                            // closeConnectionIfCurrent가 조회+제거를 원자적으로 수행해 아무 것도 하지
                            // 않는다 — findConnection()으로 먼저 확인하고 나중에 closeConnection()을
                            // 호출하는 두 단계 방식은 그 사이에 재연결이 끼어드는 레이스가 있었다.
                            registry.closeConnectionIfCurrent(state.dtlIp, state, updateNetState = true)
                        }
                    }
                }
            }.awaitAll()
        }
    }
}
