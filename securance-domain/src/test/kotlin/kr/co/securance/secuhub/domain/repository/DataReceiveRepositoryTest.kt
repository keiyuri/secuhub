package kr.co.securance.secuhub.domain.repository

import kr.co.securance.secuhub.domain.entity.DataReceive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager
import org.springframework.test.context.TestPropertySource

/**
 * [DataReceiveRepository.deleteBatchOlderThan] 검증 — D5 데이터 보관 정책(2026-08-12,
 * `docs/작업일지.md` 참고)의 배치 삭제 네이티브 쿼리가 실제 JPA 프로바이더 위에서 정상 동작하는지
 * 확인한다. `LIMIT`이 있는 네이티브 DELETE라 목 기반 테스트만으로는 문법 오류를 잡을 수 없다.
 */
@DataJpaTest
@TestPropertySource(
    properties = [
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
    ],
)
class DataReceiveRepositoryTest {

    @Autowired
    private lateinit var entityManager: TestEntityManager

    @Autowired
    private lateinit var repository: DataReceiveRepository

    private fun sample(rcvDate: String) = DataReceive(
        rcvDate = rcvDate,
        dtlIp = "192.168.0.10",
        dtlLaneNo = 1,
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

    @Test
    fun `지울 행이 없으면 0을 반환한다`() {
        entityManager.persistAndFlush(sample("202601010000"))
        entityManager.clear()

        val deleted = repository.deleteBatchOlderThan("202508120000", 100)

        assertEquals(0, deleted)
        assertEquals(1, repository.findAll().size)
    }
}
