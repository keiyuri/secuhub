package kr.co.securance.secuhub.web.gate

import kr.co.securance.secuhub.domain.entity.GateDetail
import kr.co.securance.secuhub.domain.repository.GateDetailRepository
import kr.co.securance.secuhub.domain.repository.GateGroupRepository
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 코드 리뷰 지적(2026-08-20) 회귀 방지 테스트 — "예외 없이 모든 목록 조회에 사용/분석 필터를
 * 적용"(2026-08-20 후속 지시)에 따라 [GateDetailService.findAllForManagement]가 필터 없는 쿼리로
 * 새지 않는지 검증한다.
 */
class GateDetailServiceTest {

    /**
     * 코드 리뷰 지적(2026-08-20) — 레인 관리(CRUD) 화면만 Location/Group/User 관리 화면과 달리
     * "비활성 항목 표시" 토글이 없어 비활성/미분석 레인을 재활성화할 방법이 없었다. 토글 추가에
     * 대한 회귀 방지 테스트.
     */
    @Test
    fun `findAllForManagement은 showInactive가 false면 사용·분석 대상만 조회한다`() {
        val detailRepository = mock(GateDetailRepository::class.java)
        val filtered = listOf(mock(GateDetail::class.java))
        `when`(detailRepository.findByGroup_GrpIdAndUseYnTrueAndAnalysisYnTrueOrderByDtlLaneNo(1L))
            .thenReturn(filtered)

        val service = GateDetailService(detailRepository, mock(GateGroupRepository::class.java))
        val result = service.findAllForManagement(1L, showInactive = false)

        assertEquals(filtered, result)
        verify(detailRepository, never()).findByGroup_GrpIdOrderByDtlLaneNo(1L)
    }

    @Test
    fun `findAllForManagement은 showInactive가 true면 비활성·미분석 레인까지 전부 조회한다`() {
        val detailRepository = mock(GateDetailRepository::class.java)
        val all = listOf(mock(GateDetail::class.java), mock(GateDetail::class.java))
        `when`(detailRepository.findByGroup_GrpIdOrderByDtlLaneNo(1L)).thenReturn(all)

        val service = GateDetailService(detailRepository, mock(GateGroupRepository::class.java))
        val result = service.findAllForManagement(1L, showInactive = true)

        assertEquals(all, result)
        verify(detailRepository, never()).findByGroup_GrpIdAndUseYnTrueAndAnalysisYnTrueOrderByDtlLaneNo(1L)
    }

    @Test
    fun `findAllForManagement은 grpId가 null이면 showInactive와 무관하게 빈 목록을 반환한다`() {
        val detailRepository = mock(GateDetailRepository::class.java)
        val service = GateDetailService(detailRepository, mock(GateGroupRepository::class.java))

        assertEquals(emptyList(), service.findAllForManagement(null, showInactive = true))
    }

    /**
     * 2026-08-20 사용자 확인 — 스케줄 화면의 레인 콤보는 예약 명령이 analysisYn과 무관하게 적용
     * 대상이므로(ScheduleApplyService.targets 참고) useYn만 확인하고 analysisYn은 확인하지 않는다.
     */
    @Test
    fun `findByGroupForSchedule은 사용=Y만 확인하고 분석 여부는 확인하지 않는다`() {
        val detailRepository = mock(GateDetailRepository::class.java)
        val all = listOf(mock(GateDetail::class.java))
        `when`(detailRepository.findByGroup_GrpIdAndUseYnTrue(1L)).thenReturn(all)

        val service = GateDetailService(detailRepository, mock(GateGroupRepository::class.java))
        val result = service.findByGroupForSchedule(1L)

        assertEquals(all, result)
        verify(detailRepository, never()).findByGroup_GrpIdAndUseYnTrueAndAnalysisYnTrueOrderByDtlLaneNo(1L)
    }

    @Test
    fun `findByGroupForSchedule은 grpId가 null이면 조회 없이 빈 목록을 반환한다`() {
        val detailRepository = mock(GateDetailRepository::class.java)
        val service = GateDetailService(detailRepository, mock(GateGroupRepository::class.java))

        assertEquals(emptyList(), service.findByGroupForSchedule(null))
    }
}
