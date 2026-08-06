package kr.co.securance.secuhub.domain.repository

import kr.co.securance.secuhub.domain.entity.DataReceiveAnalysis
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager
import org.springframework.test.context.TestPropertySource

/**
 * `resolveUnresolvedErrors`(SendControlJob의 RESET 명령 성공 후 오류 resolve 처리)를 실제
 * Hibernate/JPA 위에서 검증한다.
 *
 * 이 테스트를 추가하기 전까지는 이 `@Modifying @Query` JPQL이 컴파일은 됐지만 실제 실행된 적이
 * 한 번도 없었다 — `SendControlJobTest`는 `DataReceiveAnalysisRepository`를 Mockito로 목 처리해
 * 이 쿼리의 실행 경로를 우회했기 때문이다. 임베디드 H2로 최소한 "JPQL이 실제로 파싱/실행되는가"를
 * 검증한다(스키마는 `schema.sql` 참고 — 운영 DDL과 100% 동일하지 않음에 주의).
 */
@DataJpaTest
@TestPropertySource(
    properties = [
        "spring.flyway.enabled=false", // classpath:db/migration은 MariaDB 전용 문법이라 H2에서 실행 불가
        "spring.jpa.hibernate.ddl-auto=none", // schema.sql로 직접 스키마를 만들므로 Hibernate 자동 DDL을 끈다
    ],
)
class DataReceiveAnalysisRepositoryTest {

    @Autowired
    private lateinit var entityManager: TestEntityManager

    @Autowired
    private lateinit var repository: DataReceiveAnalysisRepository

    private fun errorRow(dtlIp: String, resolveYn: String = "N", errType: Int? = 3) = DataReceiveAnalysis(
        analDate = "202608061200",
        analType = "PLM",
        dtlIp = dtlIp,
        dtlLaneNo = 1,
        errType = errType,
        resolveYn = resolveYn,
    )

    @Test
    fun `해당 IP의 미해결 오류만 resolve 처리하고 건수를 반환한다`() {
        val target = entityManager.persistAndFlush(errorRow(dtlIp = "192.168.0.10"))
        val alreadyResolved = entityManager.persistAndFlush(errorRow(dtlIp = "192.168.0.10", resolveYn = "Y"))
        val otherIp = entityManager.persistAndFlush(errorRow(dtlIp = "192.168.0.11"))
        val notAnError = entityManager.persistAndFlush(errorRow(dtlIp = "192.168.0.10", errType = null))
        entityManager.clear()

        val resolvedCount = repository.resolveUnresolvedErrors(
            dtlIp = "192.168.0.10",
            resolveUser = "operator1",
            resolveDate = java.time.LocalDateTime.of(2026, 8, 6, 12, 30),
        )

        assertEquals(1, resolvedCount)

        val refetchedTarget = repository.findById(target.analId!!).get()
        assertEquals("Y", refetchedTarget.resolveYn)
        assertEquals("operator1", refetchedTarget.resolveUser)
        assertEquals(java.time.LocalDateTime.of(2026, 8, 6, 12, 30), refetchedTarget.resolveDate)

        // 이미 해결됨/다른 IP/오류 타입이 아닌 행은 건드리지 않는다.
        assertEquals("Y", repository.findById(alreadyResolved.analId!!).get().resolveYn)
        assertNull(repository.findById(alreadyResolved.analId!!).get().resolveUser)
        assertEquals("N", repository.findById(otherIp.analId!!).get().resolveYn)
        assertEquals("N", repository.findById(notAnError.analId!!).get().resolveYn)
    }

    @Test
    fun `미해결 오류가 없으면 0을 반환한다`() {
        entityManager.persistAndFlush(errorRow(dtlIp = "192.168.0.20", resolveYn = "Y"))
        entityManager.clear()

        val resolvedCount = repository.resolveUnresolvedErrors(
            dtlIp = "192.168.0.20",
            resolveUser = "operator1",
            resolveDate = java.time.LocalDateTime.now(),
        )

        assertEquals(0, resolvedCount)
    }

    @Test
    fun `findRecentUnresolvedErrors도 동일한 임베디드 DB 위에서 정상 동작한다`() {
        val saved = entityManager.persistAndFlush(errorRow(dtlIp = "192.168.0.30"))
        // has_error_event는 운영 DB(MariaDB)에서는 VIRTUAL 생성 컬럼이라 JPA로 직접 쓸 수 없다
        // (엔티티도 insertable=false로 매핑됨). 이 테스트 스키마(schema.sql)에는 그 계산 로직이
        // 없으므로, 쿼리 대상이 되도록 네이티브 SQL로 직접 세팅한다.
        entityManager.entityManager
            .createNativeQuery("UPDATE tb_data_rcv_anal SET has_error_event = TRUE WHERE anal_id = :id")
            .setParameter("id", saved.analId)
            .executeUpdate()
        entityManager.clear()

        val result = repository.findRecentUnresolvedErrors(
            sinceDate = "202608060000",
            pageable = org.springframework.data.domain.PageRequest.of(0, 10),
        )

        assertEquals(1, result.size)
        assertNotNull(result.first().analId)
    }
}
