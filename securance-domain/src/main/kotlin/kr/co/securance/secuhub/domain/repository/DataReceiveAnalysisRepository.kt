package kr.co.securance.secuhub.domain.repository

import kr.co.securance.secuhub.domain.entity.DataReceiveAnalysis
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * `tb_data_rcv_anal` 리포지토리.
 *
 * [findRecentUnresolvedErrors]는 레거시 뷰 `uvw_anlz_error`를 리포지토리 쿼리로 이식한 것이다
 * (계획서 4.4절: 1차 스캐폴드에서는 이 조회 1개만 구현해 "DB 뷰 대신 서비스 계층" 패턴을 증명한다.
 * 나머지 uvw_anlz_event/uvw_anlz_problem/uvw_snd_control/uvw_user_cnt는 동일 방식으로 후속 추가).
 */
interface DataReceiveAnalysisRepository : JpaRepository<DataReceiveAnalysis, Long> {

    @Query(
        """
        SELECT a FROM DataReceiveAnalysis a
        WHERE a.errType = 3
          AND a.hasErrorEvent = true
          AND a.analType IN ('PLM', 'STA')
          AND a.analDate >= :sinceDate
        ORDER BY a.analId DESC
        """,
    )
    fun findRecentUnresolvedErrors(sinceDate: String, pageable: Pageable): List<DataReceiveAnalysis>

    /**
     * `SendControlJob`(레거시 `ClsQuartzJobSendControl.FinalizeSuccessfulSend`)이 RESET 계열 제어
     * 명령 전송에 성공했을 때, 해당 게이트(`dtlIp`)의 미해결 오류 중 **모터** 관련 오류만 resolve
     * 처리한다. 레거시 `DbProviderBase.UpdateResetFlagMotor`의 조건(`DESC_GATE_STATUS10`/`11`이
     * 비어있지 않음)을 그대로 옮겼다.
     *
     * [Codex 적대적 리뷰 수정] 예전에는 서브타입을 구분하지 않고 `dtlIp`의 미해결 오류 전체를
     * resolve 처리했다 — 모터 하나만 RESET해도 같은 IP의 무관한(예: 화재경보) 활성 오류까지 사라져
     * 운영자가 실제 장애를 놓칠 수 있는 결함이었다. 레거시 SQL(`ClsMariaDB.UpdateResetFlagGeneric`)을
     * 다시 확인해 정확한 서브타입별 조건을 복원했다.
     *
     * @param sinceDate/[untilDate] 레거시와 동일하게 "어제 00:00 ~ 오늘 23:59"로 대상 기간을 제한한다
     *   (`yyyyMMddHHmm` 포맷, `anal_date`와 동일한 문자열 비교).
     */
    @Modifying
    @Transactional
    @Query(
        """
        UPDATE DataReceiveAnalysis a
        SET a.resolveYn = 'Y', a.resolveUser = :resolveUser, a.resolveDate = :resolveDate
        WHERE a.dtlIp = :dtlIp
          AND a.resolveYn = 'N'
          AND a.errType = 3
          AND a.analDate >= :sinceDate
          AND a.analDate <= :untilDate
          AND LENGTH(TRIM(CONCAT(
                COALESCE(a.descMainMotorError, ''), COALESCE(a.descSlaveMotorError, '')
              ))) > 0
        """,
    )
    fun resolveMotorErrors(
        dtlIp: String,
        resolveUser: String,
        resolveDate: LocalDateTime,
        sinceDate: String,
        untilDate: String,
    ): Int

    /**
     * 센서/운영 오류만 resolve 처리한다. 레거시 `UpdateResetFlagSensor`의 조건(운영 센서 8종 +
     * 안전 센서 4종이 비어있지 않음)을 그대로 옮겼다. [resolveMotorErrors] 문서 참고.
     */
    @Modifying
    @Transactional
    @Query(
        """
        UPDATE DataReceiveAnalysis a
        SET a.resolveYn = 'Y', a.resolveUser = :resolveUser, a.resolveDate = :resolveDate
        WHERE a.dtlIp = :dtlIp
          AND a.resolveYn = 'N'
          AND a.errType = 3
          AND a.analDate >= :sinceDate
          AND a.analDate <= :untilDate
          AND LENGTH(TRIM(CONCAT(
                COALESCE(a.descOperation01, ''), COALESCE(a.descOperation02, ''),
                COALESCE(a.descOperation03, ''), COALESCE(a.descOperation04, ''),
                COALESCE(a.descSafety01, ''), COALESCE(a.descSafety02, ''),
                COALESCE(a.descSafety03, ''), COALESCE(a.descSafety04, ''),
                COALESCE(a.descOperation05, ''), COALESCE(a.descOperation06, ''),
                COALESCE(a.descOperation07, ''), COALESCE(a.descOperation08, ''),
                COALESCE(a.descGateStatus09, '')
              ))) > 0
        """,
    )
    fun resolveSensorErrors(
        dtlIp: String,
        resolveUser: String,
        resolveDate: LocalDateTime,
        sinceDate: String,
        untilDate: String,
    ): Int

    /**
     * 게이트(전체) 오류를 resolve 처리한다. 레거시 `UpdateResetFlag`의 조건을 그대로 옮겼다 — 대상
     * 15개 컬럼이 생성 컬럼 `has_error_event`와 완전히 동일해(레거시 2026-07-22 수정 이력 참고)
     * `hasErrorEvent = true` 하나로 대체됐다. [resolveMotorErrors] 문서 참고.
     */
    @Modifying
    @Transactional
    @Query(
        """
        UPDATE DataReceiveAnalysis a
        SET a.resolveYn = 'Y', a.resolveUser = :resolveUser, a.resolveDate = :resolveDate
        WHERE a.dtlIp = :dtlIp
          AND a.resolveYn = 'N'
          AND a.errType = 3
          AND a.analDate >= :sinceDate
          AND a.analDate <= :untilDate
          AND a.hasErrorEvent = true
        """,
    )
    fun resolveGateErrors(
        dtlIp: String,
        resolveUser: String,
        resolveDate: LocalDateTime,
        sinceDate: String,
        untilDate: String,
    ): Int
}
