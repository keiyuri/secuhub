package kr.co.securance.secuhub.domain.repository

import kr.co.securance.secuhub.domain.entity.DataReceiveAnalysis
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
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
interface DataReceiveAnalysisRepository : JpaRepository<DataReceiveAnalysis, Long> {

    @Query(
        """
        SELECT a FROM DataReceiveAnalysis a
        WHERE a.errType = 3
          AND a.hasErrorEvent = true
          AND a.analTp IN ('PLM', 'STA')
          AND a.analDate >= :sinceDate
        ORDER BY a.analId DESC
        """,
    )
    fun findRecentUnresolvedErrors(sinceDate: String, pageable: Pageable): List<DataReceiveAnalysis>

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
}
