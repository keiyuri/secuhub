package kr.co.securance.secuhub.web.realtime

import kr.co.securance.secuhub.domain.entity.DataReceiveAnalysis
import kr.co.securance.secuhub.domain.repository.DataReceiveAnalysisRepository
import kr.co.securance.secuhub.web.dashboard.DashboardService
import kr.co.securance.secuhub.web.dashboard.DashboardSummary
import kr.co.securance.secuhub.web.dashboard.DashboardView
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.data.domain.Pageable
import tools.jackson.databind.ObjectMapper
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Kotlin non-null 파라미터에 [Mockito.any]를 직접 쓰면 NPE가 나는 문제를 우회하는 표준 헬퍼.
 * ([kr.co.securance.secuhub.web.dashboard.DashboardServiceTest]와 동일한 패턴)
 */
private fun <T> anyKt(): T {
    Mockito.any<T>()
    @Suppress("UNCHECKED_CAST")
    return null as T
}

/**
 * [DashboardWebSocketHandler.broadcast]처럼 Kotlin에서 선언된 non-null 파라미터는
 * `ArgumentCaptor.capture()`의 반환값을 직접 넘기면 컴파일러가 삽입한 null 체크에 걸려 NPE가
 * 난다(이 저장소의 기존 캡처 사용례는 전부 Java로 선언된 `JpaRepository.save()`뿐이라 이 문제를
 * 겪지 않았다). `doAnswer`로 실제 호출 인자를 직접 리스트에 모아 우회한다.
 */
private fun captureBroadcasts(webSocketHandler: DashboardWebSocketHandler): MutableList<String> {
    val captured = mutableListOf<String>()
    doAnswer { invocation ->
        captured.add(invocation.getArgument(0))
        null
    }.`when`(webSocketHandler).broadcast(anyString())
    return captured
}

/**
 * 회귀 방지 테스트(2026-08-20 Opus 전체 리뷰 지적) — [DashboardPushService]의 `lastSeenAnalId`
 * 기준선 초기화/전진 로직은 과거 실제 장애(컬럼 오참조 `anal_type` vs `anal_tp`, 2026-08-14
 * `/codex:adversarial-review` 지적 — 실시간 알림이 통째로 죽고 소켓 타임아웃까지 유발)를 낸 적이
 * 있는데도 회귀 테스트가 없었다. 여기서는 리포지토리/웹소켓 핸들러를 모킹해 순수 로직만 검증한다.
 */
class DashboardPushServiceTest {

    private fun analysis(
        analId: Long,
        descFireAlarm: String = "",
        descMainMotorError: String = "",
    ) = DataReceiveAnalysis(
        analId = analId,
        analDate = "202608200900",
        analTp = "STA",
        dtlIp = "192.168.0.205",
        dtlLaneNo = 1,
        rcvDate = "202608200900",
    ).apply {
        descGateStatus07 = descFireAlarm // descFireAlarm getter가 참조하는 실제 컬럼
        descGateStatus10 = descMainMotorError // descMainMotorError getter가 참조하는 실제 컬럼
    }

    private fun newService(
        analysisRepository: DataReceiveAnalysisRepository,
        webSocketHandler: DashboardWebSocketHandler = mock(DashboardWebSocketHandler::class.java),
    ): Pair<DashboardPushService, DashboardWebSocketHandler> {
        val dashboardService = mock(DashboardService::class.java)
        `when`(dashboardService.loadDashboard()).thenReturn(
            DashboardView(summary = mock(DashboardSummary::class.java), recentErrors = emptyList()),
        )
        val service = DashboardPushService(dashboardService, analysisRepository, webSocketHandler, ObjectMapper())
        return service to webSocketHandler
    }

    @Test
    fun `최초 호출은 알림 없이 기준선만 설정한다`() {
        // 기동 직후 미해결(resolve_yn=N) 오류가 이미 쌓여있어도 첫 접속자에게 알림 폭탄을 쏘지
        // 않아야 한다(클래스 KDoc 참고) — findMaxErrorAnalId로 현재 최신 지점을 기준선으로 잡는다.
        val analysisRepository = mock(DataReceiveAnalysisRepository::class.java)
        `when`(analysisRepository.findMaxErrorAnalId()).thenReturn(555L)
        val (service, webSocketHandler) = newService(analysisRepository)

        service.pushNewAlerts()

        verify(webSocketHandler, never()).broadcast(anyString())
        // 기준선이 실제로 555로 잡혔는지는 다음 호출에서 findNewUnresolvedErrors(555, ...)로 확인한다.
        `when`(analysisRepository.findNewUnresolvedErrors(eq(555L), anyKt<Pageable>()))
            .thenReturn(emptyList())
        service.pushNewAlerts()
        verify(analysisRepository).findNewUnresolvedErrors(
            eq(555L),
            anyKt<Pageable>(),
        )
    }

    @Test
    fun `기준선 이후 새 오류가 있으면 브로드캐스트하고 기준선을 전진시킨다`() {
        val analysisRepository = mock(DataReceiveAnalysisRepository::class.java)
        `when`(analysisRepository.findMaxErrorAnalId()).thenReturn(100L)
        val (service, webSocketHandler) = newService(analysisRepository)
        val broadcasts = captureBroadcasts(webSocketHandler)
        service.pushNewAlerts() // 기준선을 100으로 초기화

        `when`(analysisRepository.findNewUnresolvedErrors(eq(100L), anyKt<Pageable>()))
            .thenReturn(listOf(analysis(analId = 101L), analysis(analId = 102L)))

        service.pushNewAlerts()

        assertTrue(broadcasts.size == 2)
        assertTrue(broadcasts.all { it.contains("\"type\":\"ALERT\"") })

        // 다음 폴링은 방금 처리한 102 이후만 조회해야 한다(기준선 전진 확인).
        `when`(analysisRepository.findNewUnresolvedErrors(eq(102L), anyKt<Pageable>()))
            .thenReturn(emptyList())
        service.pushNewAlerts()
        verify(analysisRepository).findNewUnresolvedErrors(
            eq(102L),
            anyKt<Pageable>(),
        )
    }

    @Test
    fun `descFireAlarm이 채워져 있으면 FIRE, 아니면 FAULT로 분류한다`() {
        // 회귀 방지(2026-08-11 B2): descFireAlarm은 DB 기본값이 빈 문자열인 non-null String이라
        // `!= null` 판정은 항상 참이었다 — isNotBlank()로 실제 내용 유무를 판정해야 모터 장애가
        // 화재 경고로 잘못 표시되지 않는다.
        val analysisRepository = mock(DataReceiveAnalysisRepository::class.java)
        `when`(analysisRepository.findMaxErrorAnalId()).thenReturn(0L)
        val (service, webSocketHandler) = newService(analysisRepository)
        val broadcasts = captureBroadcasts(webSocketHandler)
        service.pushNewAlerts() // 기준선 초기화

        `when`(analysisRepository.findNewUnresolvedErrors(eq(0L), anyKt<Pageable>()))
            .thenReturn(
                listOf(
                    analysis(analId = 1L, descFireAlarm = "화재"),
                    analysis(analId = 2L, descMainMotorError = "모터과부하"),
                ),
            )

        service.pushNewAlerts()

        assertTrue(broadcasts.size == 2)
        assertTrue(broadcasts[0].contains("\"alertType\":\"FIRE\""))
        assertTrue(broadcasts[1].contains("\"alertType\":\"FAULT\""))
    }
}
