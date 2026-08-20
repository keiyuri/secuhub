package kr.co.securance.secuhub.web.realtime

import tools.jackson.databind.ObjectMapper
import kr.co.securance.secuhub.domain.entity.DataReceiveAnalysis
import kr.co.securance.secuhub.domain.repository.DataReceiveAnalysisRepository
import kr.co.securance.secuhub.web.dashboard.DashboardService
import kr.co.securance.secuhub.web.dashboard.DashboardSummary
import kr.co.securance.secuhub.web.dashboard.DashboardView
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Kotlin non-null 파라미터에 [org.mockito.Mockito.any]를 직접 쓰면 NPE가 나는 문제를 우회하는
 * 표준 헬퍼([kr.co.securance.secuhub.server.db.OprStatusPersisterTest]와 동일한 패턴).
 */
private fun <T> anyKt(): T {
    org.mockito.Mockito.any<T>()
    @Suppress("UNCHECKED_CAST")
    return null as T
}

/**
 * [DashboardWebSocketHandler.broadcast]로 실제 전달된 JSON 페이로드를 [into]에 기록한다.
 *
 * `ArgumentCaptor`(`captor.capture()`)는 Java가 반환하는 platform 타입 값이 Kotlin이 엄격히
 * non-null로 선언한 [DashboardWebSocketHandler.broadcast]의 `json: String` 파라미터로 흘러들어가는
 * 경계에서 Kotlin 컴파일러가 삽입하는 null 체크에 걸려 NPE가 난다 — `Mockito.any()`를 감싸는
 * [anyKt]와 달리 캡처는 이 우회로 해결되지 않았다([GateConnectionRegistryImplTest.recordUpsertCalls]와
 * 동일한 이유로 doAnswer 기반 기록 방식으로 대체).
 */
private fun recordBroadcasts(handler: DashboardWebSocketHandler, into: MutableList<String>) {
    doAnswer { invocation ->
        into += invocation.getArgument<String>(0)
        null
    }.`when`(handler).broadcast(anyKt())
}

/**
 * [DashboardPushService] 검증 — 코드 리뷰 지적 A-2(2026-08-20) 회귀 테스트 중심.
 *
 * [DashboardPushService.pushSummary]는 구독자가 하나도 없으면 무거운 대시보드 조회(내부 쿼리 5개)
 * 자체를 건너뛰어야 한다 — 2026-08-14 소켓 타임아웃 장애를 만든 것과 같은 "아무도 안 보는데 계속
 * DB를 훑는" 부하 패턴을 재현하지 않는지가 핵심이다. `pushNewAlerts`의 기준선 초기화/전진 로직도
 * 과거 실제 장애(컬럼 오참조 `anal_type` vs `anal_tp`, 2026-08-14 `/codex:adversarial-review`
 * 지적 — 실시간 알림이 통째로 죽고 소켓 타임아웃까지 유발)를 낸 적이 있어 함께 검증한다.
 */
class DashboardPushServiceTest {

    private fun analysis(analId: Long, dtlIp: String = "192.168.0.10", fireAlarm: String = "") =
        DataReceiveAnalysis(
            analId = analId,
            analTp = "B",
            analDate = "202608201200",
            rcvDate = "202608201200",
            dtlIp = dtlIp,
            dtlLaneNo = 1,
        ).apply { descGateStatus07 = fireAlarm } // descFireAlarm 위임 대상(DataReceiveAnalysis.kt 참고).

    @Test
    fun `구독자가 없으면 대시보드 요약 조회 자체를 건너뛴다`() {
        val dashboardService = mock(DashboardService::class.java)
        val analysisRepository = mock(DataReceiveAnalysisRepository::class.java)
        val webSocketHandler = mock(DashboardWebSocketHandler::class.java)
        `when`(webSocketHandler.hasSessions()).thenReturn(false)

        val service = DashboardPushService(dashboardService, analysisRepository, webSocketHandler, ObjectMapper())
        service.pushSummary()

        verify(dashboardService, never()).loadDashboard()
        verify(webSocketHandler, never()).broadcast(anyKt())
    }

