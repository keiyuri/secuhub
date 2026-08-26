package kr.co.securance.secuhub.web.control

import kr.co.securance.secuhub.TestJpaApplication
import kr.co.securance.secuhub.domain.entity.DataSend
import kr.co.securance.secuhub.domain.repository.DataSendRepository
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
 * [ControlHistoryFilter.toSpecification] 회귀 테스트 — `/control/history` 화면의 유일한 필터
 * 조립 로직인데도 이전까지 테스트가 전혀 없었다(2026-08-25 소스 전수 검토 지적). `EventSearchFilterTest`와
 * 동일하게 `@DataJpaTest`로 실제 Specification을 실행해, 컬럼명 오타나 조건 누락이 있으면 즉시 실패하도록 한다.
 *
 * 이 파일은 `web.control` 패키지에 있어 `web.report`의 `AccessReportControllerTest`/
 * `TestReportWebApp`과는 애초에 패키지 상속 관계가 아니다(`@DataJpaTest`의 설정 탐색은 패키지
 * 계층을 위로만 올라가고 형제 패키지는 보지 않는다) — `@ContextConfiguration` 없이도 충돌 없이
 * `kr.co.securance.secuhub`(루트) 패키지의 [TestJpaApplication]을 그대로 찾을 것이다. 다만
 * `EventSearchFilterTest`/`GateLogSearchFilterTest`와 동일한 패턴을 유지해 어느 패키지로 옮기더라도
 * 안전하도록 명시적으로 선언해 둔다.
 */
@DataJpaTest
@ContextConfiguration(classes = [TestJpaApplication::class])
@TestPropertySource(
    properties = [
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
    ],
)
class ControlHistoryFilterTest {

    @Autowired
    private lateinit var entityManager: TestEntityManager

    @Autowired
    private lateinit var repository: DataSendRepository

    private fun sample(
        sndDate: String,
        dtlIp: String = "192.168.0.10",
        sndTypeCd: String = "01",
        sndYn: String = "Y",
        chkYn: String = "Y",
    ) = DataSend(
        sndDate = sndDate,
        dtlIp = dtlIp,
        dtlLaneNo = 1,
        sndTypeCd = sndTypeCd,
        sndYn = sndYn,
        chkYn = chkYn,
    )

    private fun search(filter: ControlHistoryFilter) =
        repository.findAll(filter.toSpecification(), PageRequest.of(0, 10, Sort.by("sndId")))

    @Test
    fun `기간 조건은 sndDate 문자열 범위로 걸러낸다`() {
        val inRange = entityManager.persistAndFlush(sample(sndDate = "20260814120000"))
        entityManager.persistAndFlush(sample(sndDate = "20260810120000")) // 범위 밖 — 제외돼야 함
        entityManager.persistAndFlush(sample(sndDate = "20260816120000")) // 범위 밖 — 제외돼야 함
        entityManager.clear()

        val result = search(
            ControlHistoryFilter(
                fromDate = LocalDate.of(2026, 8, 14),
                toDate = LocalDate.of(2026, 8, 14),
            ),
        )

        assertEquals(1, result.totalElements)
        assertEquals(inRange.sndId, result.content.single().sndId)
    }

    @Test
    fun `dtlIp를 지정하면 해당 IP만 조회된다`() {
        val target = entityManager.persistAndFlush(sample(sndDate = "20260814120000", dtlIp = "192.168.0.10"))
        entityManager.persistAndFlush(sample(sndDate = "20260814130000", dtlIp = "192.168.0.11"))
        entityManager.clear()

        val result = search(
            ControlHistoryFilter(
                dtlIp = "192.168.0.10",
                fromDate = LocalDate.of(2026, 8, 14),
                toDate = LocalDate.of(2026, 8, 14),
            ),
        )

        assertEquals(1, result.totalElements)
        assertEquals(target.sndId, result.content.single().sndId)
    }

    @Test
    fun `sndYn과 chkYn을 함께 지정하면 두 조건 모두 만족하는 행만 조회된다`() {
        val pending = entityManager.persistAndFlush(
            sample(sndDate = "20260814120000", sndYn = "N", chkYn = "N"),
        )
        entityManager.persistAndFlush(sample(sndDate = "20260814130000", sndYn = "Y", chkYn = "N"))
        entityManager.persistAndFlush(sample(sndDate = "20260814140000", sndYn = "Y", chkYn = "Y"))
        entityManager.clear()

        val result = search(
            ControlHistoryFilter(
                sndYn = "N",
                chkYn = "N",
                fromDate = LocalDate.of(2026, 8, 14),
                toDate = LocalDate.of(2026, 8, 14),
            ),
        )

        assertEquals(1, result.totalElements)
        assertEquals(pending.sndId, result.content.single().sndId)
    }

    @Test
    fun `필터를 지정하지 않으면 기간 내 전체가 조회된다`() {
        entityManager.persistAndFlush(sample(sndDate = "20260814120000", dtlIp = "192.168.0.10"))
        entityManager.persistAndFlush(sample(sndDate = "20260814130000", dtlIp = "192.168.0.11"))
        entityManager.clear()

        val result = search(
            ControlHistoryFilter(
                fromDate = LocalDate.of(2026, 8, 14),
                toDate = LocalDate.of(2026, 8, 14),
            ),
        )

        assertEquals(2, result.totalElements)
    }
}
