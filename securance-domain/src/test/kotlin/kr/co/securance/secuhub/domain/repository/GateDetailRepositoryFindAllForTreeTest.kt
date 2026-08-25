package kr.co.securance.secuhub.domain.repository

import kr.co.securance.secuhub.domain.entity.GateDetail
import kr.co.securance.secuhub.domain.entity.GateGroup
import kr.co.securance.secuhub.domain.entity.GateLocation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager
import org.springframework.test.context.TestPropertySource

/**
 * [GateDetailRepository.findAllForTree]가 "게이트 목록은 화면 어디서든 설치 위치 ID → 그룹 ID →
 * 게이트 ID 순으로 표시하고, 분석 대상('Y')이 아닌 게이트는 제외한다"는 기준을 실제 JPA 프로바이더
 * 위에서 지키는지 검증한다(2026-08-25 사용자 요청 — 목 기반 테스트만으로는 `ORDER BY` 절의
 * 정렬 컬럼 오타/누락을 잡을 수 없다).
 *
 * 위치/그룹 이름은 일부러 ID 발급 순서와 반대(사전순 역순)로 지어, 정렬이 실제로 ID 기준이지
 * 이름 기준이 아님을 함께 확인한다. 같은 그룹 안의 두 레인도 `dtlLaneNo`를 ID 발급 순서와 반대로
 * 줘서, 과거처럼 `dtlLaneNo`로 정렬되고 있었다면 이 테스트가 실패하도록 만든다.
 */
@DataJpaTest
@TestPropertySource(
    properties = [
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
    ],
)
class GateDetailRepositoryFindAllForTreeTest {

    @Autowired
    private lateinit var entityManager: TestEntityManager

    @Autowired
    private lateinit var repository: GateDetailRepository

    private fun location(name: String) = entityManager.persistAndFlush(GateLocation(locName = name, useYn = true))

    private fun group(loc: GateLocation, name: String) =
        entityManager.persistAndFlush(GateGroup(location = loc, grpName = name, gateTypeCode = 1, useYn = true))

    private fun detail(
        loc: GateLocation,
        grp: GateGroup,
        ip: String,
        laneNo: Int,
        useYn: Boolean = true,
        analysisYn: Boolean = true,
    ) = entityManager.persistAndFlush(
        GateDetail(
            location = loc,
            group = grp,
            dtlIp = ip,
            dtlLaneNo = laneNo,
            dtlType = 1,
            useYn = useYn,
            analysisYn = analysisYn,
        ),
    )

    @Test
    fun `위치ID-그룹ID-게이트ID 순으로 정렬되고 사용_분석이 Y인 게이트만 반환한다`() {
        // 위치는 이름 역순(Z, A)으로 만들어 locId=1(Z-위치)이 먼저 발급되게 한다.
        val locZ = location("Z-위치") // locId=1
        val locA = location("A-위치") // locId=2

        // locZ 안의 그룹도 이름 역순(B, A)으로 만들어 grpId=1(B그룹)이 먼저 발급되게 한다.
        val grpB = group(locZ, "B그룹") // grpId=1
        val grpA = group(locZ, "A그룹") // grpId=2
        val grpInLocA = group(locA, "위치A의 그룹") // grpId=3

        // grpB 안에서 laneNo를 발급 순서와 반대로 줘, dtlId(1) < dtlId(2)인데 laneNo(9) > laneNo(1)이 되게 한다.
        val d1 = detail(locZ, grpB, ip = "10.0.0.1", laneNo = 9) // dtlId=1
        val d2 = detail(locZ, grpB, ip = "10.0.0.2", laneNo = 1) // dtlId=2
        val d3 = detail(locZ, grpA, ip = "10.0.0.3", laneNo = 1) // dtlId=3
        val d4 = detail(locA, grpInLocA, ip = "10.0.0.4", laneNo = 1) // dtlId=4

        // 사용/분석 조건 미충족 — 결과에서 제외돼야 한다.
        detail(locZ, grpB, ip = "10.0.0.5", laneNo = 2, useYn = false)
        detail(locZ, grpB, ip = "10.0.0.6", laneNo = 3, analysisYn = false)
        entityManager.clear()

        val result = repository.findAllForTree()

        assertEquals(listOf(d1.dtlId, d2.dtlId, d3.dtlId, d4.dtlId), result.map { it.dtlId })
    }
}