    @Test
    fun `구독자가 있으면 대시보드 요약을 조회해 브로드캐스트한다`() {
        val dashboardService = mock(DashboardService::class.java)
        `when`(dashboardService.loadDashboard()).thenReturn(
            DashboardView(
                summary = DashboardSummary(onlineGateCount = 1, offlineGateCount = 0, unresolvedErrorCount = 0, todayTrafficCount = 0),
                recentErrors = emptyList(),
            ),
        )
        val analysisRepository = mock(DataReceiveAnalysisRepository::class.java)
        val webSocketHandler = mock(DashboardWebSocketHandler::class.java)
        `when`(webSocketHandler.hasSessions()).thenReturn(true)

        val service = DashboardPushService(dashboardService, analysisRepository, webSocketHandler, ObjectMapper())
        service.pushSummary()

        verify(dashboardService).loadDashboard()
        verify(webSocketHandler).broadcast(anyKt())
    }

    @Test
    fun `최초 폴링은 팝업 없이 최신 anal_id로 기준선만 맞춘다`() {
        val analysisRepository = mock(DataReceiveAnalysisRepository::class.java)
        `when`(analysisRepository.findMaxErrorAnalId()).thenReturn(42L)
        val webSocketHandler = mock(DashboardWebSocketHandler::class.java)

        val service = DashboardPushService(
            mock(DashboardService::class.java), analysisRepository, webSocketHandler, ObjectMapper(),
        )
        service.pushNewAlerts()

        verify(webSocketHandler, never()).broadcast(anyKt())
        verify(analysisRepository, never()).findNewUnresolvedErrors(anyLong(), anyKt())
    }

    @Test
    fun `기준선 이후 신규 오류는 팝업으로 브로드캐스트되고 기준선이 전진한다`() {
        val analysisRepository = mock(DataReceiveAnalysisRepository::class.java)
        `when`(analysisRepository.findMaxErrorAnalId()).thenReturn(10L)
        val webSocketHandler = mock(DashboardWebSocketHandler::class.java)
        val service = DashboardPushService(
            mock(DashboardService::class.java), analysisRepository, webSocketHandler, ObjectMapper(),
        )
        service.pushNewAlerts() // 기준선을 10으로 초기화.

        `when`(analysisRepository.findNewUnresolvedErrors(anyLong(), anyKt()))
            .thenReturn(listOf(analysis(analId = 11L, fireAlarm = "화재 감지")))

        service.pushNewAlerts() // 구독자 유무와 무관하게 기준선은 계속 전진해야 한다(A-2).

        verify(webSocketHandler).broadcast(anyKt())

        // 세 번째 폴링에서는 이미 처리한 11번을 다시 조회하지 않는다 — 기준선이 11로 전진했는지 확인.
        `when`(analysisRepository.findNewUnresolvedErrors(anyLong(), anyKt())).thenReturn(emptyList())
        service.pushNewAlerts()
        verify(analysisRepository).findNewUnresolvedErrors(org.mockito.ArgumentMatchers.eq(11L), anyKt())
    }

    @Test
    fun `구독자가 없어도 신규 알림 폴링은 계속되어 기준선이 밀리지 않는다`() {
        // 코드 리뷰 지적 A-2: pushSummary만 건너뛰고 pushNewAlerts는 계속 돌아야, 나중에 구독자가
        // 접속했을 때 그동안 밀린 알림이 한꺼번에 몰리지 않는다.
        val analysisRepository = mock(DataReceiveAnalysisRepository::class.java)
        `when`(analysisRepository.findMaxErrorAnalId()).thenReturn(5L)
        val webSocketHandler = mock(DashboardWebSocketHandler::class.java)
        `when`(webSocketHandler.hasSessions()).thenReturn(false)

        val service = DashboardPushService(
            mock(DashboardService::class.java), analysisRepository, webSocketHandler, ObjectMapper(),
        )
        service.pushNewAlerts()

        assertTrue(true) // 예외 없이 기준선 초기화가 수행되면 통과 — 위 findMaxErrorAnalId 호출이 핵심 단언.
        verify(analysisRepository).findMaxErrorAnalId()
    }

    // ── alertType FIRE/FAULT 판정 (버그 수정 2026-08-11 B2 회귀) ────────────

