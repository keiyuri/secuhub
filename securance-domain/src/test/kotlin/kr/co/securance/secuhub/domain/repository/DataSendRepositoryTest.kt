package kr.co.securance.secuhub.domain.repository

import kr.co.securance.secuhub.domain.entity.DataSend
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager
import org.springframework.data.domain.PageRequest
import org.springframework.test.context.TestPropertySource
import java.time.LocalDateTime

/**
 * [Codex 적대적 리뷰 회귀 테스트] `findEligiblePending`이 실제 JPA 프로바이더 위에서 `next_attempt_at`
 * 조건을 정확히 반영하는지 검증한다 — 배치 조회가 항상 `snd_id` 오름차순 첫 페이지만 보는 구조라, 큐
 * 앞쪽의 계속 실패하는 행을 조회 조건에서 배제하지 못하면 뒤쪽 정상 행이 영구히 처리되지 못한다
 * (헤드 오브 라인 차단). [DataSendRepository]는 `SendControlJob`의 목 기반 단위 테스트로만 검증되고
 * 있었는데, 그 테스트들은 리포지토리 자체를 목으로 대체하므로 실제 JPQL/`@Query`가 올바른지는
 * 검증하지 못한다 — 이 테스트가 그 공백을 메운다.
 */
@DataJpaTest
@TestPropertySource(
    properties = [
        "spring.flyway.enabled=false", // classpath:db/migration은 MariaDB 전용 문법이라 H2에서 실행 불가
        "spring.jpa.hibernate.ddl-auto=none", // schema.sql로 직접 스키마를 만들므로 Hibernate 자동 DDL을 끈다
    ],
)
class DataSendRepositoryTest {

    @Autowired
    private lateinit var entityManager: TestEntityManager

    @Autowired
    private lateinit var repository: DataSendRepository

    private fun row(dtlIp: String, nextAttemptAt: LocalDateTime? = null) = DataSend(
        sndDate = "20260807120000",
        sndYn = "N",
        chkYn = "N",
        nextAttemptAt = nextAttemptAt,
        dtlIp = dtlIp,
        dtlLaneNo = 1,
        sndRaw = "0200",
    )

    @Test
    fun `next_attempt_at이 미래인 행은 조회 대상에서 제외되고, 뒤쪽 행이 대신 조회된다`() {
        // 큐 헤드 오브 라인 차단 회귀 검증 — 앞쪽(작은 snd_id)에 재시도 쿨다운 중인 행이 있어도
        // 뒤쪽 행이 배치에 포함되어야 한다.
        val now = LocalDateTime.of(2026, 8, 7, 12, 0)
        val stuck = entityManager.persistAndFlush(row("192.168.0.10", nextAttemptAt = now.plusSeconds(5)))
        val fresh = entityManager.persistAndFlush(row("192.168.0.11"))
        entityManager.clear()

        val result = repository.findEligiblePending("N", "N", now, PageRequest.of(0, 10))

        assertEquals(1, result.size)
        assertEquals(fresh.dtlIp, result.first().dtlIp)
        assertEquals(false, result.any { it.sndId == stuck.sndId })
    }

    @Test
    fun `next_attempt_at이 지나면 다시 조회 대상에 포함된다`() {
        val now = LocalDateTime.of(2026, 8, 7, 12, 0)
        val saved = entityManager.persistAndFlush(row("192.168.0.12", nextAttemptAt = now.minusSeconds(1)))
        entityManager.clear()

        val result = repository.findEligiblePending("N", "N", now, PageRequest.of(0, 10))

        assertEquals(1, result.size)
        assertEquals(saved.sndId, result.first().sndId)
    }

    @Test
    fun `next_attempt_at이 null이면 즉시 조회 대상에 포함된다`() {
        val now = LocalDateTime.of(2026, 8, 7, 12, 0)
        val saved = entityManager.persistAndFlush(row("192.168.0.13"))
        entityManager.clear()

        val result = repository.findEligiblePending("N", "N", now, PageRequest.of(0, 10))

        assertEquals(1, result.size)
        assertEquals(saved.sndId, result.first().sndId)
    }

    @Test
    fun `조회 결과는 snd_id 오름차순으로 정렬된다`() {
        val now = LocalDateTime.of(2026, 8, 7, 12, 0)
        val earlier = entityManager.persistAndFlush(row("192.168.0.21"))
        val later = entityManager.persistAndFlush(row("192.168.0.20"))
        entityManager.clear()

        val result = repository.findEligiblePending("N", "N", now, PageRequest.of(0, 10))

        assertEquals(listOf(earlier.sndId, later.sndId), result.map { it.sndId })
    }
}
