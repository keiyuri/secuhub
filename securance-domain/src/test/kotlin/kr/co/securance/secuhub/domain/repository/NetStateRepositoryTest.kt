package kr.co.securance.secuhub.domain.repository

import kr.co.securance.secuhub.domain.entity.NetState
import kr.co.securance.secuhub.domain.entity.NetStateId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager
import org.springframework.test.context.TestPropertySource

/**
 * [NetState]("게이트 연결 상태" 데이터, `tb_net_state`)가 실제 JPA 프로바이더 위에서 컬럼 누락 없이
 * 저장/조회되는지 검증한다(2026-08-26 DB 저장 컬럼 누락 검증 작업). Hibernate는 엔티티가 매핑한
 * 컬럼을 전부 INSERT 문에 포함하므로, 엔티티에는 있는데 스키마에 없는 컬럼이 하나라도 있으면
 * "Column not found"로 이 테스트가 즉시 실패한다.
 *
 * [NetStateRepository.upsertIfNewer]/[NetStateRepository.nextSeq]는 MariaDB 전용 문법
 * (`ON DUPLICATE KEY UPDATE`, `NEXT VALUE FOR`)이라 H2로는 검증할 수 없다 — 그 리포지토리의
 * KDoc에 이미 기록된 한계로, 이 테스트는 엔티티-스키마 컬럼 정합성과 나머지 조회 메서드만 다룬다.
 */
@DataJpaTest
@TestPropertySource(
    properties = [
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
    ],
)
class NetStateRepositoryTest {

    @Autowired
    private lateinit var entityManager: TestEntityManager

    @Autowired
    private lateinit var repository: NetStateRepository

    private fun sample(
        dtlIp: String = "192.168.0.10",
        dtlLaneNo: Int = 1,
        locId: Long = 1,
        grpId: Long = 1,
        dtlState: String = "Y",
    ) = NetState(
        id = NetStateId(dtlIp = dtlIp, dtlLaneNo = dtlLaneNo, locId = locId, grpId = grpId),
        dtlState = dtlState,
        serverIp = "10.0.0.1",
        checkTime = "20260826120000",
        appliedSeq = 1,
    )

    @Test
    fun `엔티티가 매핑한 컬럼 전체가 스키마에 있어 저장과 재조회가 성공한다`() {
        val saved = entityManager.persistAndFlush(sample())
        entityManager.clear()

        val reloaded = entityManager.find(NetState::class.java, saved.id)!!

        assertEquals("Y", reloaded.dtlState)
        assertEquals("10.0.0.1", reloaded.serverIp)
        assertEquals("20260826120000", reloaded.checkTime)
        assertEquals(1L, reloaded.appliedSeq)
        assertTrue(reloaded.isOnline)
    }

    @Test
    fun `findByIdDtlIpAndIdDtlLaneNo는 해당 IP-레인의 모든 레코드를 반환한다`() {
        entityManager.persistAndFlush(sample(locId = 1, grpId = 1))
        entityManager.persistAndFlush(sample(locId = 2, grpId = 2))
        entityManager.clear()

        val found = repository.findByIdDtlIpAndIdDtlLaneNo("192.168.0.10", 1)

        assertEquals(2, found.size)
    }

    @Test
    fun `countByDtlState는 대시보드 온라인-오프라인 위젯이 쓰는 상태별 카운트를 반환한다`() {
        entityManager.persistAndFlush(sample(dtlLaneNo = 1, dtlState = "Y"))
        entityManager.persistAndFlush(sample(dtlLaneNo = 2, dtlState = "N"))
        entityManager.persistAndFlush(sample(dtlLaneNo = 3, dtlState = "Y"))
        entityManager.clear()

        assertEquals(2L, repository.countByDtlState("Y"))
        assertEquals(1L, repository.countByDtlState("N"))
    }

    @Test
    fun `findByIdGrpId는 그룹 내 모든 레인의 연결 상태를 반환한다`() {
        entityManager.persistAndFlush(sample(dtlLaneNo = 1, grpId = 5))
        entityManager.persistAndFlush(sample(dtlLaneNo = 2, grpId = 5))
        entityManager.persistAndFlush(sample(dtlLaneNo = 1, grpId = 9))
        entityManager.clear()

        assertEquals(2, repository.findByIdGrpId(5).size)
    }
}
