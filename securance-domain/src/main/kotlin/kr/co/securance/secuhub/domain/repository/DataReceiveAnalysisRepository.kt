package kr.co.securance.secuhub.domain.repository

import kr.co.securance.secuhub.domain.entity.DataReceiveAnalysis
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

/**
 * `tb_data_rcv_anal` 리포지토리.
 *
 * [findRecentUnresolvedErrors]는 레거시 뷰 `uvw_anlz_error`를 리포지토리 쿼리로 이식한 것이다
 * (계획서 4.4절: DB 뷰 대신 서비스 계층).
 *
 * `resolve*` 계열은 레거시 `ClsMariaDB.UpdateResetFlag`/`UpdateResetFlagSensor`/
 * `UpdateResetFlagMotor`의 이식이다. 레거시는 IP 단위로만 해제했으나(다중 레인 장비에서
 * 한 레인 리셋이 다른 레인 장애까지 해제해 버림) 여기서는 **레인 번호까지 조건에 넣어**
 * 리셋한 레인의 장애만 해제한다.
 */
/**
 * [JpaSpecificationExecutor] 추가 — #9 SR_F_ViewEvent(계획서 4절)가 위치/그룹/게이트/이벤트유형/
 * 해결여부/기간 등 9종 필터를 임의 조합으로 조회해야 해서, 조합마다 `@Query` 메서드를 만드는 대신
 * 동적 조건 조립이 필요하다.
 */
interface DataReceiveAnalysisRepository : JpaRepository<DataReceiveAnalysis, Long>, JpaSpecificationExecutor<DataReceiveAnalysis> {

    /**
     * `resolveYn = 'N'`(미해결) 조건 포함(적대적 리뷰 지적) — 이 조건이 없으면 운영자가 오류를
     * 해결 처리(`resolve_yn='Y'`)해도 대시보드의 "미해결 오류" 위젯에서 사라지지 않고 5일 윈도우 내내
     * 계속 누적되어 표시된다. 인덱스는 [V2__fix_dashboard_query_indexes.sql]도 함께 확인할 것.
     */
    @Query(
        """
        SELECT a FROM DataReceiveAnalysis a
        WHERE a.errType = 3
          AND a.hasErrorEvent = true
          AND a.resolveYn = 'N'
          AND a.analTp IN ('PLM', 'STA')
          AND a.analDate >= :sinceDate
        ORDER BY a.analId DESC
        """,
    )
    fun findRecentUnresolvedErrors(sinceDate: String, pageable: Pageable): List<DataReceiveAnalysis>

    /** 대시보드 SmallBox에 표시할 실제 미해결 오류 총 건수 — [findRecentUnresolvedErrors]는 상위 N건만
     * 조회하므로 개수 표시에는 쓸 수 없다(페이지 크기로 카운트가 잘리는 버그 방지). */
    @Query(
        """
        SELECT COUNT(a) FROM DataReceiveAnalysis a
        WHERE a.errType = 3
          AND a.hasErrorEvent = true
          AND a.resolveYn = 'N'
          AND a.analTp IN ('PLM', 'STA')
          AND a.analDate >= :sinceDate
        """,
    )
    fun countRecentUnresolvedErrors(sinceDate: String): Long

