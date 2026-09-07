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

    /**
     * `dtl_type`/`dtl_id`/`server_cd`/`snd_raw` 조회 전용 매핑 회귀 테스트(2026-09-07 재점검 —
     * [NetStateRepository.upsertIfNewer] KDoc "컬럼 누락 수정 2" 참고). 네 컬럼 모두 JPA로 직접
     * INSERT하지 않는(엔티티는 조회 전용) 실제 값이므로, 네이티브 UPDATE로 채운 뒤 재조회해
     * 엔티티 매핑 자체가 깨지지 않았는지만 검증한다 — `upsertIfNewer`의 조건부 UPSERT 로직 자체는
     * MariaDB 전용 문법이라 이 테스트로 검증할 수 없다(같은 클래스 KDoc의 한계 설명 참고).
     */
    @Test
    fun `dtl_type-dtl_id-server_cd-snd_raw는 조회 전용으로 매핑되어 재조회 시 값이 반영된다`() {
        val saved = entityManager.persistAndFlush(sample())
        entityManager.entityManager
            .createNativeQuery(
                "UPDATE tb_net_state SET dtl_type = 1, dtl_id = 42, server_cd = 'SERVER', snd_raw = 'AABBCC' " +
                    "WHERE dtl_ip = :dtlIp AND dtl_lane_no = :dtlLaneNo AND loc_id = :locId AND grp_id = :grpId",
            )
            .setParameter("dtlIp", saved.id.dtlIp)
            .setParameter("dtlLaneNo", saved.id.dtlLaneNo)
            .setParameter("locId", saved.id.locId)
            .setParameter("grpId", saved.id.grpId)
            .executeUpdate()
        entityManager.clear()

        val reloaded = entityManager.find(NetState::class.java, saved.id)!!

        assertEquals(1, reloaded.dtlType)
        assertEquals(42L, reloaded.dtlId)
        assertEquals("SERVER", reloaded.serverCd)
        assertEquals("AABBCC", reloaded.sndRaw)
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
