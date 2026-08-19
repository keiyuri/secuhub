package kr.co.securance.secuhub.web.gate

import kr.co.securance.secuhub.domain.entity.GateDetail
import kr.co.securance.secuhub.domain.entity.GateGroup
import kr.co.securance.secuhub.domain.entity.GateLocation
import kr.co.securance.secuhub.domain.entity.NetState
import kr.co.securance.secuhub.domain.entity.NetStateId
import kr.co.securance.secuhub.domain.repository.GateDetailRepository
import kr.co.securance.secuhub.domain.repository.GateGroupRepository
import kr.co.securance.secuhub.domain.repository.GateLocationRepository
import kr.co.securance.secuhub.domain.repository.NetStateRepository
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.data.domain.Sort
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [GateTreeService]의 TTL 캐시(2026-08-20 Opus 전체 리뷰 지적 — `/api/gate-tree`가 탭당 5초
 * 주기로 폴링되며 매 호출마다 4개 테이블을 전량 조회하던 문제) 회귀 테스트.
 */
class GateTreeServiceTest {

    private val loc = GateLocation(locId = 1L, locName = "본관")
    private val grp = GateGroup(grpId = 10L, location = loc, grpName = "1층 로비", gateTypeCode = 1)
    private val dtl = GateDetail(
        dtlId = 100L,
        location = loc,
        group = grp,
        dtlIp = "10.0.0.1",
        dtlLaneNo = 1,
        dtlType = 1,
    )

    private fun newService(
        clock: Clock,
        locationRepository: GateLocationRepository = mock(GateLocationRepository::class.java).also {
            `when`(it.findAll(Sort.by("locId"))).thenReturn(listOf(loc))
        },
        groupRepository: GateGroupRepository = mock(GateGroupRepository::class.java).also {
            `when`(it.findAll()).thenReturn(listOf(grp))
        },
        detailRepository: GateDetailRepository = mock(GateDetailRepository::class.java).also {
            `when`(it.findAllForTree()).thenReturn(listOf(dtl))
        },
        netStateRepository: NetStateRepository = mock(NetStateRepository::class.java).also {
            `when`(it.findAll()).thenReturn(
                listOf(NetState(id = NetStateId(dtlIp = "10.0.0.1", dtlLaneNo = 1, locId = 1L, grpId = 10L), dtlState = "Y")),
            )
        },
    ) = Triple(
        GateTreeService(locationRepository, groupRepository, detailRepository, netStateRepository, clock),
        detailRepository,
        netStateRepository,
    )

    @Test
    fun `트리를 정상적으로 조립한다`() {
        val (service, _, _) = newService(Clock.fixed(Instant.parse("2026-08-20T00:00:00Z"), ZoneOffset.UTC))

        val tree = service.buildTree()

        assertEquals(1, tree.size)
        assertEquals("본관", tree[0].locName)
        assertEquals(1, tree[0].groups.size)
        assertEquals(1, tree[0].groups[0].details.size)
        assertTrue(tree[0].groups[0].details[0].online, "tb_net_state에 Y로 기록된 레인은 online=true여야 한다")
    }

    @Test
    fun `TTL 이내 재호출은 캐시를 재사용하고 리포지토리를 다시 조회하지 않는다`() {
        val fixed = Instant.parse("2026-08-20T00:00:00Z")
        val clock = MutableClock(fixed)
        val (service, detailRepository, netStateRepository) = newService(clock)

        service.buildTree()
        clock.instant = fixed.plusSeconds(1) // TTL(2초) 이내
        service.buildTree()

        verify(detailRepository, times(1)).findAllForTree()
        verify(netStateRepository, times(1)).findAll()
    }

    @Test
    fun `TTL 만료 후 재호출은 리포지토리를 다시 조회한다`() {
        val fixed = Instant.parse("2026-08-20T00:00:00Z")
        val clock = MutableClock(fixed)
        val (service, detailRepository, netStateRepository) = newService(clock)

        service.buildTree()
        clock.instant = fixed.plusSeconds(3) // TTL(2초) 초과
        service.buildTree()

        verify(detailRepository, times(2)).findAllForTree()
        verify(netStateRepository, times(2)).findAll()
    }

    // 회귀 방지(2026-08-20 Codex 적대적 리뷰 지적) — NTP 보정/VM 시간 동기화로 시스템 시계가
    // 뒤로 이동하면 Duration.between(builtAt, now)가 음수가 되어 `< cacheTtl` 조건을 만족해버린다.
    // 이때 캐시를 계속 적중시키면 시계가 원래 시각을 따라잡을 때까지(여기서는 5초) 운영자가 실제
    // 구성/온라인 상태 변경을 전혀 보지 못한다 — 시계 역행 시에는 즉시 캐시를 무효화해야 한다.
    @Test
    fun `시스템 시계가 뒤로 이동해도 캐시를 즉시 무효화하고 리포지토리를 다시 조회한다`() {
        val fixed = Instant.parse("2026-08-20T00:00:00Z")
        val clock = MutableClock(fixed)
        val (service, detailRepository, netStateRepository) = newService(clock)

        service.buildTree()
        clock.instant = fixed.minusSeconds(5) // 시계 역행 — TTL 계산상 "미래"가 아니라 "과거"로 이동.
        service.buildTree()

        verify(detailRepository, times(2)).findAllForTree()
        verify(netStateRepository, times(2)).findAll()
    }

    /** 잠금 해제 시각 경과 시뮬레이션과 동일한 패턴(LoginAttemptServiceTest 참고)의 가변 [Clock]. */
    private class MutableClock(var instant: Instant) : Clock() {
        override fun instant(): Instant = instant
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?): Clock = this
    }
}
