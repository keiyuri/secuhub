package kr.co.securance.secuhub.domain.repository

import kr.co.securance.secuhub.domain.entity.DataReceive
import kr.co.securance.secuhub.domain.entity.DataReceiveAck
import kr.co.securance.secuhub.domain.entity.DataReceiveFail
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional

interface DataReceiveRepository : JpaRepository<DataReceive, Long> {

    /**
     * D5 데이터 보관 정책(2026-08-12) — `rcv_date`(`yyyyMMddHHmm`) 기준 컷오프보다 오래된 원시 수신
     * 패킷을 배치 단위로 삭제한다. 표준 JPQL `DELETE`는 `LIMIT`을 지원하지 않아 네이티브 쿼리로
     * 작성했다 — 대상 행이 수백만 건일 수 있는 고빈도 적재 테이블이라, 한 트랜잭션에서 전부 지우면
     * 락을 오래 쥐게 되므로 [kr.co.securance.secuhub.scheduler.job.RetentionCleanupJob]이 이 메서드를
     * 반복 호출해 조금씩 지운다.
     *
     * **`@Transactional` 필수(Codex 적대적 리뷰 지적, 2026-08-20, [high])**: Spring Data JPA는
     * `find`/`get`/`read`/... 로 시작하는 조회 메서드에만 기본 트랜잭션(readOnly)을 자동으로 씌운다
     * — `deleteBatchOlderThan`처럼 이름이 그 패턴에 안 맞는 커스텀 `@Modifying` 메서드는 호출부가
     * 트랜잭션 안에 있지 않으면 `TransactionRequiredException`으로 실패한다. 호출부인
     * `RetentionCleanupJob.executeInternal`은 트랜잭션이 아니므로(배치 삭제를 잘게 쪼개 락을 짧게
     * 쥐려는 의도, 클래스 KDoc "배치 삭제인 이유" 참고) 메서드 자체에 트랜잭션을 씌운다.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "DELETE FROM tb_data_rcv WHERE rcv_date < :cutoff LIMIT :batchSize", nativeQuery = true)
    fun deleteBatchOlderThan(@Param("cutoff") cutoff: String, @Param("batchSize") batchSize: Int): Int

    /**
     * 이 장비(dtlIp)의 최신 원시 수신 행 — [kr.co.securance.secuhub.server.db.GatePacketPersister]가
     * `tb_data_rcv_anal.rcv_id`(원본 `tb_data_rcv` 행의 PK)를 채우기 위해 조회한다(2026-08-14
     * 코드 리뷰 지적 대응: 이전에는 항상 0으로 고정되어 두 테이블 간 FK 추적이 불가능했다).
     *
     * **레인으로 필터링하지 않는다**(2026-08-14 재검토로 발견한 P1 버그 수정) — `tb_data_rcv`는
     * 원시 패킷 1건당 "대표 레인" 하나로만 태그된 행 1건을 만드는 반면([GatePacketPersister]의
     * `persistReceivedPacket`), 같은 원시 패킷을 분석하는 `persistStatusAnalysis`는 패킷에 실린
     * 레인 수만큼 여러 분석 행을 만든다([GateStatusAnalyzer.analyze]). 레인 번호로 필터링하면
     * 대표 레인이 아닌 레인들은 이번에 막 저장된 원시 행을 절대 찾지 못하고, 그 레인 번호가 과거
     * 다른 패킷에서 대표 레인이었던 시점의 무관한 `rcv_id`를 조용히 잘못 가져오거나 0으로
     * 폴백했다. 같은 원시 패킷에서 나온 분석 행들은 전부 같은 원시 행을 가리켜야 하므로, 레인
     * 필터 없이 dtlIp만으로 최신 행을 찾는다.
     *
     * 같은 파티션 키(dtlIp)로 큐잉되는 원시 INSERT 작업이 분석 INSERT 작업보다 먼저 enqueue되고
     * [kr.co.securance.secuhub.server.db.GateDbWriteQueue]가 파티션 내 실행 순서를 보장하므로,
     * 정상 경로(타임아웃 없음)에서는 분석 작업 실행 시점에 이 조회가 방금 저장된 원시 행을
     * 찾는다. 못 찾으면(레코드가 아직 없거나 재시도 경합) 호출부가 0으로 폴백한다.
     */
    fun findTopByDtlIpOrderByRcvIdDesc(dtlIp: String): DataReceive?
}

interface DataReceiveFailRepository : JpaRepository<DataReceiveFail, Long> {

    /**
     * 코드 리뷰 지적 D-3 대응: `tb_data_rcv_fail`은 D5 보관 정책(2026-08-12) 도입 당시 정리 대상에서
     * 빠져 있었다 — 체크섬 실패마다 1행씩 무한정 쌓이는 고빈도 테이블인데도 삭제 쿼리 자체가
     * 없었다. `fail_date`는 `yyyyMMddHHmmss`(초 단위) 문자열이라 사전식 비교가 시간 비교와
     * 일치한다([GatePacketPersister]의 `TIMESTAMP_FORMAT`).
     */
    // `@Transactional` 필요 이유는 DataReceiveRepository.deleteBatchOlderThan KDoc 참고
    // (Codex 적대적 리뷰 지적, 2026-08-20, [high]).
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "DELETE FROM tb_data_rcv_fail WHERE fail_date < :cutoff LIMIT :batchSize", nativeQuery = true)
    fun deleteBatchOlderThan(@Param("cutoff") cutoff: String, @Param("batchSize") batchSize: Int): Int
}

interface DataReceiveAckRepository : JpaRepository<DataReceiveAck, Long> {

    /**
     * 코드 리뷰 지적 D-3 대응: `tb_data_rcv_ack`는 장비 ACK 수신마다 1행씩 쌓이는 고빈도 테이블인데도
     * D5 보관 정책 대상에서 빠져 있었다. `ack_date`는 `yyyyMMddHHmmss`(초 단위) 문자열이라 사전식
     * 비교가 시간 비교와 일치한다.
     */
    // `@Transactional` 필요 이유는 DataReceiveRepository.deleteBatchOlderThan KDoc 참고
    // (Codex 적대적 리뷰 지적, 2026-08-20, [high]).
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "DELETE FROM tb_data_rcv_ack WHERE ack_date < :cutoff LIMIT :batchSize", nativeQuery = true)
    fun deleteBatchOlderThan(@Param("cutoff") cutoff: String, @Param("batchSize") batchSize: Int): Int
}

// DataReceiveLogRepository(tb_gate_log_event)는 2026-08-12 제거됐다 — 자세한 경위는
// DataReceive.kt의 관련 주석과 docs/작업일지.md 0011 참고.
