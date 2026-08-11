package kr.co.securance.secuhub.web.realtime

import tools.jackson.databind.ObjectMapper
import kr.co.securance.secuhub.domain.entity.DataReceiveAnalysis
import kr.co.securance.secuhub.domain.repository.DataReceiveAnalysisRepository
import kr.co.securance.secuhub.web.dashboard.DashboardService
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.util.concurrent.atomic.AtomicLong

/**
 * #18 대시보드 실시간화 + #1 GateControl(장애 알림 팝업) + #17 Warning(화재 경고 팝업)의 공통
 * push 엔진. 계획서 4절 확정 결정("WebSocket + 폴링")에 따라 DB를 주기적으로 다시 읽어 변화를
 * 감지하고, [DashboardWebSocketHandler]로 브로드캐스트한다.
 *
 * 레거시는 게이트가 UDP로 직접 쏘는 상태 변화를 즉시 받았지만(계획서 3.1절), 여기서는 그 대신
 * "N초마다 DB를 다시 읽어 이전과 달라진 부분만 보낸다"는 더 단순한 모델을 쓴다 — `securance-web`이
 * `securance-server`(커넥션 액터)에 의존하지 않는 모듈 경계를 지키기 위한 의도적 절충이다.
 */
@Service
class DashboardPushService(
    private val dashboardService: DashboardService,
    private val analysisRepository: DataReceiveAnalysisRepository,
    private val webSocketHandler: DashboardWebSocketHandler,
    // Spring Boot 4.1부터 기본 JacksonAutoConfiguration이 Jackson 3(`tools.jackson`)의
    // JsonMapper/ObjectMapper만 빈으로 등록한다 — 이전에 Jackson 2(`com.fasterxml.jackson`)
    // ObjectMapper로 선언했을 때는 해당 타입의 빈이 없어 컨텍스트 기동 자체가 실패했다
    // (SecuranceApplicationTests가 잡아낸 문제, Opus 리뷰의 "컨텍스트 로드 테스트 부재" 지적).
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 시작 시점 이후 새로 생긴 오류만 팝업으로 띄운다 — 시작 전부터 있던 미해결 오류까지 전부
    // 팝업으로 쏟아내면(대시보드 위젯에는 이미 나와 있는데도) 첫 접속자가 알림 폭탄을 맞는다.
    private val lastSeenAnalId = AtomicLong(-1)

    @Scheduled(fixedDelay = SUMMARY_INTERVAL_MS)
    fun pushSummary() {
        runCatching {
            val view = dashboardService.loadDashboard()
            val payload = mapOf(
                "type" to "SUMMARY",
                "summary" to view.summary,
                "recentErrors" to view.recentErrors,
            )
            webSocketHandler.broadcast(objectMapper.writeValueAsString(payload))
        }.onFailure { log.warn("대시보드 요약 push 실패", it) }
    }

    @Scheduled(fixedDelay = ALERT_POLL_INTERVAL_MS)
    fun pushNewAlerts() {
        runCatching {
            val baselineId = lastSeenAnalId.get()
            if (baselineId < 0) {
                // 최초 1회는 팝업을 쏘지 않고 현재 최대 analId로 기준선만 맞춘다.
                initializeBaseline()
                return@runCatching
            }
            val newErrors = analysisRepository.findAll(newUnresolvedErrorsSpec(baselineId), Sort.by(Sort.Direction.ASC, "analId"))
            if (newErrors.isEmpty()) return@runCatching

            newErrors.forEach { error ->
                val payload = mapOf(
                    "type" to "ALERT",
                    // 버그 수정(2026-08-11, B2): descFireAlarm 등은 DB 컬럼 기본값이 빈 문자열인
                    // non-null String이라 `!= null`은 항상 참이었다 — 실제 내용 유무는 isNotBlank()로
                    // 판정해야 한다. 이 오류로 모터 장애까지 전부 "화재 경고"로 표시되고 있었다.
                    "alertType" to if (error.descFireAlarm.isNotBlank()) "FIRE" else "FAULT",
                    "dtlIp" to error.dtlIp,
                    // 리셋 버튼(#1/#17 팝업)이 /api/gate-control/reset 호출에 필요로 한다(2026-08-11 B3).
                    "dtlLaneNo" to error.dtlLaneNo,
                    "description" to alertDescription(error),
                    "analDate" to error.analDate,
                )
                webSocketHandler.broadcast(objectMapper.writeValueAsString(payload))
            }
            lastSeenAnalId.set(newErrors.maxOf { requireNotNull(it.analId) })
        }.onFailure { log.warn("실시간 알림 push 실패", it) }
    }

    private fun initializeBaseline() {
        val currentMax = analysisRepository.findAll(
            newUnresolvedErrorsSpec(sinceExclusive = -1),
            Sort.by(Sort.Direction.DESC, "analId"),
        ).firstOrNull()?.analId ?: 0L
        lastSeenAnalId.set(currentMax)
    }

    private fun alertDescription(error: DataReceiveAnalysis): String =
        // 위와 동일한 이유로 listOfNotNull은 걸러내는 게 없었다(전부 non-null) — 실제 값이 채워진
        // 항목만 고르도록 isNotBlank()로 필터링한다.
        listOf(error.descFireAlarm, error.descMainMotorError, error.descSlaveMotorError)
            .firstOrNull { it.isNotBlank() } ?: "오류 상세 미확인"

    /** [kr.co.securance.secuhub.web.dashboard.DashboardService]/[DataReceiveAnalysisRepository.findRecentUnresolvedErrors]
     * 의 미해결 오류 조건과 동일(errType=3, hasErrorEvent=true, resolveYn='N', analType IN ('PLM','STA')) + analId 하한.
     *
     * Opus 전체 리뷰 지적: 예전에는 analType 조건이 빠져 있었다 — 대시보드 위젯(개수/목록)에는 절대
     * 나타나지 않는 analType의 오류가 실시간 팝업으로는 튀어나오는 불일치가 있었다. */
    private fun newUnresolvedErrorsSpec(sinceExclusive: Long): Specification<DataReceiveAnalysis> =
        Specification { root, _, cb ->
            cb.and(
                cb.greaterThan(root.get("analId"), sinceExclusive),
                cb.equal(root.get<Int>("errType"), 3),
                cb.equal(root.get<Boolean>("hasErrorEvent"), true),
                cb.equal(root.get<String>("resolveYn"), "N"),
                root.get<String>("analType").`in`("PLM", "STA"),
            )
        }

    private companion object {
        const val SUMMARY_INTERVAL_MS = 5_000L
        const val ALERT_POLL_INTERVAL_MS = 3_000L
    }
}