    /**
     * 게이트 전체 장애 해제(레거시 `UpdateResetFlag` — 조건 `has_error_event = 1`).
     * 생성 컬럼을 그대로 조건에 써서 `IDX_ANAL_ERR3_SCAN` 인덱스를 탄다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        UPDATE DataReceiveAnalysis a
           SET a.resolveYn = 'Y', a.resolveDate = :resolvedAt, a.resolveUser = :resolvedBy
         WHERE a.errType = 3 AND a.resolveYn = 'N'
           AND a.hasErrorEvent = true
           AND a.dtlIp = :dtlIp AND a.dtlLaneNo = :dtlLaneNo
           AND a.analDate >= :fromDate AND a.analDate <= :toDate
        """,
    )
    fun resolveAllErrors(
        @Param("dtlIp") dtlIp: String,
        @Param("dtlLaneNo") dtlLaneNo: Int,
        @Param("fromDate") fromDate: String,
        @Param("toDate") toDate: String,
        @Param("resolvedBy") resolvedBy: String,
        @Param("resolvedAt") resolvedAt: LocalDateTime,
    ): Int

    /**
     * 운영/안전 센서 장애 해제(레거시 `UpdateResetFlagSensor` — 13개 desc 컬럼 중 하나라도 값이 있으면 대상).
     *
     * 레거시는 `LENGTH(TRIM(CONCAT(...)))`로 판정하면서 NULL 컬럼 하나가 CONCAT 전체를 NULL로
     * 만들어 실제 장애가 있어도 누락되는 버그가 있었다(2026-07-22 `IFNULL` 추가로 수정).
     * 여기서는 컬럼이 `NOT NULL DEFAULT ''`이고 조건도 컬럼별 OR이라 그 문제가 발생하지 않는다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        UPDATE DataReceiveAnalysis a
           SET a.resolveYn = 'Y', a.resolveDate = :resolvedAt, a.resolveUser = :resolvedBy
         WHERE a.errType = 3 AND a.resolveYn = 'N'
           AND a.dtlIp = :dtlIp AND a.dtlLaneNo = :dtlLaneNo
           AND a.analDate >= :fromDate AND a.analDate <= :toDate
           AND (TRIM(a.descOperation01) <> '' OR TRIM(a.descOperation02) <> ''
             OR TRIM(a.descOperation03) <> '' OR TRIM(a.descOperation04) <> ''
             OR TRIM(a.descSafety01) <> '' OR TRIM(a.descSafety02) <> ''
             OR TRIM(a.descSafety03) <> '' OR TRIM(a.descSafety04) <> ''
             OR TRIM(a.descOperation05) <> '' OR TRIM(a.descOperation06) <> ''
             OR TRIM(a.descOperation07) <> '' OR TRIM(a.descOperation08) <> ''
             OR TRIM(a.descGateStatus09) <> '')
        """,
    )
    fun resolveSensorErrors(
        @Param("dtlIp") dtlIp: String,
        @Param("dtlLaneNo") dtlLaneNo: Int,
        @Param("fromDate") fromDate: String,
        @Param("toDate") toDate: String,
        @Param("resolvedBy") resolvedBy: String,
        @Param("resolvedAt") resolvedAt: LocalDateTime,
    ): Int

    /** 모터 장애 해제(레거시 `UpdateResetFlagMotor` — `desc_gate_status10`/`11`). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        UPDATE DataReceiveAnalysis a
           SET a.resolveYn = 'Y', a.resolveDate = :resolvedAt, a.resolveUser = :resolvedBy
         WHERE a.errType = 3 AND a.resolveYn = 'N'
           AND a.dtlIp = :dtlIp AND a.dtlLaneNo = :dtlLaneNo
           AND a.analDate >= :fromDate AND a.analDate <= :toDate
           AND (TRIM(a.descGateStatus10) <> '' OR TRIM(a.descGateStatus11) <> '')
        """,
    )
    fun resolveMotorErrors(
        @Param("dtlIp") dtlIp: String,
        @Param("dtlLaneNo") dtlLaneNo: Int,
        @Param("fromDate") fromDate: String,
        @Param("toDate") toDate: String,
        @Param("resolvedBy") resolvedBy: String,
        @Param("resolvedAt") resolvedAt: LocalDateTime,
    ): Int

    /** 화재 경보 해제(레거시 트리거의 "화재 경보 복구" 분기 — `desc_gate_status07`). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        UPDATE DataReceiveAnalysis a
           SET a.resolveYn = 'Y', a.resolveDate = :resolvedAt, a.resolveUser = :resolvedBy
         WHERE a.errType = 3 AND a.resolveYn = 'N'
           AND a.dtlIp = :dtlIp AND a.dtlLaneNo = :dtlLaneNo
           AND a.analDate >= :fromDate AND a.analDate <= :toDate
           AND TRIM(a.descGateStatus07) <> ''
        """,
    )
    fun resolveFireAlarms(
        @Param("dtlIp") dtlIp: String,
        @Param("dtlLaneNo") dtlLaneNo: Int,
        @Param("fromDate") fromDate: String,
        @Param("toDate") toDate: String,
        @Param("resolvedBy") resolvedBy: String,
        @Param("resolvedAt") resolvedAt: LocalDateTime,
    ): Int

    /**
     * D5 데이터 보관 정책(2026-08-12) — `anal_date`(`yyyyMMddHHmm`) 기준 컷오프보다 오래된 분석
     * 결과를 배치 단위로 삭제한다. [DataReceiveRepository.deleteBatchOlderThan]과 동일한 이유로
     * 네이티브 `LIMIT` 삭제를 쓴다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "DELETE FROM tb_data_rcv_anal WHERE anal_date < :cutoff LIMIT :batchSize", nativeQuery = true)
    fun deleteBatchOlderThan(@Param("cutoff") cutoff: String, @Param("batchSize") batchSize: Int): Int

    /**
     * 레인 1개의 "당일 전체 상태 데이터 upsert"(2026-08-14) — [GatePacketPersister.persistStatusAnalysis]가
     * 매 상태 패킷마다 이 레인의 최신 행을 조회해, 오늘 날짜의 동일 데이터면 `rcv_date`만 갱신하고
     * 아니면 새 행을 INSERT한다. `IDX_ANAL_LANE_LATEST(dtl_ip, dtl_lane_no, anal_id)`([V25__add_anal_lane_latest_index.sql])
     * 로 정렬까지 인덱스로 커버한다.
     */
    fun findTopByDtlIpAndDtlLaneNoOrderByAnalIdDesc(dtlIp: String, dtlLaneNo: Int): DataReceiveAnalysis?
}
