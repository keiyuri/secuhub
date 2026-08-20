package kr.co.securance.secuhub.web.gate

import kr.co.securance.secuhub.domain.entity.GateGroup
import kr.co.securance.secuhub.domain.repository.GateGroupRepository
import kr.co.securance.secuhub.domain.repository.GateLocationRepository
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import kotlin.test.Test
import kotlin.test.assertSame

/**
 * 코드 리뷰 지적(2026-08-20, Codex) 회귀 방지 테스트 — [GateGroupService.findByIdOrNull]이
 * `location`을 fetch join하지 않는 기본 `JpaRepository.findById`로 새지 않는지 검증한다.
 * fetch join이 빠지면 반환값을 트랜잭션 밖(뷰 렌더링)에서 `.location.locName`처럼 읽을 때
 * LazyInitializationException이 난다(레인 관리 화면의 그룹 콤보에 비활성 그룹을 끼워 넣는
 * 경로에서 재현됨).
 */
class GateGroupServiceTest {

    @Test
    fun `findByIdOrNull은 location이 fetch join된 조회로 위임한다`() {
        val groupRepository = mock(GateGroupRepository::class.java)
        val group = mock(GateGroup::class.java)
        `when`(groupRepository.findByIdWithLocation(1L)).thenReturn(group)

        val service = GateGroupService(groupRepository, mock(GateLocationRepository::class.java))
        val result = service.findByIdOrNull(1L)

        assertSame(group, result)
        verify(groupRepository, never()).findById(1L)
    }
}
