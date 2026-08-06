package kr.co.securance.secuhub.web.dashboard

import kr.co.securance.secuhub.domain.entity.DataReceiveAnalysis
import kr.co.securance.secuhub.domain.repository.DataReceiveAnalysisRepository
import kr.co.securance.secuhub.domain.repository.NetStateRepository
import org.mockito.Mockito
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Kotlin non-null 파라미터에 [Mockito.any]를 직접 쓰면 NPE가 나는 문제를 우회하는 표준 헬퍼.
 * ([kr.co.securance.secuhub.server.connection.GateConnectionRegistryImplTest]와 동일한 패턴)
 */
private fun <T> anyKt(): T {
    Mockito.any<T>()
    @Suppress("UNCHECKED_CAST")
    return null as T
}

class DashboardServiceTest {

    private fun fakeError(dtlIp: String = "192.168.0.1"): DataReceiveAnalysis =
        DataReceiveAnalysis(
            analDate = "202601010000",
            analType = "PLM",
            dtlIp = dtlIp,
            dtlLaneNo = 1,
            errType = 3,
            resolveYn = "N",
        )

    @Test
    fun `unresolvedErrorCount는 목록 페이지 크기가 아니라 실제 총 건수를 반환한다`() {
        // 회귀 방지 테스트: 예전에는 unresolvedErrorCount = errors.size(최대 8건)였다.
        // 실제 미해결 오류가 8건보다 많은 경우(여기서는 42건)를 재현해, 카운트가 8로 잘리지
        // 않고 별도 COUNT 쿼리 결과(countRecentUnresolvedErrors)를 그대로 쓰는지 검증한다.
        val netStateRepository = mock(NetStateRepository::class.java)
        `when`(netStateRepository.countByDtlState("Y")).thenReturn(10L)
        `when`(netStateRepository.countByDtlState("N")).thenReturn(2L)

        val analysisRepository = mock(DataReceiveAnalysisRepository::class.java)
        // 목록 조회는 여전히 상위 8건만 반환(페이지 제한 그대로 유지되는지도 함께 확인).
        `when`(analysisRepository.findRecentUnresolvedErrors(anyKt(), anyKt()))
            .thenReturn(List(8) { fakeError() })
        // 하지만 실제 총 건수는 42건 — 이 값이 그대로 unresolvedErrorCount에 반영되어야 한다.
        `when`(analysisRepository.countRecentUnresolvedErrors(anyKt())).thenReturn(42L)

        val service = DashboardService(netStateRepository, analysisRepository)

        val view = service.loadDashboard()

        assertEquals(42L, view.summary.unresolvedErrorCount, "8건으로 잘리지 않고 실제 총 건수(42)를 반영해야 한다")
        assertEquals(8, view.recentErrors.size, "화면 목록은 여전히 상위 8건만 보여줘야 한다")
        assertEquals(10L, view.summary.onlineGateCount)
        assertEquals(2L, view.summary.offlineGateCount)
    }

    @Test
    fun `loadDashboard는 동일 조건의 미해결 오류 조회를 두 번이 아니라 한 번만 요청한다`() {
        // 회귀 방지 테스트: 예전에는 컨트롤러가 summary()/recentUnresolvedErrors()를 각각 호출해
        // findRecentUnresolvedErrors가 페이지 로드마다 중복 실행됐다.
        val netStateRepository = mock(NetStateRepository::class.java)
        `when`(netStateRepository.countByDtlState(anyKt())).thenReturn(0L)

        val analysisRepository = mock(DataReceiveAnalysisRepository::class.java)
        `when`(analysisRepository.findRecentUnresolvedErrors(anyKt(), anyKt())).thenReturn(emptyList())
        `when`(analysisRepository.countRecentUnresolvedErrors(anyKt())).thenReturn(0L)

        val service = DashboardService(netStateRepository, analysisRepository)
        service.loadDashboard()

        verify(analysisRepository, Mockito.times(1)).findRecentUnresolvedErrors(anyKt(), anyKt())
        verify(analysisRepository, Mockito.times(1)).countRecentUnresolvedErrors(anyKt())
    }

    @Test
    fun `목록 조회와 카운트 조회는 같은 sinceDate 기준으로 이뤄진다`() {
        var listSinceDate: String? = null
        var countSinceDate: String? = null

        val netStateRepository = mock(NetStateRepository::class.java)
        `when`(netStateRepository.countByDtlState(anyKt())).thenReturn(0L)

        val analysisRepository = mock(DataReceiveAnalysisRepository::class.java)
        doAnswer { invocation ->
            listSinceDate = invocation.getArgument(0)
            emptyList<DataReceiveAnalysis>()
        }.`when`(analysisRepository).findRecentUnresolvedErrors(anyKt(), anyKt())
        doAnswer { invocation ->
            countSinceDate = invocation.getArgument(0)
            0L
        }.`when`(analysisRepository).countRecentUnresolvedErrors(anyKt())

        val service = DashboardService(netStateRepository, analysisRepository)
        service.loadDashboard()

        assertEquals(listSinceDate, countSinceDate)
    }

    @Test
    fun `오류 상세 설명이 모두 비어있으면 기본 문구로 대체된다`() {
        val netStateRepository = mock(NetStateRepository::class.java)
        `when`(netStateRepository.countByDtlState(anyKt())).thenReturn(0L)

        val analysisRepository = mock(DataReceiveAnalysisRepository::class.java)
        `when`(analysisRepository.findRecentUnresolvedErrors(anyKt(), anyKt())).thenReturn(listOf(fakeError()))
        `when`(analysisRepository.countRecentUnresolvedErrors(anyKt())).thenReturn(1L)

        val service = DashboardService(netStateRepository, analysisRepository)
        val view = service.loadDashboard()

        assertEquals("오류 상세 미확인", view.recentErrors.single().description)
    }
}
