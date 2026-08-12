package kr.co.securance.secuhub.domain.repository

import kr.co.securance.secuhub.domain.entity.DataReceive
import kr.co.securance.secuhub.domain.entity.DataReceiveAck
import kr.co.securance.secuhub.domain.entity.DataReceiveFail
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface DataReceiveRepository : JpaRepository<DataReceive, Long> {

    /**
     * D5 데이터 보관 정책(2026-08-12) — `rcv_date`(`yyyyMMddHHmm`) 기준 컷오프보다 오래된 원시 수신
     * 패킷을 배치 단위로 삭제한다. 표준 JPQL `DELETE`는 `LIMIT`을 지원하지 않아 네이티브 쿼리로
     * 작성했다 — 대상 행이 수백만 건일 수 있는 고빈도 적재 테이블이라, 한 트랜잭션에서 전부 지우면
     * 락을 오래 쥐게 되므로 [kr.co.securance.secuhub.scheduler.job.RetentionCleanupJob]이 이 메서드를
     * 반복 호출해 조금씩 지운다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "DELETE FROM tb_data_rcv WHERE rcv_date < :cutoff LIMIT :batchSize", nativeQuery = true)
    fun deleteBatchOlderThan(@Param("cutoff") cutoff: String, @Param("batchSize") batchSize: Int): Int
}

interface DataReceiveFailRepository : JpaRepository<DataReceiveFail, Long>

interface DataReceiveAckRepository : JpaRepository<DataReceiveAck, Long>

// DataReceiveLogRepository(tb_gate_log_event)는 2026-08-12 제거됐다 — 자세한 경위는
// DataReceive.kt의 관련 주석과 docs/작업일지.md 0011 참고.
