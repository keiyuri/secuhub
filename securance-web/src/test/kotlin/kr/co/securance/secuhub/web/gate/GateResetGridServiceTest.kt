package kr.co.securance.secuhub.web.gate

import kr.co.securance.secuhub.domain.entity.GateDetail
import kr.co.securance.secuhub.domain.entity.GateGroup
import kr.co.securance.secuhub.domain.entity.GateLocation
import kr.co.securance.secuhub.domain.entity.NetState
import kr.co.securance.secuhub.domain.entity.NetStateId
import kr.co.securance.secuhub.domain.repository.GateDetailRepository
import kr.co.securance.secuhub.domain.repository.NetStateRepository
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [DashboardServiceTest]와 동일한 패턴의 Kotlin non-null 인자용 Mockito.any 우회 헬퍼. */
private fun <T> anyKt(): T {
    Mockito.any<T>()
    @Suppress("UNCHECKED_CAST")
    return null as T
}

class GateResetGridServiceTest {

    private val location = mock(GateLocation::class.java)
    private val group = mock(GateGroup::class.java).also { `when`(it.grpId).thenReturn(1L) }

    private fun detail(dtlId: Long, dtlIp: String, laneNo: Int) = GateDetail(
        dtlId = dtlId,
        location = location,
        group = group,
        dtlIp = dtlIp,
        dtlLaneNo = laneNo,
        dtlType = 1,
    )

    private fun netState(dtlIp: String, laneNo: Int, online: Boolean) = NetState(
        id = NetStateId(dtlIp = dtlIp, dtlLaneNo = laneNo, locId = 1, grpId = 1),
        dtlState = if (online) "Y" else "N",
    )

    @Test
    fun `같은 그룹 안에 IP가 다른 두 장비가 레인 번호를 공유해도 온라인 상태가 서로 덮어쓰지 않는다`() {
        // 회귀 방지 테스트: 예전에는 net_state 맵을 dtlLaneNo만으로 키를 만들어, IP가 다른 두
        // 장비가 같은 레인 번호(1)를 쓰면 나중에 들어온 쪽이 먼저 들어온 쪽의 온라인 상태를
        // 덮어썼다. tb_net_state의 실제 복합키(dtlIp, dtlLaneNo, ...)와 동일하게 (dtlIp, dtlLaneNo)
        // 조합으로 키를 만들면 두 장비 상태가 독립적으로 유지되어야 한다.
        val detailRepository = mock(GateDetailRepository::class.java)
        `when`(detailRepository.findByGroup_GrpIdAndUseYnTrueAndAnalysisYnTrueOrderByDtlLaneNo(1L)).thenReturn(
            listOf(
                detail(dtlId = 1L, dtlIp = "192.168.0.1", laneNo = 1),
                detail(dtlId = 2L, dtlIp = "192.168.0.2", laneNo = 1),
            ),
        )

        val netStateRepository = mock(NetStateRepository::class.java)
        `when`(netStateRepository.findByIdGrpId(1L)).thenReturn(
            listOf(
                netState(dtlIp = "192.168.0.1", laneNo = 1, online = true),
                netState(dtlIp = "192.168.0.2", laneNo = 1, online = false),
            ),
        )

        val service = GateResetGridService(detailRepository, netStateRepository)
        val rows = service.rowsFor(1L)

        assertEquals(2, rows.size)
        assertTrue(rows.single { it.dtlIp == "192.168.0.1" }.online, "IP .1은 온라인 상태를 유지해야 한다")
        assertFalse(rows.single { it.dtlIp == "192.168.0.2" }.online, "IP .2는 오프라인 상태를 유지해야 한다(덮어써지면 안 됨)")
    }

    @Test
    fun `grpId가 null이면 빈 목록을 반환한다`() {
        val detailRepository = mock(GateDetailRepository::class.java)
        val netStateRepository = mock(NetStateRepository::class.java)
        val service = GateResetGridService(detailRepository, netStateRepository)

        assertEquals(emptyList(), service.rowsFor(null))
    }

    @Test
    fun `net_state 정보가 없는 레인은 오프라인으로 취급한다`() {
        val detailRepository = mock(GateDetailRepository::class.java)
        `when`(detailRepository.findByGroup_GrpIdAndUseYnTrueAndAnalysisYnTrueOrderByDtlLaneNo(1L)).thenReturn(
            listOf(detail(dtlId = 1L, dtlIp = "192.168.0.1", laneNo = 1)),
        )
        val netStateRepository = mock(NetStateRepository::class.java)
        `when`(netStateRepository.findByIdGrpId(1L)).thenReturn(emptyList())

        val service = GateResetGridService(detailRepository, netStateRepository)
        val rows = service.rowsFor(1L)

        assertFalse(rows.single().online)
    }

    @Test
    fun `grpId가 없으면 소속 검증을 생략하지 않고 전량 거부한다`() {
        // Codex 적대적 리뷰 지적(2026-08-20) 회귀 방지: grpId를 비운 조작된 POST로 소속/상태
        // 검증 자체를 우회할 수 있던 구멍을 막는다 — 정상 화면(reset.html)은 그룹을 선택해야만
        // 리셋 버튼이 노출되므로 정상 경로에서는 grpId가 항상 채워져 있다.
        val detailRepository = mock(GateDetailRepository::class.java)
        val netStateRepository = mock(NetStateRepository::class.java)
        val service = GateResetGridService(detailRepository, netStateRepository)

        val result = service.filterByGroupMembership(null, listOf(1L, 2L))

        assertEquals(emptyList(), result)
        Mockito.verify(detailRepository, Mockito.never()).findByDtlIdInAndGroup_GrpId(anyKt(), Mockito.anyLong())
    }

    @Test
    fun `요청한 dtlId 중 grpId에 속하지 않은 것은 걸러낸다`() {
        // 전체 프로젝트 재감사 지적 회귀 방지: 클라이언트가 화면에 표시된 그룹과 무관한 dtlId를
        // 함께 보내도(조작/버그) 서버가 dtlId 존재 여부만 보고 그대로 처리하면 안 된다.
        val detailRepository = mock(GateDetailRepository::class.java)
        `when`(detailRepository.findByDtlIdInAndGroup_GrpId(listOf(1L, 2L, 99L), 1L)).thenReturn(
            listOf(
                detail(dtlId = 1L, dtlIp = "192.168.0.1", laneNo = 1),
                detail(dtlId = 2L, dtlIp = "192.168.0.2", laneNo = 2),
            ),
        )
        val netStateRepository = mock(NetStateRepository::class.java)
        val service = GateResetGridService(detailRepository, netStateRepository)

        val result = service.filterByGroupMembership(1L, listOf(1L, 2L, 99L))

        assertEquals(listOf(1L, 2L), result)
    }

    @Test
    fun `dtlIds가 비어 있으면 조회 없이 빈 목록을 반환한다`() {
        val detailRepository = mock(GateDetailRepository::class.java)
        val netStateRepository = mock(NetStateRepository::class.java)
        val service = GateResetGridService(detailRepository, netStateRepository)

        val result = service.filterByGroupMembership(1L, emptyList())

        assertEquals(emptyList(), result)
        Mockito.verify(detailRepository, Mockito.never()).findByDtlIdInAndGroup_GrpId(anyKt(), Mockito.anyLong())
    }
}
