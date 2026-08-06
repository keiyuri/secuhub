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
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * `resolveMotorErrors`/`resolveSensorErrors`/`resolveGateErrors`(SendControlJob의 RESET 서브타입별
 * 오류 resolve 처리)를 실제 Hibernate/JPA 위에서 검증한다.
 *
 * [Codex 적대적 리뷰 수정] 예전에는 서브타입 구분 없이 `dtlIp`의 미해결 오류 전체를 resolve하는
 * 단일 쿼리(`resolveUnresolvedErrors`)였는데, 모터 RESET 한 번으로 무관한 화재경보 오류까지
 * 사라지는 결함이었다. 이 테스트는 그 회귀를 정확히 잡아낸다 — 같은 IP에 모터 오류와 게이트 오류가
 * 동시에 있을 때 `resolveMotorErrors`가 모터 오류만 resolve하고 게이트 오류는 그대로 두는지 확인한다.
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

    private val today = LocalDate.now()
    private val datePattern = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val sinceDate = today.minusDays(1).format(datePattern) + "0000"
    private val untilDate = today.format(datePattern) + "2359"
    private val analDateNow = today.format(datePattern) + "1200"

    private fun baseRow(dtlIp: String, resolveYn: String = "N", errType: Int? = 3) = DataReceiveAnalysis(
        analDate = analDateNow,
        analType = "PLM",
        dtlIp = dtlIp,
        dtlLaneNo = 1,
        errType = errType,
        resolveYn = resolveYn,
    )

    private fun markHasErrorEvent(analId: Long) {
        // has_error_event는 운영 DB(MariaDB)에서는 VIRTUAL 생성 컬럼이라 JPA로 직접 쓸 수 없다
        // (엔티티도 insertable=false로 매핑됨). 이 테스트 스키마(schema.sql)에는 그 계산 로직이
        // 없으므로, 쿼리 대상이 되도록 네이티브 SQL로 직접 세팅한다.
        entityManager.entityManager
            .createNativeQuery("UPDATE tb_data_rcv_anal SET has_error_event = TRUE WHERE anal_id = :id")
            .setParameter("id", analId)
            .executeUpdate()
    }

    @Test
    fun `resolveMotorErrors는 모터 오류만 resolve하고 같은 IP의 게이트 오류는 건드리지 않는다`() {
        val motorError = entityManager.persistAndFlush(
            baseRow(dtlIp = "192.168.0.10").apply { descMainMotorError = "MOTOR_ERR" },
        )
        val gateError = entityManager.persistAndFlush(baseRow(dtlIp = "192.168.0.10"))
        markHasErrorEvent(gateError.analId!!)
        entityManager.clear()

        val resolvedCount = repository.resolveMotorErrors(
            dtlIp = "192.168.0.10",
            resolveUser = "operator1",
            resolveDate = LocalDateTime.of(2026, 8, 6, 12, 30),
            sinceDate = sinceDate,
            untilDate = untilDate,
        )

        assertEquals(1, resolvedCount)
        assertEquals("Y", repository.findById(motorError.analId!!).get().resolveYn)
        // [핵심 회귀 검증] 같은 IP의 무관한 게이트 오류는 모터 RESET에 영향받지 않아야 한다.
        assertEquals("N", repository.findById(gateError.analId!!).get().resolveYn)
    }

    @Test
    fun `resolveSensorErrors는 운영-안전 센서 컬럼이 채워진 행만 resolve한다`() {
        val sensorError = entityManager.persistAndFlush(
            baseRow(dtlIp = "192.168.0.11").apply { descSafety01 = "SAFETY_ERR" },
        )
        val motorError = entityManager.persistAndFlush(
            baseRow(dtlIp = "192.168.0.11").apply { descMainMotorError = "MOTOR_ERR" },
        )
        entityManager.clear()

        val resolvedCount = repository.resolveSensorErrors(
            dtlIp = "192.168.0.11",
            resolveUser = "operator1",
            resolveDate = LocalDateTime.now(),
            sinceDate = sinceDate,
            untilDate = untilDate,
        )

        assertEquals(1, resolvedCount)
        assertEquals("Y", repository.findById(sensorError.analId!!).get().resolveYn)
        assertEquals("N", repository.findById(motorError.analId!!).get().resolveYn)
    }

    @Test
    fun `resolveGateErrors는 has_error_event가 true인 행만 resolve한다`() {
        val gateError = entityManager.persistAndFlush(baseRow(dtlIp = "192.168.0.12"))
        markHasErrorEvent(gateError.analId!!)
        val motorError = entityManager.persistAndFlush(
            baseRow(dtlIp = "192.168.0.12").apply { descMainMotorError = "MOTOR_ERR" },
        )
        entityManager.clear()

        val resolvedCount = repository.resolveGateErrors(
            dtlIp = "192.168.0.12",
            resolveUser = "operator1",
            resolveDate = LocalDateTime.now(),
            sinceDate = sinceDate,
            untilDate = untilDate,
        )

        assertEquals(1, resolvedCount)
        assertEquals("Y", repository.findById(gateError.analId!!).get().resolveYn)
        assertEquals("N", repository.findById(motorError.analId!!).get().resolveYn)
    }

    @Test
    fun `이미 해결됨 다른 IP 오류가 아닌 행은 resolveMotorErrors 대상에서 제외된다`() {
        val target = entityManager.persistAndFlush(
            baseRow(dtlIp = "192.168.0.13").apply { descMainMotorError = "MOTOR_ERR" },
        )
        val alreadyResolved = entityManager.persistAndFlush(
            baseRow(dtlIp = "192.168.0.13", resolveYn = "Y").apply { descMainMotorError = "MOTOR_ERR" },
        )
        val otherIp = entityManager.persistAndFlush(
            baseRow(dtlIp = "192.168.0.14").apply { descMainMotorError = "MOTOR_ERR" },
        )
        val notAnError = entityManager.persistAndFlush(
            baseRow(dtlIp = "192.168.0.13", errType = null).apply { descMainMotorError = "MOTOR_ERR" },
        )
        entityManager.clear()

        val resolvedCount = repository.resolveMotorErrors(
            dtlIp = "192.168.0.13",
            resolveUser = "operator1",
            resolveDate = LocalDateTime.of(2026, 8, 6, 12, 30),
            sinceDate = sinceDate,
            untilDate = untilDate,
        )

        assertEquals(1, resolvedCount)
        val refetchedTarget = repository.findById(target.analId!!).get()
        assertEquals("Y", refetchedTarget.resolveYn)
        assertEquals("operator1", refetchedTarget.resolveUser)
        assertEquals(LocalDateTime.of(2026, 8, 6, 12, 30), refetchedTarget.resolveDate)

        assertEquals("Y", repository.findById(alreadyResolved.analId!!).get().resolveYn)
        assertNull(repository.findById(alreadyResolved.analId!!).get().resolveUser)
        assertEquals("N", repository.findById(otherIp.analId!!).get().resolveYn)
        assertEquals("N", repository.findById(notAnError.analId!!).get().resolveYn)
    }

    @Test
    fun `미해결 오류가 없으면 0을 반환한다`() {
        entityManager.persistAndFlush(
            baseRow(dtlIp = "192.168.0.20", resolveYn = "Y").apply { descMainMotorError = "MOTOR_ERR" },
        )
        entityManager.clear()

        val resolvedCount = repository.resolveMotorErrors(
            dtlIp = "192.168.0.20",
            resolveUser = "operator1",
            resolveDate = LocalDateTime.now(),
            sinceDate = sinceDate,
            untilDate = untilDate,
        )

        assertEquals(0, resolvedCount)
    }

    @Test
    fun `findRecentUnresolvedErrors도 동일한 임베디드 DB 위에서 정상 동작한다`() {
        val saved = entityManager.persistAndFlush(baseRow(dtlIp = "192.168.0.30"))
        markHasErrorEvent(saved.analId!!)
        entityManager.clear()

        val result = repository.findRecentUnresolvedErrors(
            sinceDate = sinceDate,
            pageable = org.springframework.data.domain.PageRequest.of(0, 10),
        )

        assertEquals(1, result.size)
        assertNotNull(result.first().analId)
    }
}
