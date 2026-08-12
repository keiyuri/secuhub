package kr.co.securance.secuhub.domain.repository

import kr.co.securance.secuhub.domain.entity.OprStatusOutbox
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

/**
 * `tb_opr_status_outbox` 리포지토리 — 큐 드롭 durable 재작성(2026-08-12, `docs/작업일지.md` 참고).
 * [OprStatusOutbox] 클래스 KDoc 참고.
 */
interface OprStatusOutboxRepository : JpaRepository<OprStatusOutbox, Long> {

    /** [kr.co.securance.secuhub.scheduler.job.OprStatusOutboxReplayJob]이 재처리할 대상을 오래된 순으로 가져온다. */
    fun findByProcessedFalseOrderByOutboxIdAsc(pageable: Pageable): List<OprStatusOutbox>

    /**
     * 재처리에 성공(또는 재시도 상한 도달로 포기)한 오래된 행을 정리한다 — D5 데이터 보관 정책과
     * 별도로, 이 outbox 테이블 자체가 무한정 쌓이는 것을 막기 위한 자체 정리다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "DELETE FROM tb_opr_status_outbox WHERE processed = TRUE AND processed_date < :cutoff LIMIT :batchSize", nativeQuery = true)
    fun deleteProcessedOlderThan(@Param("cutoff") cutoff: LocalDateTime, @Param("batchSize") batchSize: Int): Int
}
