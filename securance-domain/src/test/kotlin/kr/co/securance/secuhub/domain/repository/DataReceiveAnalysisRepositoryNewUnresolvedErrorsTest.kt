package kr.co.securance.secuhub.domain.repository

import kr.co.securance.secuhub.domain.entity.DataReceiveAnalysis
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager
import org.springframework.data.domain.PageRequest
import org.springframework.test.context.TestPropertySource

/**
 * [DataReceiveAnalysisRepository.findNewUnresolvedErrors]/[DataReceiveAnalysisRepository.findMaxUnresolvedErrorAnalId]
 * 회귀 테스트. `DashboardPushService`(securance-web)가 3초마다 폴링하는 조회가 실수로 파티션 키
 * `anal_tp` 대신 무관한 `anal_type`(항상 'B') 컬럼을 참조했던 버그(2026-08-14,
 * `docs/작업일지.md` 참고 — 개발 DB `20260814-01.txt` 소켓 타임아웃의 근본 원인) 재발을 막는다.
 *
 * `has_error_event`는 실제 스키마에서 MariaDB VIRTUAL 생성 컬럼이라 엔티티가 읽기 전용
 * (`insertable=false updatable=false`)으로 매핑한다 — [GateLogRepositoryTest]와 동일한 이유로
 * `persistAndFlush` 이후 네이티브 UPDATE로 값을 채운다.
 */
@DataJpaTest
@TestPropertySource(
    properties = [
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
    ],
)
class DataReceiveAnalysisRepositoryNewUnresolvedErrorsTest {

    @Autowired
    private lateinit var entityManager: TestEntityManager

    @Autowired
    private lateinit var repository: DataReceiveAnalysisRepository

    private fun sample(analTp: String, analType: String = "B", errType: Int = 3, resolveYn: String = "N") =
        DataReceiveAnalysis(
            analDate = "202608140000",
            analTp = analTp,
            analType = analType,
            dtlIp = "192.168.0.10",
            dtlLaneNo = 1,
            rcvDate = "202608140000",
            errType = errType,
            resolveYn = resolveYn,
        )

    /** 실제 스키마의 VIRTUAL 생성 컬럼을 흉내내 `has_error_event`를 채운다. */
    private fun markHasErrorEvent(entity: DataReceiveAnalysis) {
        entityManager.entityManager
            .createNativeQuery("UPDATE tb_data_rcv_anal SET has_error_event = TRUE WHERE anal_id = ${entity.analId}")
            .executeUpdate()
    }

    @Test
    fun `anal_type이 아니라 anal_tp로 PLM STA를 판별한다`() {
        // 회귀 시나리오: 모든 행의 anal_type은 항상 'B'다(레거시 트리거가 그렇게 채움) — anal_type을
        // 조건으로 쓰면 아래 두 행 모두 매치하지 못해 findNewUnresolvedErrors가 항상 빈 리스트를
        // 반환하는 버그가 재현된다.
        val plm = entityManager.persistAndFlush(sample(analTp = "PLM"))
        markHasErrorEvent(plm)
        val nor = entityManager.persistAndFlush(sample(analTp = "NOR")) // anal_tp가 대상 밖 — 제외돼야 함
        markHasErrorEvent(nor)
        entityManager.clear()

        val result = repository.findNewUnresolvedErrors(0L, PageRequest.of(0, 200))

        assertEquals(1, result.size)
        assertEquals(plm.analId, result.single().analId)
    }

    @Test
    fun `has_error_event가 false이거나 resolve_yn이 Y면 제외한다`() {
        val notError = entityManager.persistAndFlush(sample(analTp = "PLM")) // markHasErrorEvent 호출 안 함 → false로 유지
        val resolved = entityManager.persistAndFlush(sample(analTp = "STA", resolveYn = "Y"))
        markHasErrorEvent(resolved)
        entityManager.clear()

        val result = repository.findNewUnresolvedErrors(0L, PageRequest.of(0, 200))

        assertEquals(0, result.size)
        assertEquals(0, listOf(notError, resolved).count { it.analId in result.map { r -> r.analId } })
    }

    @Test
    fun `sinceExclusive보다 큰 anal_id만 오름차순으로 반환하고 limit을 넘지 않는다`() {
        val first = entityManager.persistAndFlush(sample(analTp = "PLM"))
        markHasErrorEvent(first)
        val second = entityManager.persistAndFlush(sample(analTp = "STA"))
        markHasErrorEvent(second)
        entityManager.clear()

        val onlySecond = repository.findNewUnresolvedErrors(requireNotNull(first.analId), PageRequest.of(0, 200))
        assertEquals(listOf(second.analId), onlySecond.map { it.analId })

        val limited = repository.findNewUnresolvedErrors(0L, PageRequest.of(0, 1))
        assertEquals(listOf(first.analId), limited.map { it.analId })
    }

    @Test
    fun `findMaxUnresolvedErrorAnalId는 조건에 맞는 행이 없으면 null을 반환한다`() {
        assertNull(repository.findMaxUnresolvedErrorAnalId())
    }

    @Test
    fun `findMaxUnresolvedErrorAnalId는 조건에 맞는 행 중 최댓값을 반환한다`() {
        val first = entityManager.persistAndFlush(sample(analTp = "PLM"))
        markHasErrorEvent(first)
        val second = entityManager.persistAndFlush(sample(analTp = "STA"))
        markHasErrorEvent(second)
        entityManager.clear()

        assertEquals(second.analId, repository.findMaxUnresolvedErrorAnalId())
    }
}
