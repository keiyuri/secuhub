package kr.co.securance.secuhub.domain.repository

import kr.co.securance.secuhub.domain.entity.DataReceiveAnalysis
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

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
}
