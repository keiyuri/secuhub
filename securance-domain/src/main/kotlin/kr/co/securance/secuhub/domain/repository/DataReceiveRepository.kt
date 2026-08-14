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

    /**
     * 레인 1개의 최신 원시 수신 행 — [kr.co.securance.secuhub.server.db.GatePacketPersister]가
     * `tb_data_rcv_anal.rcv_id`(원본 `tb_data_rcv` 행의 PK)를 채우기 위해 조회한다(2026-08-14
     * 코드 리뷰 지적 대응: 이전에는 항상 0으로 고정되어 두 테이블 간 FK 추적이 불가능했다).
     *
     * 같은 파티션 키(dtlIp)로 큐잉되는 원시 INSERT 작업이 분석 INSERT 작업보다 먼저 enqueue되고
     * [kr.co.securance.secuhub.server.db.GateDbWriteQueue]가 파티션 내 실행 순서를 보장하므로,
     * 정상 경로(타임아웃 없음)에서는 분석 작업 실행 시점에 이 조회가 방금 저장된 원시 행을
     * 찾는다. 못 찾으면(레코드가 아직 없거나 재시도 경합) 호출부가 0으로 폴백한다.
     */
    fun findTopByDtlIpAndDtlLaneNoOrderByRcvIdDesc(dtlIp: String, dtlLaneNo: Int): DataReceive?
}

interface DataReceiveFailRepository : JpaRepository<DataReceiveFail, Long>

interface DataReceiveAckRepository : JpaRepository<DataReceiveAck, Long>

// DataReceiveLogRepository(tb_gate_log_event)는 2026-08-12 제거됐다 — 자세한 경위는
// DataReceive.kt의 관련 주석과 docs/작업일지.md 0011 참고.
