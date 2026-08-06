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
          AND a.analType IN ('PLM', 'STA')
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
          AND a.analType IN ('PLM', 'STA')
          AND a.analDate >= :sinceDate
        """,
    )
    fun countRecentUnresolvedErrors(sinceDate: String): Long
}
