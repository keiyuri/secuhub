package kr.co.securance.secuhub.domain.repository

import kr.co.securance.secuhub.domain.entity.OprStatus
import kr.co.securance.secuhub.domain.entity.OprStatusId
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional

/** `tb_opr_status` 리포지토리 — 대시보드 통행량 위젯(`uvw_user_cnt` 대응, 계획서 5.2절)의 원본 데이터. */
interface OprStatusRepository : JpaRepository<OprStatus, OprStatusId> {
    /**
     * 코드 리뷰 지적(2026-08-28): 형제 리포트(LogReportController 등)와 달리 이 조회만
     * [Pageable] 없이 조건에 맞는 전체 행을 반환해, 통행량이 많은 그룹을 3개월치로 조회하면
     * 수십만 행이 한 번에 메모리로 올라갈 수 있었다. 화면/엑셀 각각에서 [Pageable]로 건수를
     * 제한해 호출하도록 오버로드를 추가했다(기존 무제한 시그니처는 제거).
     */
    fun findByLocIdAndGrpIdAndIdOprDateBetween(
        locId: Long,
        grpId: Long,
        fromDate: String,
        toDate: String,
        pageable: Pageable,
    ): Page<OprStatus>

    /**
     * 화면 상단 요약 위젯(입/출/도어 합계)은 전체 조회 결과에 대한 집계라 [Pageable]로 페이지를
     * 나눠도 값이 달라지면 안 된다 — 행을 메모리로 가져와 합산하는 대신 DB에서 SUM으로 직접
     * 집계한다(코드 리뷰 지적, 2026-08-28. 위 [findByLocIdAndGrpIdAndIdOprDateBetween] 참고).
     */
    @Query(
        """
        SELECT COALESCE(SUM(o.inTotal), 0) AS totalIn,
               COALESCE(SUM(o.outTotal), 0) AS totalOut,
               COALESCE(SUM(o.doorTotal), 0) AS totalDoor
        FROM OprStatus o
        WHERE o.locId = :locId AND o.grpId = :grpId AND o.id.oprDate BETWEEN :fromDateKey AND :toDateKey
        """,
    )
    fun sumAccessTotals(
        @Param("locId") locId: Long,
        @Param("grpId") grpId: Long,
        @Param("fromDateKey") fromDateKey: String,
        @Param("toDateKey") toDateKey: String,
    ): AccessTotals

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

    /**
     * 코드 리뷰 지적 D-3 대응: `tb_opr_status`는 레인 × 분(分) 버킷마다 1행씩 쌓이는 고빈도
     * 테이블인데도 D5 보관 정책(2026-08-12) 대상에서 빠져 있었다. `opr_date`는 `yyyyMMddHHmm`
     * 문자열이라 사전식 비교가 시간 비교와 일치한다([OprStatusPersister]의 `MINUTE_FORMAT`).
     * 복합키(`opr_date`+`opr_seq`+`dtl_ip`+`dtl_lane_no`) 엔티티라 JPQL DELETE로는 `LIMIT`을 쓸 수
     * 없어 [DataReceiveRepository.deleteBatchOlderThan]과 동일하게 네이티브 쿼리로 작성했다.
     *
     * `@Transactional` 필요 이유도 동일 KDoc 참고(Codex 적대적 리뷰 지적, 2026-08-20, [high]).
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "DELETE FROM tb_opr_status WHERE opr_date < :cutoff LIMIT :batchSize", nativeQuery = true)
    fun deleteBatchOlderThan(@Param("cutoff") cutoff: String, @Param("batchSize") batchSize: Int): Int
}

/** [OprStatusRepository.sumAccessTotals]의 인터페이스 프로젝션 — SUM 3종을 한 번의 쿼리로 묶어 받는다. */
interface AccessTotals {
    val totalIn: Long
    val totalOut: Long
    val totalDoor: Long
}
