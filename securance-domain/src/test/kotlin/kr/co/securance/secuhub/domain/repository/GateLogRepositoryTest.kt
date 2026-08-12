package kr.co.securance.secuhub.domain.repository

import kr.co.securance.secuhub.domain.entity.GateLog
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager
import org.springframework.test.context.TestPropertySource
import java.time.LocalDateTime

/**
 * [GateLogRepository.existsByDtlIpAndDtlLaneNoAndEventTimeAndEventTypeAndCodeAndErrCodeAndFunctionCode]가
 * `GateLogService`(securance-server)가 기대하는 자연키 조합을 정확히 조회하는지 실제 JPA 프로바이더
 * 위에서 검증한다 — `GateDbWriteQueue`의 멱등성 요구사항(find-or-create)을 지키기 위한 핵심 쿼리라
 * 목 기반 테스트만으로는 JPQL 오탈자/필드 순서 오류를 잡을 수 없다.
 */
@DataJpaTest
@TestPropertySource(
    properties = [
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
    ],
)
class GateLogRepositoryTest {

    @Autowired
    private lateinit var entityManager: TestEntityManager

    @Autowired
    private lateinit var repository: GateLogRepository

    private val sampleTime: LocalDateTime = LocalDateTime.of(2025, 8, 5, 15, 0, 25)

    private fun sample(dtlLaneNo: Int = 0) = GateLog(
        dtlIp = "192.168.0.50",
        dtlLaneNo = dtlLaneNo,
        eventType = 0x18,
        objectCode = 0x01,
        code = 0x05,
        errCode = 0x0B,
        operationMode = 0x05,
        readerType = 0x00,
        readerNumber = 0,
        doorStatus = 0x01,
        functionCode = 255,
        eventTime = sampleTime,
        userData1 = "000000000000000000000000",
        userData2 = "0000000000000000",
    )

    @Test
    fun `동일 자연키 조합이 이미 저장돼 있으면 true를 반환한다`() {
        entityManager.persistAndFlush(sample())
        entityManager.clear()

        val exists = repository.existsByDtlIpAndDtlLaneNoAndEventTimeAndEventTypeAndCodeAndErrCodeAndFunctionCode(
            "192.168.0.50", 0, sampleTime, 0x18, 0x05, 0x0B, 255,
        )

        assertTrue(exists)
    }

    @Test
    fun `필드 하나라도 다르면 다른 로그로 간주해 false를 반환한다`() {
        entityManager.persistAndFlush(sample())
        entityManager.clear()

        // functionCode만 다름 — 같은 시각/게이트/레인이라도 다른 이벤트로 취급해야 한다.
        val exists = repository.existsByDtlIpAndDtlLaneNoAndEventTimeAndEventTypeAndCodeAndErrCodeAndFunctionCode(
            "192.168.0.50", 0, sampleTime, 0x18, 0x05, 0x0B, 1,
        )

        assertFalse(exists)
    }

    /**
     * [GateLogRepository.deleteBatchOlderThan] 검증 — D5 데이터 보관 정책(2026-08-12,
     * `docs/작업일지.md` 참고). `reg_date`는 DB 기본값(`CURRENT_TIMESTAMP`)이라 애플리케이션에서
     * 직접 지정할 수 없으므로, `entityManager`로 직접 과거 시각을 UPDATE해 오래된 행을 흉내낸다.
     */
    @Test
    fun `reg_date가 컷오프보다 오래된 행만 지운다`() {
        val old = sample(dtlLaneNo = 0) // 곧 과거로 되돌릴 행
        val recent = sample(dtlLaneNo = 1) // 방금 적재된 행(유지 대상, 자연키 충돌 방지용으로 레인만 다르게)
        entityManager.persistAndFlush(old)
        entityManager.persistAndFlush(recent)
        entityManager.entityManager
            .createNativeQuery("UPDATE tb_gate_log SET reg_date = '2025-01-01 00:00:00' WHERE log_id = ${old.logId}")
            .executeUpdate()
        entityManager.clear()

        val deleted = repository.deleteBatchOlderThan(LocalDateTime.of(2026, 1, 1, 0, 0), 100)

        assertTrue(deleted == 1)
        assertTrue(repository.findAll().size == 1)
    }
}
