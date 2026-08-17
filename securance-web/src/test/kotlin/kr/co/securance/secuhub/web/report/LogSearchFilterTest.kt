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
 * [LogSearchFilter.toSpecification] 회귀 테스트 — [EventSearchFilterTest]와 동일한 버그
 * (`anal_type` 대신 `anal_tp`를 봐야 하는 "유형" 필터, 2026-08-14, `docs/작업일지.md` 0030 참고)를
 * #10 SR_F_ViewLog(통신/운영 로그 조회) 화면에서 재발 방지한다. `@ContextConfiguration` 사유는
 * [EventSearchFilterTest] KDoc 참고(같은 `report` 패키지의 `TestReportWebApp` 충돌 회피).
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

    /** [LogSearchFilter]는 desc_* 컬럼 중 하나라도 채워진 행만 조회한다(레거시 SelectGateLog와 동일).
     * `descFireAlarm`은 `descGateStatus07`의 읽기 전용 별칭(getter)이라 생성자에는 원본 컬럼을 채운다. */
    private fun sample(analTp: String) = DataReceiveAnalysis(
        analDate = "202608140000",
        analTp = analTp,
        dtlIp = "192.168.0.10",
        dtlLaneNo = 1,
        rcvDate = "202608140000",
        descGateStatus07 = "화재 감지",
    )

    @Test
    fun `analType 필터는 anal_tp 컬럼으로 걸러낸다`() {
        val nor = entityManager.persistAndFlush(sample(analTp = "NOR"))
        entityManager.persistAndFlush(sample(analTp = "PLM")) // 필터 대상 밖 — 제외돼야 함
        entityManager.clear()

        val filter = LogSearchFilter(
            analType = "NOR",
            fromDate = LocalDate.of(2026, 8, 14),
            toDate = LocalDate.of(2026, 8, 14),
        )
        val result = repository.findAll(filter.toSpecification(), PageRequest.of(0, 10, Sort.by("analId")))

        assertEquals(1, result.totalElements)
        assertEquals(nor.analId, result.content.single().analId)
    }

    @Test
    fun `desc_ 컬럼이 전부 빈 문자열인 행은 조회에서 제외한다`() {
        // 회귀 방지: descFireAlarm 등 getter 별칭을 조건에 걸면 예외가 났고(엔티티 메타모델에
        // 없는 속성), 실제 컬럼(descGateStatus07 등)을 걸어도 isNotNull이면 NOT NULL DEFAULT ''라
        // 항상 참이라 이 필터가 사실상 죽어있었다 — 둘 다 실제로 걸러지는지 확인한다.
        entityManager.persistAndFlush(
            DataReceiveAnalysis(
                analDate = "202608140000",
                analTp = "NOR",
                dtlIp = "192.168.0.10",
                dtlLaneNo = 1,
                rcvDate = "202608140000",
                // desc_* 전부 기본값('')으로 남겨 "설명 없는 행"을 재현한다.
            ),
        )
        entityManager.clear()

        val filter = LogSearchFilter(
            analType = null,
            fromDate = LocalDate.of(2026, 8, 14),
            toDate = LocalDate.of(2026, 8, 14),
        )
        val result = repository.findAll(filter.toSpecification(), PageRequest.of(0, 10, Sort.by("analId")))

        assertEquals(0, result.totalElements)
    }
}