    @Test
    fun `descFireAlarm이 채워져 있으면 FIRE로 분류한다`() {
        val analysisRepository = mock(DataReceiveAnalysisRepository::class.java)
        `when`(analysisRepository.findMaxErrorAnalId()).thenReturn(0L)
        val webSocketHandler = mock(DashboardWebSocketHandler::class.java)
        val broadcasts = mutableListOf<String>()
        recordBroadcasts(webSocketHandler, broadcasts)
        val service = DashboardPushService(
            mock(DashboardService::class.java), analysisRepository, webSocketHandler, ObjectMapper(),
        )
        service.pushNewAlerts() // 기준선 초기화.

        `when`(analysisRepository.findNewUnresolvedErrors(anyLong(), anyKt()))
            .thenReturn(listOf(analysis(analId = 1L, fireAlarm = "화재 감지")))
        service.pushNewAlerts()

        val payload = broadcasts.single()
        assertTrue(payload.contains("\"alertType\":\"FIRE\""), "화재 경고 필드가 채워졌으면 FIRE로 분류돼야 한다: $payload")
    }

    @Test
    fun `descFireAlarm이 비어 있으면(모터 등 다른 장애) FAULT로 분류한다`() {
        // 버그 수정(2026-08-11, B2) 회귀 테스트: descFireAlarm 등은 DB 컬럼 기본값이 빈 문자열인
        // non-null String이라 `!= null` 판정은 항상 참이었다 — isNotBlank()로 판정해야 모터 장애까지
        // 전부 "화재 경고"로 잘못 표시되던 문제가 재발하지 않는다.
        val analysisRepository = mock(DataReceiveAnalysisRepository::class.java)
        `when`(analysisRepository.findMaxErrorAnalId()).thenReturn(0L)
        val webSocketHandler = mock(DashboardWebSocketHandler::class.java)
        val broadcasts = mutableListOf<String>()
        recordBroadcasts(webSocketHandler, broadcasts)
        val service = DashboardPushService(
            mock(DashboardService::class.java), analysisRepository, webSocketHandler, ObjectMapper(),
        )
        service.pushNewAlerts() // 기준선 초기화.

        `when`(analysisRepository.findNewUnresolvedErrors(anyLong(), anyKt()))
            .thenReturn(listOf(analysis(analId = 1L, fireAlarm = "")))
        service.pushNewAlerts()

        val payload = broadcasts.single()
        assertTrue(payload.contains("\"alertType\":\"FAULT\""), "화재 경고가 비어 있으면 FAULT로 분류돼야 한다: $payload")
    }

    // ── NEW_ALERTS_BATCH_LIMIT 상한 (밀린 알림 폭주 방지) ───────────────

    @Test
    fun `배치 한 번에 여러 건이 와도 각각 브로드캐스트하고 기준선은 그 중 최대 anal_id로 전진한다`() {
        val analysisRepository = mock(DataReceiveAnalysisRepository::class.java)
        `when`(analysisRepository.findMaxErrorAnalId()).thenReturn(0L)
        val webSocketHandler = mock(DashboardWebSocketHandler::class.java)
        val service = DashboardPushService(
            mock(DashboardService::class.java), analysisRepository, webSocketHandler, ObjectMapper(),
        )
        service.pushNewAlerts() // 기준선 초기화.

        // findNewUnresolvedErrors는 최대 NEW_ALERTS_BATCH_LIMIT(200)건까지 반환할 수 있다 — 여기서는
        // 반환 자체를 3건으로 스텁해, "이 배치의 최대값"으로 기준선이 전진하는지만 확인한다(장기간
        // 다운타임 뒤 재기동 시 밀린 오류가 한꺼번에 몰리는 것을 막는 상한의 소비 측 동작).
        `when`(analysisRepository.findNewUnresolvedErrors(anyLong(), anyKt())).thenReturn(
            listOf(analysis(analId = 3L), analysis(analId = 1L), analysis(analId = 2L)), // 순서 뒤섞여도 무관해야 한다.
        )
        service.pushNewAlerts()

        verify(webSocketHandler, times(3)).broadcast(anyKt())

        // 다음 폴링은 기준선(=배치 내 최대값 3) 이후로만 조회해야 한다.
        `when`(analysisRepository.findNewUnresolvedErrors(anyLong(), anyKt())).thenReturn(emptyList())
        service.pushNewAlerts()
        verify(analysisRepository).findNewUnresolvedErrors(org.mockito.ArgumentMatchers.eq(3L), anyKt())
    }
}
