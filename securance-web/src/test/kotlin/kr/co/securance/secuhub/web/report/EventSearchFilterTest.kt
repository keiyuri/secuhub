package kr.co.securance.secuhub.web.report

import kr.co.securance.secuhub.TestJpaApplication
import kr.co.securance.secuhub.domain.entity.DataReceiveAnalysis
import kr.co.securance.secuhub.domain.repository.DataReceiveAnalysisRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.TestPropertySource
import java.time.LocalDate

/**
 * [EventSearchFilter.toSpecification] 회귀 테스트 — #9 SR_F_ViewEvent(이벤트 이력 조회) 화면의
 * "유형" 드롭다운(NOR/EVT/PLM/STA)이 실수로 파티션 키 `anal_tp` 대신 이름이 비슷한 `anal_type`
 * (레거시가 항상 `'B'`만 넣는 무관한 컬럼)을 조건으로 걸었던 버그(2026-08-14,
 * `docs/작업일지.md` 0030 참고 — `DashboardPushService`와 동일한 근본 원인) 재발을 막는다.
 * 이 버그가 있으면 "유형" 필터를 하나라도 선택하는 순간 결과가 항상 0건이었다.
 *
 * 같은 `report` 패키지의 [AccessReportControllerTest]가 private `TestReportWebApp`
 * (`@SpringBootConfiguration`)을 두고 있어, `@DataJpaTest`의 기본 설정 탐색(테스트 패키지에서
 * 위로 올라가며 가장 가까운 `@SpringBootConfiguration`을 찾음)이 그쪽을 먼저 찾아 리포지토리가
 * 하나도 등록되지 않는다 — `@ContextConfiguration`으로 [TestJpaApplication]을 명시해 우회한다.
 */
@DataJpaTest
@ContextConfiguration(classes = [TestJpaApplication::class])
@TestPropertySource(
    properties = [
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
    ],
)
class EventSearchFilterTest {

    @Autowired
    private lateinit var entityManager: TestEntityManager

    @Autowired
    private lateinit var repository: DataReceiveAnalysisRepository

    private fun sample(analTp: String) = DataReceiveAnalysis(
        analDate = "202608140000",
        analTp = analTp,
        dtlIp = "192.168.0.10",
        dtlLaneNo = 1,
        rcvDate = "202608140000",
        errType = 3,
        resolveYn = "N",
    )

    @Test
    fun `analType 필터는 anal_tp 컬럼으로 걸러낸다`() {
        val plm = entityManager.persistAndFlush(sample(analTp = "PLM"))
        entityManager.persistAndFlush(sample(analTp = "NOR")) // 필터 대상 밖 — 제외돼야 함
        entityManager.clear()

        val filter = EventSearchFilter(
            analType = "PLM",
            fromDate = LocalDate.of(2026, 8, 14),
            toDate = LocalDate.of(2026, 8, 14),
        )
        val result = repository.findAll(filter.toSpecification(), PageRequest.of(0, 10, Sort.by("analId")))

        assertEquals(1, result.totalElements)
        assertEquals(plm.analId, result.content.single().analId)
    }

    @Test
    fun `analType을 지정하지 않으면 유형 조건 없이 전체를 조회한다`() {
        entityManager.persistAndFlush(sample(analTp = "PLM"))
        entityManager.persistAndFlush(sample(analTp = "STA"))
        entityManager.clear()

        val filter = EventSearchFilter(
            analType = null,
            fromDate = LocalDate.of(2026, 8, 14),
            toDate = LocalDate.of(2026, 8, 14),
        )
        val result = repository.findAll(filter.toSpecification(), PageRequest.of(0, 10, Sort.by("analId")))

        assertEquals(2, result.totalElements)
    }
}
