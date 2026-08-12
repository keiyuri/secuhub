package kr.co.securance.secuhub.domain.repository

import kr.co.securance.secuhub.domain.entity.DataReceiveAnalysis
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager
import org.springframework.test.context.TestPropertySource

/**
 * [DataReceiveAnalysisRepository.deleteBatchOlderThan] 검증 — D5 데이터 보관 정책(2026-08-12,
 * `docs/작업일지.md` 참고). [DataReceiveRepositoryTest]와 동일한 이유로 별도 파일로 분리했다
 * (기존 `DataReceiveAnalysisRepositoryTest`는 없고, 관련 리포지토리 메서드는 `AccessReportController`
 * 등에서 통합 검증되는 구조라 이 메서드만 최소 커버리지를 추가한다).
 */
@DataJpaTest
@TestPropertySource(
    properties = [
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
    ],
)
class DataReceiveAnalysisRepositoryDeleteBatchTest {

    @Autowired
    private lateinit var entityManager: TestEntityManager

    @Autowired
    private lateinit var repository: DataReceiveAnalysisRepository

    private fun sample(analDate: String) = DataReceiveAnalysis(
        analDate = analDate,
        analTp = "NOR",
        dtlIp = "192.168.0.10",
        dtlLaneNo = 1,
        rcvDate = analDate,
    )

    @Test
    fun `컷오프보다 오래된 행만 지운다`() {
        entityManager.persistAndFlush(sample("202501010000")) // 오래됨 — 삭제 대상
        entityManager.persistAndFlush(sample("202601010000")) // 최근 — 유지
        entityManager.clear()

        val deleted = repository.deleteBatchOlderThan("202508120000", 100)

        assertEquals(1, deleted)
        assertEquals(1, repository.findAll().size)
    }

    @Test
    fun `한 번에 배치 크기만큼만 지운다`() {
        repeat(5) { entityManager.persistAndFlush(sample("202501010000")) }
        entityManager.clear()

        val deleted = repository.deleteBatchOlderThan("202508120000", 3)

        assertEquals(3, deleted)
        assertEquals(2, repository.findAll().size)
    }
}
