package kr.co.securance.secuhub.web.gate

import kr.co.securance.secuhub.domain.entity.GateDetail
import kr.co.securance.secuhub.domain.entity.GateGroup
import kr.co.securance.secuhub.domain.entity.GateLocation
import kr.co.securance.secuhub.domain.repository.GateDetailRepository
import kr.co.securance.secuhub.domain.repository.GateGroupRepository
import kr.co.securance.secuhub.domain.repository.GateLocationRepository
import kr.co.securance.secuhub.domain.repository.NetStateRepository
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.data.domain.Sort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [GateResetGridServiceTest]와 동일한 패턴의 Kotlin non-null 인자용 Mockito.any 우회 헬퍼. */
private fun <T> anyKt(): T {
    Mockito.any<T>()
    @Suppress("UNCHECKED_CAST")
    return null as T
}

/**
 * [GateTreeService.buildTree] 검증(2026-08-19 사용자 요청) — "사용여부와 분석여부가 Y인
 * 것만 표시"에 따라 LOC/GRP는 `use_yn='Y'`, DTL은 `use_yn='Y' AND analysis_yn='Y'`인 것만
 * 트리에 남아야 한다(SR_Speed_Client `GetTreeListAsync` 쿼리와 동일 기준). DTL 단의
 * `analysis_yn` 필터는 [GateDetailRepository.findAllForTree]의 쿼리 조건이 담당하므로, 여기서는
 * 리포지토리가 반환한 값을 그대로 신뢰하는 서비스의 LOC/GRP 필터링만 단위 테스트로 검증한다.
 */
class GateTreeServiceTest {

    private val locationRepository = mock(GateLocationRepository::class.java)
    private val groupRepository = mock(GateGroupRepository::class.java)
    private val detailRepository = mock(GateDetailRepository::class.java)
    private val netStateRepository = mock(NetStateRepository::class.java)

    private val service = GateTreeService(locationRepository, groupRepository, detailRepository, netStateRepository)

    private fun location(id: Long, name: String, useYn: Boolean) =
        GateLocation(locId = id, locName = name, useYn = useYn)

    private fun group(id: Long, loc: GateLocation, name: String, useYn: Boolean, gateTypeCode: Int = 1) =
        GateGroup(grpId = id, location = loc, grpName = name, gateTypeCode = gateTypeCode, useYn = useYn)

    @Test
    fun `사용여부가 N인 위치는 트리에서 제외된다`() {
        val used = location(1L, "사용중 위치", useYn = true)
        val unused = location(2L, "미사용 위치", useYn = false)
        `when`(locationRepository.findAll(anyKt<Sort>())).thenReturn(listOf(used, unused))
        `when`(groupRepository.findAll()).thenReturn(emptyList())
        `when`(detailRepository.findAllForTree()).thenReturn(emptyList())
        `when`(netStateRepository.findAll()).thenReturn(emptyList())

        val tree = service.buildTree()

        assertEquals(listOf(1L), tree.map { it.locId })
    }

    @Test
    fun `사용여부가 N인 그룹은 트리에서 제외된다`() {
        val loc = location(1L, "위치", useYn = true)
        val usedGroup = group(10L, loc, "사용중 그룹", useYn = true)
        val unusedGroup = group(11L, loc, "미사용 그룹", useYn = false)
        `when`(locationRepository.findAll(anyKt<Sort>())).thenReturn(listOf(loc))
        `when`(groupRepository.findAll()).thenReturn(listOf(usedGroup, unusedGroup))
        `when`(detailRepository.findAllForTree()).thenReturn(emptyList())
        `when`(netStateRepository.findAll()).thenReturn(emptyList())

        val tree = service.buildTree()

        assertEquals(listOf(10L), tree.single().groups.map { it.grpId })
    }

    @Test
    fun `미사용 위치 아래에 사용중인 그룹이 있어도 위치 자체가 제외되므로 함께 제외된다`() {
        // SR_Speed_Client의 GRP 레벨 쿼리도 부모 LOC의 use_yn='Y' 조인을 요구한다 — 부모가
        // 미사용이면 자식(그룹의 useYn 값과 무관하게)도 트리에 나타나면 안 된다.
        val unusedLoc = location(1L, "미사용 위치", useYn = false)
        val groupUnderUnusedLoc = group(10L, unusedLoc, "사용중 그룹", useYn = true)
        `when`(locationRepository.findAll(anyKt<Sort>())).thenReturn(listOf(unusedLoc))
        `when`(groupRepository.findAll()).thenReturn(listOf(groupUnderUnusedLoc))
        `when`(detailRepository.findAllForTree()).thenReturn(emptyList())
        `when`(netStateRepository.findAll()).thenReturn(emptyList())

        val tree = service.buildTree()

        assertTrue(tree.isEmpty())
    }
}
