package kr.co.securance.secuhub.web.report

import kr.co.securance.secuhub.TestJpaApplication
import kr.co.securance.secuhub.domain.entity.GateLog
import kr.co.securance.secuhub.domain.repository.GateLogRepository
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
import java.time.LocalDateTime

/**
 * [GateLogSearchFilter.toSpecification] 회귀 테스트(2026-08-25 소스 전수 검토 지적 — `/reports/gate-logs`
 * 화면의 필터 조립 로직이 테스트 전무였다). [EventSearchFilterTest]/`ControlHistoryFilterTest`와
 * 동일한 `@DataJpaTest` 패턴을 따른다.
 *
 * `fromDate`/`toDate`가 문자열이 아닌 [LocalDateTime]("event_time" 컬럼) 범위인 것이 다른
 * 리포트 필터들과의 핵심 차이라, 하루 경계(`toDate.plusDays(1).atStartOfDay()` 미만)가
 * 실제로 배타적으로 동작하는지를 별도로 검증한다.
 */
@DataJpaTest
@ContextConfiguration(classes = [TestJpaApplication::class])
@TestPropertySource(
    properties = [
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
    ],
)
class GateLogSearchFilterTest {

    @Autowired
    private lateinit var entityManager: TestEntityManager

    @Autowired
    private lateinit var repository: GateLogRepository

    private fun sample(
        eventTime: LocalDateTime,
        dtlIp: String = "192.168.0.10",
        dtlLaneNo: Int = 1,
        eventType: Int = 1,
    ) = GateLog(
        dtlIp = dtlIp,
        dtlLaneNo = dtlLaneNo,
        eventType = eventType,
        objectCode = 0x61,
        code = 0,
        errCode = 0,
        operationMode = 0,
        readerType = 0,
        readerNumber = 0,
        doorStatus = 0,
        functionCode = 0,
        eventTime = eventTime,
    )

    private fun search(filter: GateLogSearchFilter) =
        repository.findAll(filter.toSpecification(), PageRequest.of(0, 10, Sort.by("logId")))

    @Test
    fun `toDate 당일 마지막 순간까지는 포함하고 다음날은 제외한다`() {
        val lastMoment = entityManager.persistAndFlush(sample(eventTime = LocalDateTime.of(2026, 8, 14, 23, 59, 59)))
        entityManager.persistAndFlush(sample(eventTime = LocalDateTime.of(2026, 8, 15, 0, 0, 0))) // 다음날 — 제외돼야 함
        entityManager.clear()

        val result = search(
            GateLogSearchFilter(
                fromDate = LocalDate.of(2026, 8, 14),
                toDate = LocalDate.of(2026, 8, 14),
            ),
        )

        assertEquals(1, result.totalElements)
        assertEquals(lastMoment.logId, result.content.single().logId)
    }

    @Test
    fun `dtlLaneNo를 지정하면 해당 레인만 조회된다`() {
        val target = entityManager.persistAndFlush(
            sample(eventTime = LocalDateTime.of(2026, 8, 14, 10, 0), dtlLaneNo = 2),
        )
        entityManager.persistAndFlush(sample(eventTime = LocalDateTime.of(2026, 8, 14, 11, 0), dtlLaneNo = 3))
        entityManager.clear()

        val result = search(
            GateLogSearchFilter(
                dtlLaneNo = 2,
                fromDate = LocalDate.of(2026, 8, 14),
                toDate = LocalDate.of(2026, 8, 14),
            ),
        )

        assertEquals(1, result.totalElements)
        assertEquals(target.logId, result.content.single().logId)
    }

    @Test
    fun `eventType을 지정하면 해당 유형만 조회된다`() {
        val target = entityManager.persistAndFlush(
            sample(eventTime = LocalDateTime.of(2026, 8, 14, 10, 0), eventType = 5),
        )
        entityManager.persistAndFlush(sample(eventTime = LocalDateTime.of(2026, 8, 14, 11, 0), eventType = 9))
        entityManager.clear()

        val result = search(
            GateLogSearchFilter(
                eventType = 5,
                fromDate = LocalDate.of(2026, 8, 14),
                toDate = LocalDate.of(2026, 8, 14),
            ),
        )

        assertEquals(1, result.totalElements)
        assertEquals(target.logId, result.content.single().logId)
    }
}
