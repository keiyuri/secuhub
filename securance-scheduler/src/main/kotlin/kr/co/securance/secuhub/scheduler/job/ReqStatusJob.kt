package kr.co.securance.secuhub.scheduler.job

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kr.co.securance.secuhub.scheduler.config.SchedulerProperties
import kr.co.securance.secuhub.server.connection.GateConnectionRegistry
import kr.co.securance.secuhub.server.connection.GateConnectionState
import org.quartz.DisallowConcurrentExecution
import org.quartz.JobExecutionContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.scheduling.quartz.QuartzJobBean

/**
 * 게이트 상태 능동 조회 잡 — 레거시 `ClsQuartzJobReqStatus`에 대응한다.
 *
 * `NetCheckJob`과 동일하게 [GateConnectionRegistry] 인터페이스만 알고, `GateConnectionActor`/Netty
 * 내부 구현은 직접 참조하지 않는다.
 *
 * [아키텍처 차이] 레거시는 이 잡에서 전송 성공 시 즉시 `tb_net_state`를 'Y'(온라인)로 갱신했다
 * (`UpdateNetStatusBatchAsync`). 이 코드베이스에서는 "요청 전송 성공"과 "온라인 확인"이 분리되어
 * 있다 — `tb_net_state` 갱신은 `DefaultGatePacketHandler`가 실제 `GATE_STATUS`(0x4D) 응답을
 * 수신했을 때만 수행한다(계획서 3.2/3.5절). 따라서 이 잡은 전송 성공/실패를 로그로만 남기면 되고,
 * 중복 DB 갱신을 할 필요가 없다 — 응답이 없는 죽은 연결은 `NetCheckJob`(채널 생존 확인)이 별도로
 * 정리한다.
 */
@DisallowConcurrentExecution
class ReqStatusJob : QuartzJobBean() {

    @Autowired
    private lateinit var registry: GateConnectionRegistry

    @Autowired
    private lateinit var properties: SchedulerProperties

    private val logger = LoggerFactory.getLogger(ReqStatusJob::class.java)

    override fun executeInternal(context: JobExecutionContext) {
        val connections = registry.allConnections()
        if (connections.isEmpty()) return

        runBlocking(Dispatchers.IO) {
            val semaphore = Semaphore(properties.jobConcurrency.coerceAtLeast(1))
            connections.map { state ->
                async {
                    semaphore.withPermit {
                        requestStatus(state)
                    }
                }
            }.awaitAll()
        }
    }

    private fun requestStatus(state: GateConnectionState) {
        try {
            // 상태 조회 패킷은 특정 레인이 아니라 커넥션(장치) 전체를 대상으로 한다 — 임의로 고른
            // "대표 레인"이 sendToLane의 소유권 검사(레인 집합이 authoritative하게 비어있는 특이
            // 케이스 등)에 걸려 영구적으로 거부될 수 있으므로, 레인 소유권과 무관한
            // sendToConnection을 쓴다.
            val packet = state.codec.buildStatusRequest()
            val sent = registry.sendToConnection(state.dtlIp, packet)

            if (sent) {
                logger.debug("[ReqStatus] 상태 조회 전송: ip={}", state.dtlIp)
            } else {
                logger.warn("[ReqStatus] 상태 조회 전송 실패(연결 없음/대기열 초과 등): ip={}", state.dtlIp)
            }
        } catch (ex: Exception) {
            // 커넥션 하나의 처리 실패가 다른 커넥션의 상태 조회에 영향을 주지 않도록 여기서 흡수한다.
            logger.error("[ReqStatus] 상태 조회 처리 중 오류: ip={}", state.dtlIp, ex)
        }
    }
}
