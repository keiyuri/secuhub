package kr.co.securance.secuhub.domain.repository

import kr.co.securance.secuhub.domain.entity.DataSend
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager
import org.springframework.test.context.TestPropertySource

/**
 * [DataSendRepository.claimForSend] 검증 — 2026-08-13 Opus 전체 리뷰 지적(다중 인스턴스 정합성의
 * 핵심인 이 UPDATE가 mock 스텁으로만 검증되고 있었다).
 *
 * `claimForSend`는 대상 테이블을 상관 서브쿼리(`NOT EXISTS`)로 자기 자신 참조하는 UPDATE라 JPQL
 * 문법 오류나 조건 오적용은 Mockito만으로는 잡히지 않는다 — 실제 JPA 프로바이더 위에서 (1) 정상
 * 선점, (2) 버전 불일치, (3) 이미 전송된 행, (4) 같은 레인 ACK 대기 중 행 존재를 각각 검증한다.
 */
@DataJpaTest
@TestPropertySource(
    properties = [
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
    ],
)
class DataSendRepositoryTest {

    @Autowired
    private lateinit var entityManager: TestEntityManager

    @Autowired
    private lateinit var repository: DataSendRepository

    private fun sample(dtlIp: String = "192.168.0.10", dtlLaneNo: Int = 1, sndYn: String = "N", chkYn: String = "N") =
        DataSend(
            sndDate = "20260813120000",
            sndYn = sndYn,
            chkYn = chkYn,
            dtlIp = dtlIp,
            dtlLaneNo = dtlLaneNo,
            sndRaw = "AA",
            sndData = "AA",
        )

    @Test
    fun `대기 중인 행을 정상 선점하면 1을 반환하고 sndYn과 version을 갱신한다`() {
        val entity = entityManager.persistAndFlush(sample())
        entityManager.clear()

        val claimed = repository.claimForSend(entity.sndId!!, version = 0, server = "10.0.0.1")

        assertEquals(1, claimed)
        val reloaded = entityManager.find(DataSend::class.java, entity.sndId!!)!!
        assertEquals("Y", reloaded.sndYn)
        assertEquals("10.0.0.1", reloaded.sndServer)
        assertEquals(1L, reloaded.version)
    }

    @Test
    fun `version이 저장된 값과 다르면 다른 인스턴스가 이미 처리한 것으로 보고 0을 반환한다`() {
        val entity = entityManager.persistAndFlush(sample())
        entityManager.clear()

        val claimed = repository.claimForSend(entity.sndId!!, version = 99, server = "10.0.0.1")

        assertEquals(0, claimed)
        val reloaded = entityManager.find(DataSend::class.java, entity.sndId!!)!!
        assertEquals("N", reloaded.sndYn)
    }

    @Test
    fun `이미 전송된(snd_yn=Y) 행은 다시 선점할 수 없다`() {
        val entity = entityManager.persistAndFlush(sample(sndYn = "Y"))
        entityManager.clear()

        val claimed = repository.claimForSend(entity.sndId!!, version = 0, server = "10.0.0.1")

        assertEquals(0, claimed)
    }

    @Test
    fun `같은 레인에 ACK 대기 중인 행이 있으면 선점 자체를 실패시킨다`() {
        // 이미 전송돼 ACK를 기다리는 행 (sndYn=Y, chkYn=N) — 같은 dtlIp/dtlLaneNo.
        entityManager.persistAndFlush(sample(sndYn = "Y", chkYn = "N"))
        val pending = entityManager.persistAndFlush(sample(sndYn = "N", chkYn = "N"))
        entityManager.clear()

        val claimed = repository.claimForSend(pending.sndId!!, version = 0, server = "10.0.0.1")

        assertEquals(0, claimed)
        val reloaded = entityManager.find(DataSend::class.java, pending.sndId!!)!!
        assertEquals("N", reloaded.sndYn) // 선점되지 않고 그대로 대기 상태여야 한다.
    }

    @Test
    fun `다른 레인의 ACK 대기 행은 선점에 영향을 주지 않는다`() {
        // 다른 레인(2번)에 ACK 대기 행이 있어도 1번 레인 선점에는 영향이 없어야 한다.
        entityManager.persistAndFlush(sample(dtlLaneNo = 2, sndYn = "Y", chkYn = "N"))
        val pending = entityManager.persistAndFlush(sample(dtlLaneNo = 1, sndYn = "N", chkYn = "N"))
        entityManager.clear()

        val claimed = repository.claimForSend(pending.sndId!!, version = 0, server = "10.0.0.1")

        assertEquals(1, claimed)
    }

    @Test
    fun `유효기간이 지난 미전송 대기 행만 실패 확정한다`() {
        // 오래된 대기 행 — 헤드 오브 라인 차단을 일으키던 바로 그 케이스(코드 리뷰 R-1).
        val stale = entityManager.persistAndFlush(sample().apply { sndDate = "20260101000000" })
        // 최근 대기 행 — 아직 유효기간 안이므로 건드리면 안 된다.
        val fresh = entityManager.persistAndFlush(sample().apply { sndDate = "20260813120000" })
        entityManager.clear()

        val expired = repository.expireStalePending(cutoff = "20260101120000")

        assertEquals(1, expired)
        val reloadedStale = entityManager.find(DataSend::class.java, stale.sndId!!)!!
        assertEquals("N", reloadedStale.sndYn)
        assertEquals("F", reloadedStale.chkYn)
        val reloadedFresh = entityManager.find(DataSend::class.java, fresh.sndId!!)!!
        assertEquals("N", reloadedFresh.chkYn)
    }

    @Test
    fun `이미 전송되었거나 이미 종결된 행은 유효기간 정리 대상에서 제외한다`() {
        // 이미 전송돼 ACK 대기 중(Y,N) — 이 정리는 전송 자체를 못한 행만 대상으로 한다.
        val awaitingAck = entityManager.persistAndFlush(sample(sndYn = "Y").apply { sndDate = "20260101000000" })
        // 이미 실패 확정된 행(N,F) — 다시 손댈 필요 없다.
        val alreadyFailed = entityManager.persistAndFlush(sample(chkYn = "F").apply { sndDate = "20260101000000" })
        entityManager.clear()

        val expired = repository.expireStalePending(cutoff = "20260101120000")

        assertEquals(0, expired)
        assertEquals("N", entityManager.find(DataSend::class.java, awaitingAck.sndId!!)!!.chkYn)
        assertEquals("F", entityManager.find(DataSend::class.java, alreadyFailed.sndId!!)!!.chkYn)
    }
}
