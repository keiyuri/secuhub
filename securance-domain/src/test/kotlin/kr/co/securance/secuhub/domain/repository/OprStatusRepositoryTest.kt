package kr.co.securance.secuhub.domain.repository

import kr.co.securance.secuhub.domain.entity.OprStatus
import kr.co.securance.secuhub.domain.entity.OprStatusId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager
import org.springframework.data.domain.PageRequest
import org.springframework.test.context.TestPropertySource

/**
 * [OprStatus]("운영 카운터" 데이터, `tb_opr_status`)가 실제 JPA 프로바이더 위에서 컬럼 누락 없이
 * 저장/조회되는지 검증한다(2026-08-26 DB 저장 컬럼 누락 검증 작업). `OprStatusPersister`가 채우는
 * total/in/out/door 3종 카운터와 dtl_type/dtl_no/opr_gate_type 등 V11/V15/V16이 보강한 컬럼까지
 * 전부 포함해, 엔티티에는 있는데 스키마에 없는 컬럼이 하나라도 있으면 "Column not found"로 이
 * 테스트가 즉시 실패한다.
 */
@DataJpaTest
@TestPropertySource(
    properties = [
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
    ],
)
class OprStatusRepositoryTest {

    @Autowired
    private lateinit var entityManager: TestEntityManager

    @Autowired
    private lateinit var repository: OprStatusRepository

    private fun sample(
        oprDate: String = "202608261200",
        oprSeq: Int = 1,
        dtlIp: String = "192.168.0.10",
        dtlLaneNo: Int = 1,
        dtlId: Long = 100,
        userCount: Int = 3,
        totalCount: Long = 1000,
    ) = OprStatus(
        id = OprStatusId(oprDate = oprDate, oprSeq = oprSeq, dtlIp = dtlIp, dtlLaneNo = dtlLaneNo),
        dtlId = dtlId,
        dtlType = 1,
        dtlNo = 1,
        locId = 1,
        grpId = 1,
        gateType = "SPEED",
        userMode = "1",
        securityMode = "1",
        inoutTime = 5,
        userCount = userCount,
        totalCount = totalCount,
        beforeTotal = totalCount - userCount,
        inCount = 2,
        inTotal = 500,
        inBefore = 498,
        outCount = 1,
        outTotal = 500,
        outBefore = 499,
        doorCount = 0,
        doorTotal = 0,
        doorBefore = 0,
        useYn = "Y",
    )

    @Test
    fun `엔티티가 매핑한 컬럼 전체가 스키마에 있어 저장과 재조회가 성공한다`() {
        val saved = entityManager.persistAndFlush(sample())
        entityManager.clear()

        val reloaded = entityManager.find(OprStatus::class.java, saved.id)!!

        assertEquals(100L, reloaded.dtlId)
        assertEquals(1, reloaded.dtlType)
        assertEquals("SPEED", reloaded.gateType)
        assertEquals(3, reloaded.userCount)
        assertEquals(1000L, reloaded.totalCount)
        assertEquals(500L, reloaded.outTotal)
        assertEquals(499L, reloaded.outBefore)
        assertEquals("Y", reloaded.useYn)
    }

    @Test
    fun `findLatestBefore는 직전 분 버킷 레코드 1건을 최신순으로 반환한다`() {
        entityManager.persistAndFlush(sample(oprDate = "202608261200", dtlId = 100, totalCount = 900))
        entityManager.persistAndFlush(sample(oprDate = "202608261201", dtlId = 100, totalCount = 950))
        entityManager.clear()

        val latest = repository.findLatestBefore(
            dtlId = 100,
            dtlIp = "192.168.0.10",
            dtlLaneNo = 1,
            beforeDateKey = "202608261202",
            sinceDateKey = "202608260000",
            pageable = PageRequest.of(0, 1),
        )

        assertEquals(1, latest.size)
        assertEquals(950L, latest[0].totalCount)
    }

    @Test
    fun `sumUserCountToday는 당일 분단위 증가분 합계를 반환한다`() {
        entityManager.persistAndFlush(sample(oprDate = "202608260900", oprSeq = 1, userCount = 3))
        entityManager.persistAndFlush(sample(oprDate = "202608261000", oprSeq = 1, userCount = 5))
        entityManager.clear()

        val sum = repository.sumUserCountToday("202608260000", "202608262359")

        assertEquals(8L, sum)
    }
}
