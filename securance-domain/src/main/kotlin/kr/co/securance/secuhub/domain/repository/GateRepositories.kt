package kr.co.securance.secuhub.domain.repository

import kr.co.securance.secuhub.domain.entity.GateDetail
import kr.co.securance.secuhub.domain.entity.GateGroup
import kr.co.securance.secuhub.domain.entity.GateLocation
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * 커넥션 수립 시 1회만 조회해 캐시하는 게이트(레인) 식별 정보.
 *
 * 레거시 `ClsAsyncObj.GateLaneInfo`(연결 시 `SelectGateInfo` 1회 조회 후 캐시)에 대응한다.
 * 패킷마다 `tb_gate_dtl`을 재조회하면 고빈도 수신 경로에서 N+1 조회가 발생하므로(레거시 M-8),
 * 커넥션 상태에 이 값을 담아두고 `loc_id`/`grp_id`/`dtl_id`를 그대로 사용한다.
 */
data class GateLaneInfo(
    val locId: Long,
    val grpId: Long,
    val dtlId: Long?,
    val dtlLaneNo: Int,
    val dtlType: Int,
    /** 분석/제어 대상 여부 — false면 상세 저장/제어 전송에서 제외한다. */
    val analysisYn: Boolean,
    /** `tb_gate_dtl.dtl_nm` — `tb_data_rcv_anal.desc_gate_name` 적재에 쓴다(2026-08-14 매핑 누락 수정). */
    val dtlName: String? = null,
)

interface GateLocationRepository : JpaRepository<GateLocation, Long> {
    /** 대시보드 게이트 트리뷰 전용 — 비활성(사용안함) 위치는 트리에서 아예 숨긴다(2026-08-21). */
    fun findByUseYnTrueOrderByLocName(): List<GateLocation>
}

interface GateGroupRepository : JpaRepository<GateGroup, Long> {
    // Opus 전체 리뷰 지적: GateGroup.location은 LAZY고 open-in-view: false라, 단순 파생 쿼리로
    // 가져온 뒤 컨트롤러 메서드(트랜잭션 범위) 밖인 뷰 렌더링 단계에서 grp.location.locName을
    // 읽으면 LazyInitializationException이 난다. JOIN FETCH로 쿼리 시점에 함께 로딩한다.
    @Query("select g from GateGroup g join fetch g.location where g.location.locId = :locId")
    fun findByLocation_LocId(locId: Long): List<GateGroup>

    @Query("select g from GateGroup g join fetch g.location")
    override fun findAll(): List<GateGroup>

    /**
     * 대시보드 게이트 트리뷰 전용 — 비활성(사용안함) 그룹은 트리에서 아예 숨긴다(2026-08-21).
     *
     * Opus 전체 리뷰 지적(2026-08-21): mains 브랜치에 이미 동일 기능이 `findAllByUseYnTrue`라는
     * 이름으로 존재해, `findByUseYnTrue`로 남겨두면 병합 시 두 메서드가 동시에 남거나
     * mains의 `GateTreeServiceTest`(이 메서드명을 스텁함)가 컴파일 실패한다. 이름을 mains와
     * 통일한다.
     */
    @Query("select g from GateGroup g join fetch g.location where g.useYn = true")
    fun findAllByUseYnTrue(): List<GateGroup>
}

interface GateDetailRepository : JpaRepository<GateDetail, Long> {
    // 아래 조회 메서드들은 전부 GateDetail.location/group(둘 다 LAZY)을 반환한다. open-in-view:
    // false 환경에서 호출측이 트랜잭션 밖(뷰 렌더링 등)에서 .location/.group을 읽으면
    // LazyInitializationException이 난다 — findAllForTree/GateGroupRepository.findAll이 이미
    // 겪은 문제와 동일하다. 파생 쿼리 대신 명시적 JOIN FETCH로 통일해 재발을 막는다(2026-08-13
    // 코드 리뷰 — 일부 메서드에만 반영돼 있던 fetch join을 전체로 확장).

    /** 자연키 `(dtl_ip, dtl_lane_no)` 조회 — 게이트 연결 수립 시 사용(계획서 3.1절 `SelectGateInfo` 대응). */
    @Query("select d from GateDetail d join fetch d.location join fetch d.group where d.dtlIp = :dtlIp and d.dtlLaneNo = :dtlLaneNo")
    fun findByDtlIpAndDtlLaneNo(@Param("dtlIp") dtlIp: String, @Param("dtlLaneNo") dtlLaneNo: Int): GateDetail?

    /**
     * IP로 대표 레인 1건을 조회한다 — SERVER 모드에서 신규 커넥션 수락 시 `dtl_type`(게이트 타입)을
     * 알아내는 데 쓴다(레거시 `SelectGateInfo`처럼 소켓 1개가 여러 레인을 실어나르므로 접속 시점에는
     * 어떤 레인인지 아직 모른다 — 계획서 3.1/3.2절).
     *
     * 2026-08-13 코드 리뷰(Codex) 지적: `@Query`(선언 쿼리)에는 메서드 이름의 `findFirst...`가
     * 자동 적용되지 않는다 — 동일 IP에 활성 레인이 2개 이상인 정상적인 서버 모드 구성에서 이
     * 쿼리가 여러 건을 반환하면 단일 엔티티(`GateDetail?`) 반환 과정에서
     * `IncorrectResultSizeDataAccessException`이 터져 신규 게이트 연결 수락 자체가 실패했다.
     * `Pageable`로 결과를 1건으로 제한해 재현을 막는다.
     */
    @Query("select d from GateDetail d join fetch d.location join fetch d.group where d.dtlIp = :dtlIp and d.useYn = true order by d.dtlLaneNo")
    fun findFirstByDtlIpAndUseYnTrueOrderByDtlLaneNo(@Param("dtlIp") dtlIp: String, pageable: Pageable): List<GateDetail>

