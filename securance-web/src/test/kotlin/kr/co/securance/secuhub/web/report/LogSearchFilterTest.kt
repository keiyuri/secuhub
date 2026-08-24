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
 * [LogSearchFilter.toSpecification] 회귀 테스트(2026-08-25 소스 전수 검토 지적 — `/reports/logs`
 * 화면은 이 클래스 KDoc이 직접 언급하는 두 건의 실제 조회 실패 버그(`docs/작업일지.md` 0030 참고:
 * ①`descFireAlarm` 등 존재하지 않는 속성명으로 `Could not resolve attribute` 예외, ②`desc_*`가
 * `NOT NULL DEFAULT ''`라 `isNotNull`이 죽은 코드였던 것)를 겪었는데도 이 필터 자체를 검증하는
 * 테스트가 하나도 없었다 — 두 버그 모두 재발하면 이 테스트가 즉시 잡는다.
 */
@DataJpaTest
@ContextConfiguration(classes = [TestJpaApplication::class])
@TestPropertySource(
    properties = [
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
    ],
)
class LogSearchFilterTest {

    @Autowired
    private lateinit var entityManager: TestEntityManager

    @Autowired
    private lateinit var repository: DataReceiveAnalysisRepository

    private fun sample(
        analTp: String = "NOR",
        dtlIp: String = "192.168.0.10",
        descGateStatus07: String = "",
    ) = DataReceiveAnalysis(
        analDate = "202608140000",
        analTp = analTp,
        dtlIp = dtlIp,
        dtlLaneNo = 1,
        rcvDate = "202608140000",
        descGateStatus07 = descGateStatus07,
    )

    private fun search(filter: LogSearchFilter) =
        repository.findAll(filter.toSpecification(), PageRequest.of(0, 10, Sort.by("analId")))

    private fun defaultFilter(
        locId: Long? = null,
        grpId: Long? = null,
        dtlIp: String? = null,
        analType: String? = null,
    ) = LogSearchFilter(
        locId = locId,
        grpId = grpId,
        dtlIp = dtlIp,
        analType = analType,
        fromDate = LocalDate.of(2026, 8, 14),
        toDate = LocalDate.of(2026, 8, 14),
    )

    @Test
    fun `desc 컬럼이 하나라도 채워진 행만 조회되고 전부 빈 행은 제외된다`() {
        val described = entityManager.persistAndFlush(sample(descGateStatus07 = "화재감지"))
        entityManager.persistAndFlush(sample(descGateStatus07 = "")) // 전체 desc_* 공란 — 제외돼야 함
        entityManager.clear()

        val result = search(defaultFilter())

        assertEquals(1, result.totalElements)
        assertEquals(described.analId, result.content.single().analId)
    }

    @Test
    fun `analType 필터는 anal_type이 아니라 anal_tp 컬럼으로 걸러낸다`() {
        val plm = entityManager.persistAndFlush(sample(analTp = "PLM", descGateStatus07 = "장애"))
        entityManager.persistAndFlush(sample(analTp = "NOR", descGateStatus07 = "정상")) // 필터 대상 밖 — 제외돼야 함
        entityManager.clear()

        val result = search(defaultFilter(analType = "PLM"))

        assertEquals(1, result.totalElements)
        assertEquals(plm.analId, result.content.single().analId)
    }

    @Test
    fun `dtlIp를 지정하면 해당 IP만 조회된다`() {
        val target = entityManager.persistAndFlush(sample(dtlIp = "192.168.0.10", descGateStatus07 = "화재감지"))
        entityManager.persistAndFlush(sample(dtlIp = "192.168.0.11", descGateStatus07 = "화재감지"))
        entityManager.clear()

        val result = search(defaultFilter(dtlIp = "192.168.0.10"))

        assertEquals(1, result.totalElements)
        assertEquals(target.analId, result.content.single().analId)
    }

    @Test
    fun `기간 밖의 행은 desc가 채워져 있어도 제외된다`() {
        entityManager.persistAndFlush(
            sample(descGateStatus07 = "화재감지").apply { analDate = "202608130000" },
        )
        entityManager.clear()

        val result = search(defaultFilter())

        assertEquals(0, result.totalElements)
    }
}
