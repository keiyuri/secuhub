package kr.co.securance.secuhub.domain.repository

import kr.co.securance.secuhub.common.gate.GateTypeCodes
import kr.co.securance.secuhub.domain.entity.GateGroup
import kr.co.securance.secuhub.domain.entity.GateLocation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager
import org.springframework.test.context.TestPropertySource

/**
 * 게이트그룹 목록 정렬 회귀 방지 테스트(2026-08-25 "위치ID→그룹ID 순 정렬" 요청).
 *
 * [GateGroupRepository]의 각 조회 메서드가 `order by loc_id, grp_id`를 지키는지 실제 JPA
 * 프로바이더 위에서 검증한다 — 목 기반 테스트로는 JPQL의 `order by` 절 누락/오탈자를 잡을 수 없다.
 * 그룹ID(자동 증가 PK)가 저장 순서와 위치 배정 순서에 따라 위치ID 순서와 어긋나도록 일부러
 * 뒤섞어 저장해, 단순 PK 순이 아니라 위치ID를 1순위로 정렬하는지 확인한다.
 */
@DataJpaTest
@TestPropertySource(
    properties = [
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
    ],
)
class GateGroupRepositoryTest {

    @Autowired
    private lateinit var entityManager: TestEntityManager

    @Autowired
    private lateinit var repository: GateGroupRepository

    private fun group(location: GateLocation, name: String) = GateGroup(
        location = location,
        grpName = name,
        gateTypeCode = GateTypeCodes.SPEED_GATE,
    )

    @Test
    fun `목록 조회는 위치ID 다음 그룹ID 순으로 정렬된다`() {
        // 위치는 locB(먼저 저장 → locId 작음) 다음 locA(나중 저장 → locId 큼) 순으로 만들고,
        // 각 위치 안에서도 grpId가 위치 저장 순서와 무관하게 섞이도록 번갈아 저장한다.
        val locB = entityManager.persistAndFlush(GateLocation(locName = "위치B"))
        val locA = entityManager.persistAndFlush(GateLocation(locName = "위치A"))

        val g1 = entityManager.persistAndFlush(group(locA, "A-그룹1")) // grpId 작음, locId 큼
        val g2 = entityManager.persistAndFlush(group(locB, "B-그룹1")) // grpId 중간, locId 작음
        val g3 = entityManager.persistAndFlush(group(locB, "B-그룹2")) // grpId 큼, locId 작음
        entityManager.clear()

        val result = repository.findAll()

        assertEquals(listOf(g2.grpId, g3.grpId, g1.grpId), result.map { it.grpId })
        assertEquals(listOf(locB.locId, locB.locId, locA.locId), result.map { it.location.locId })
    }

    @Test
    fun `위치별 조회도 그룹ID 순으로 정렬된다`() {
        val loc = entityManager.persistAndFlush(GateLocation(locName = "위치"))
        val g2 = entityManager.persistAndFlush(group(loc, "그룹2"))
        val g1Later = entityManager.persistAndFlush(group(loc, "그룹1"))
        entityManager.clear()

        val result = repository.findByLocation_LocId(requireNotNull(loc.locId))

        assertEquals(listOf(g2.grpId, g1Later.grpId), result.map { it.grpId })
    }
}
