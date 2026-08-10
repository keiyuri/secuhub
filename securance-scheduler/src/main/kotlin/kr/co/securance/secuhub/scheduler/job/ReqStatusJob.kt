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
 * 상태 폴링 잡(계획서 3.6절) — 레거시 `ClsQuartzJobReqStatus`에 대응한다.
 *
 * 연결된 모든 게이트에 상태 조회(+시간 동기화) 요청 패킷을 주기적으로 보낸다. 장비는 이에
 * `GATE_STATUS`(0x4D) 패킷으로 응답하고, 그 응답 처리
 * ([kr.co.securance.secuhub.server.tcp.DefaultGatePacketHandler])가 레인 집합 갱신과
 * `tb_net_state` 온라인 기록을 담당한다.
 *
 * **`tb_net_state`를 이 잡에서 직접 갱신하지 않는 이유**: 레거시는 전송 성공만으로 해당 IP의
 * 레인들을 온라인으로 배치 기록했는데(M-7), 다중 레인 소켓에서는 TCP 연결이 살아있어도 개별
 * 레인은 끊길 수 있어 0x4D 분석이 정확히 남긴 'N'을 즉시 덮어쓰는 이중 기록 경로 충돌이
 * 있었다. 여기서는 온라인 기록 경로를 0x4D 응답 처리 하나로 일원화한다.
 *
 * [NetCheckJob]과 동일한 구조(팬아웃 동시성 제한 + [GateConnectionRegistry]만 의존)를 따른다.
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
                    semaphore.withPermit { requestStatus(state) }
                }
            }.awaitAll()
        }
    }

    private suspend fun requestStatus(state: GateConnectionState) {
        // 채널이 죽어 있으면 상태 요청 대신 정리한다 — 재연결 레이스 가드(계획서 3.3절):
        // registry가 이미 같은 IP의 새 커넥션으로 교체했다면 이 오래된 커넥션의 오프라인
        // 기록이 살아있는 새 연결의 온라인 상태를 덮어쓰지 않도록 건너뛴다.
        if (!state.isChannelActive) {
            if (registry.findConnection(state.dtlIp) === state) {
                logger.info("커넥션[{}] 채널이 닫혀 있어 상태 요청 대신 정리합니다.", state.dtlIp)
                registry.closeConnection(state.dtlIp, updateNetState = true)
            } else {
                logger.debug("커넥션[{}] 재접속 감지 — 오래된 커넥션의 정리를 건너뜁니다.", state.dtlIp)
            }
            return
        }

        val packet = try {
            state.codec.buildStatusRequest(state.codec.defaultAddress)
        } catch (ex: IllegalArgumentException) {
            logger.error("커넥션[{}] 상태 요청 패킷 생성 실패", state.dtlIp, ex)
            return
        }

        // 소켓이 여러 레인을 실어나르더라도 물리 소켓은 하나이므로 전송도 1회면 충분하다.
        // 아직 0x4D를 한 번도 못 받아 레인 집합이 비어 있으면 기본 레인(1)로 보낸다 —
        // 이 시점에는 hasAuthoritativeLaneInfo가 false이므로 레인 소유 검사에 걸리지 않는다.
        val laneNo = state.laneSnapshot().minOrNull() ?: 1
        // trackForAck=false: 상태 요청은 임의로 고른 대표 레인으로 나가고 ACK 상관관계가 필요
        // 없다. 여기서 기록하면 뒤이어 도착하는 제어 명령 ACK가 이 폴링이 고른 레인으로 잘못
        // 귀속될 수 있다(Codex 어드버서리얼 리뷰 대응 — GateConnectionRegistry.sendToLane 참고).
        if (!registry.sendToLane(state.dtlIp, laneNo, packet, trackForAck = false)) {
            // 전송이 등록조차 되지 않았다(대기열 포화/레인 불일치). 소켓 자체의 오류가 아니므로
            // 커넥션을 끊지 않고 다음 폴링 주기에 재시도한다(레거시 ChainRejectedException 처리와 동일).
            logger.warn("커넥션[{}] 상태 요청 전송 보류: lane={}", state.dtlIp, laneNo)
        }
    }
}
