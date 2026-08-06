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
     * `SendControlJob`(레거시 `ClsQuartzJobSendControl.FinalizeSuccessfulSend`의 리셋 플래그 갱신에
     * 대응)이 리셋류 제어 명령 전송에 성공했을 때, 해당 게이트(`dtlIp`)의 미해결 오류를 일괄
     * resolve 처리하기 위한 쿼리.
     *
     * [스코프 제한] 레거시는 MOTOR/OPER/GATE 서브타입별로 다른 컬럼(`UpdateResetFlagMotor` 등)을
     * 갱신했으나, 그 정확한 서브타입-컬럼 매핑을 원본 SQL/스토어드 프로시저 없이 추측하는 것은
     * 위험 판단하여 이번 구현은 "해당 게이트(dtlIp)의 미해결 오류(`resolveYn='N'`, `errType=3`) 전체를
     * resolve 처리"로 스코프를 좁혔다. 정확한 서브타입별 컬럼 매핑은 후속 작업으로 남긴다.
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
        """,
    )
    fun resolveUnresolvedErrors(dtlIp: String, resolveUser: String, resolveDate: LocalDateTime): Int
}