    /** CLIENT 모드에서 IP 단위로 그룹핑해 아웃바운드 연결 대상을 조회할 때 사용(계획서 3.1절). */
    @Query("select d from GateDetail d join fetch d.location join fetch d.group where d.useYn = true and d.analysisYn = true order by d.dtlIp")
    fun findByUseYnTrueAndAnalysisYnTrueOrderByDtlIp(): List<GateDetail>

    /** #4 SetupGateGroup 화면 — 그룹에 속한 레인(게이트 상세) 목록 조회. */
    @Query("select d from GateDetail d join fetch d.location join fetch d.group where d.group.grpId = :grpId order by d.dtlLaneNo")
    fun findByGroup_GrpIdOrderByDtlLaneNo(@Param("grpId") grpId: Long): List<GateDetail>

    /**
     * #3 SR_F_GateReset — 클라이언트가 보낸 dtlId 목록이 실제로 grpId에 속하는지 서버에서
     * 교차 검증할 때 사용(전체 프로젝트 재감사 지적: 이전에는 dtlId 존재 여부만 확인하고
     * grpId/locId 소속은 확인하지 않았다).
     */
    @Query("select d from GateDetail d join fetch d.location join fetch d.group where d.dtlId in :dtlIds and d.group.grpId = :grpId")
    fun findByDtlIdInAndGroup_GrpId(@Param("dtlIds") dtlIds: Collection<Long>, @Param("grpId") grpId: Long): List<GateDetail>

    /** #6 SetupSchedule(Phase 5) — 위치 단위로 예약 모드를 일괄 적용할 때 사용. */
    @Query("select d from GateDetail d join fetch d.location join fetch d.group where d.location.locId = :locId and d.useYn = true")
    fun findByLocation_LocIdAndUseYnTrue(@Param("locId") locId: Long): List<GateDetail>

    /** #6 SetupSchedule(Phase 5) — 그룹 단위로 예약 모드를 일괄 적용할 때 사용. */
    @Query("select d from GateDetail d join fetch d.location join fetch d.group where d.group.grpId = :grpId and d.useYn = true")
    fun findByGroup_GrpIdAndUseYnTrue(@Param("grpId") grpId: Long): List<GateDetail>

    /**
     * #7/#15(Phase 5) 타임존 저장/동기화 — 레거시 `SelectGateDtlIPList`/`InsertSendDataAll`과 동일하게
     * `analysis_yn`은 확인하지 않고 `use_yn='Y'`인 전체 게이트를 대상으로 한다.
     */
    @Query("select d from GateDetail d join fetch d.location join fetch d.group where d.useYn = true order by d.dtlIp")
    fun findByUseYnTrueOrderByDtlIp(): List<GateDetail>

    /**
     * 커넥션 수립 시 1회 호출해 이 IP가 실어나르는 **모든 레인**의 식별 정보를 한 번에 읽는다.
     *
     * 엔티티 대신 [GateLaneInfo] 프로젝션을 쓰는 이유: `loc_id`/`grp_id`만 필요한데
     * `GateDetail`을 로드하면 LAZY 연관(`location`/`group`)이 커넥션 스레드 밖에서 초기화되며
     * 트랜잭션 경계 문제를 일으킬 수 있다. FK 컬럼만 읽으면 조인조차 발생하지 않는다.
     */
    @Query(
        """
        SELECT new kr.co.securance.secuhub.domain.repository.GateLaneInfo(
            d.location.locId, d.group.grpId, d.dtlId, d.dtlLaneNo, d.dtlType, d.analysisYn, d.dtlName
        )
        FROM GateDetail d
        WHERE d.dtlIp = :dtlIp AND d.useYn = true
        ORDER BY d.dtlLaneNo
        """,
    )
    fun findLaneInfoByDtlIp(@Param("dtlIp") dtlIp: String): List<GateLaneInfo>

    /**
     * Phase 10 대시보드 게이트 트리뷰 — LOC/GRP/DTL 전체를 한 번에 조회한다. `location`/`group`은
     * LAZY + open-in-view:false라 [GateGroupRepository.findAll]과 동일한 이유로 JOIN FETCH가
     * 필요하다(컨트롤러 트랜잭션 밖에서 접근 시 LazyInitializationException).
     *
     * 2026-08-21: 사용여부(`use_yn`)뿐 아니라 분석여부(`analysis_yn`)도 'Y'인 레인만 노출하도록
     * 조건을 추가했다 — analysis_yn=false는 SendControlJob/NetCheckJob이 건너뛰는 대상이라
     * 트리에 보여도 실제로는 갱신되지 않는 죽은 항목이었다(사용자 요청).
     */
    @Query("select d from GateDetail d join fetch d.location join fetch d.group where d.useYn = true and d.analysisYn = true order by d.dtlLaneNo")
    fun findAllForTree(): List<GateDetail>
}
