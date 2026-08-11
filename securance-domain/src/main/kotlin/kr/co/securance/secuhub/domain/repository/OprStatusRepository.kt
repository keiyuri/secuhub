package kr.co.securance.secuhub.domain.repository

import kr.co.securance.secuhub.domain.entity.OprStatus
import kr.co.securance.secuhub.domain.entity.OprStatusId
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/** `tb_opr_status` 리포지토리 — 대시보드 통행량 위젯(`uvw_user_cnt` 대응, 계획서 5.2절)의 원본 데이터. */
interface OprStatusRepository : JpaRepository<OprStatus, OprStatusId> {
    fun findByLocIdAndGrpIdAndIdOprDateBetween(locId: Long, grpId: Long, fromDate: String, toDate: String): List<OprStatus>

    /**
     * [kr.co.securance.secuhub.server.db.OprStatusPersister]가 새 분(分) 버킷을 INSERT하기 전에
     * "직전 값"을 조회하는 용도 — 레거시 `usp_process_status`의 PREV 조회(최근 24시간 이내,
     * 현재 분보다 이전 레코드 중 가장 최신 1건)와 동일하다. [Pageable]로 1건만 받는다(JPQL은
     * `LIMIT`을 직접 못 쓴다).
     */
    @Query(
        """
        SELECT o FROM OprStatus o
        WHERE o.dtlId = :dtlId AND o.id.dtlIp = :dtlIp AND o.id.dtlLaneNo = :dtlLaneNo
          AND o.useYn = 'Y'
          AND o.id.oprDate < :beforeDateKey AND o.id.oprDate >= :sinceDateKey
        ORDER BY o.id.oprDate DESC, o.id.oprSeq DESC
        """,
    )
    fun findLatestBefore(
        @Param("dtlId") dtlId: Long,
        @Param("dtlIp") dtlIp: String,
        @Param("dtlLaneNo") dtlLaneNo: Int,
        @Param("beforeDateKey") beforeDateKey: String,
        @Param("sinceDateKey") sinceDateKey: String,
        pageable: Pageable,
    ): List<OprStatus>

    /**
     * 오늘 하루 기록된 분단위 통행 증가분(`opr_user_count`)의 총합 — 대시보드 "오늘 통행량" 위젯.
     *
     * **2026-08-12(B6) 주의**: `uvw_user_cnt` 뷰를 참조하는 레거시 C# 코드를 찾지 못해(SR_Speed_Client/
     * Server 어디에도 없음), 그 뷰가 실제로 대시보드 위젯에 쓰였는지, 쓰였다면 정확히 어떤 집계식이었는지
     * 검증하지 못했다. 이 SUM은 최선 추정이다 — `opr_user_count`는 분 버킷별 순증가분(delta)이므로
     * 하루 전체를 합하면 "오늘 하루 순증가한 총 통행량"이 되어(누적값 없이도) 자정 넘어가는 시점의
     * 카운터 롤오버와 무관하게 안전하다는 점에서 `uvw_user_cnt.user_cnt`를 그대로 MAX로 묶는 것보다
     * 더 안전한 근사치라고 판단했다.
     */
    @Query(
        """
        SELECT COALESCE(SUM(o.userCount), 0) FROM OprStatus o
        WHERE o.useYn = 'Y' AND o.id.oprDate BETWEEN :fromDateKey AND :toDateKey
        """,
    )
    fun sumUserCountToday(@Param("fromDateKey") fromDateKey: String, @Param("toDateKey") toDateKey: String): Long
}
