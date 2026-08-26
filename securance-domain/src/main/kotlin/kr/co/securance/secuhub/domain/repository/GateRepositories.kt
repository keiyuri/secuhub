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
    /** 대시보드 트리뷰 전체 조회 — GateLocation은 '분석' 컬럼이 없으므로 '사용'(useYn)만으로 필터한다. */
    fun findByUseYnTrueOrderByLocName(): List<GateLocation>
}

interface GateGroupRepository : JpaRepository<GateGroup, Long> {
    // Opus 전체 리뷰 지적: GateGroup.location은 LAZY고 open-in-view: false라, 단순 파생 쿼리로
    // 가져온 뒤 컨트롤러 메서드(트랜잭션 범위) 밖인 뷰 렌더링 단계에서 grp.location.locName을
    // 읽으면 LazyInitializationException이 난다. JOIN FETCH로 쿼리 시점에 함께 로딩한다.
    // 정렬은 위치ID(loc_id) → 그룹ID(grp_id) 순(2026-08-25 "설치위치ID→그룹ID 순 정렬" 요청).
    @Query("select g from GateGroup g join fetch g.location where g.location.locId = :locId order by g.location.locId, g.grpId")
    fun findByLocation_LocId(locId: Long): List<GateGroup>

    @Query("select g from GateGroup g join fetch g.location order by g.location.locId, g.grpId")
    override fun findAll(): List<GateGroup>

    /** 대시보드 트리뷰 전체 조회 — GateGroup도 '분석' 컬럼이 없으므로 '사용'(useYn)만으로 필터한다. */
    @Query("select g from GateGroup g join fetch g.location where g.useYn = true order by g.location.locId, g.grpId")
    fun findAllByUseYnTrue(): List<GateGroup>

    /**
     * 관리(CRUD) 목록이 아닌 화면(리포트/스케줄/리셋/배치도 등)의 위치별 그룹 드롭다운 — GateGroup은
     * '분석' 컬럼이 없으므로 '사용'(useYn)만으로 필터한다(2026-08-20 "예외 없이 전체 목록 조회에
     * 적용" 지시, 단 위치/그룹/사용자 관리 화면 자체는 사용자 확인에 따라 제외).
     */
    @Query("select g from GateGroup g join fetch g.location where g.location.locId = :locId and g.useYn = true order by g.location.locId, g.grpId")
    fun findByLocation_LocIdAndUseYnTrue(@Param("locId") locId: Long): List<GateGroup>

    /**
     * 단건 조회에도 `location`을 함께 로딩한다 — 기본 [JpaRepository.findById]는 fetch join이
     * 없어, 결과를 뷰 렌더링 단계(트랜잭션 밖)에서 `.location.locName`처럼 실제 컬럼을 읽으면
     * LazyInitializationException이 난다(2026-08-20 Codex 리뷰 지적: 레인 관리 화면의 그룹
     * 콤보에 비활성 그룹을 끼워 넣을 때 이 문제가 재현됐다. 위 40번째 줄 주석과 동일한 근본 원인).
     */
    @Query("select g from GateGroup g join fetch g.location where g.grpId = :grpId")
    fun findByIdWithLocation(@Param("grpId") grpId: Long): GateGroup?
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

    /**
     * #4 SetupGateGroup 화면 — 그룹에 속한 레인(게이트 상세) 목록 조회.
     *
     * 정렬은 위치ID→그룹ID→게이트(레인) ID 순(2026-08-21 사용자 요청 — "게이트 목록의 표시 순서는
     * 화면 어디서든 설치 위치 ID, 그룹 ID, 게이트 ID 순이어야 한다", [GateTreeController]의
     * 트리뷰 정렬 기준과 동일하게 맞춘다). 이 메서드는 grpId로 이미 필터링되어 위치·그룹이
     * 고정되므로 남는 정렬 기준은 게이트 ID(`dtlId`)뿐이다 — 과거에는 `dtlLaneNo`(레인 번호)로
     * 정렬해 등록 순서에 따라 게이트 ID 순서와 어긋날 수 있었다.
     */
    @Query("select d from GateDetail d join fetch d.location join fetch d.group where d.group.grpId = :grpId order by d.dtlId")
    fun findByGroup_GrpIdOrderByDtlId(@Param("grpId") grpId: Long): List<GateDetail>

    /**
     * #4 SetupGateGroup 화면 — 사용/분석 대상(둘 다 'Y')인 레인만 목록에 노출할 때 사용.
     * 정렬 기준은 위 [findByGroup_GrpIdOrderByDtlId]와 동일(게이트 ID 순).
     */
    @Query(
        "select d from GateDetail d join fetch d.location join fetch d.group " +
            "where d.group.grpId = :grpId and d.useYn = true and d.analysisYn = true order by d.dtlId",
    )
    fun findByGroup_GrpIdAndUseYnTrueAndAnalysisYnTrueOrderByDtlId(@Param("grpId") grpId: Long): List<GateDetail>

    /**
     * #3 SR_F_GateReset — 클라이언트가 보낸 dtlId 목록이 실제로 grpId에 속하는지 서버에서
     * 교차 검증할 때 사용(전체 프로젝트 재감사 지적: 이전에는 dtlId 존재 여부만 확인하고
     * grpId/locId 소속은 확인하지 않았다).
     *
     * 목록 화면(GateResetGridService.rowsFor)이 useYn=true·analysisYn=true인 레인만 보여주므로,
     * 이 교차 검증 쿼리도 같은 조건을 강제한다 — 그렇지 않으면 화면에는 보이지 않는 비활성/미분석
     * dtlId를 조작된 POST로 직접 전송했을 때 소속 검증만 통과하고 그대로 리셋 명령까지 도달한다
     * (2026-08-20 Codex 적대적 리뷰 지적: UI 필터가 신뢰 경계에서 강제되지 않던 문제).
     *
     * 상위 그룹/위치의 `useYn`도 함께 강제한다 — 레인 자신은 useYn=true·analysisYn=true라도
     * 소속 그룹이나 위치를 관리자가 비활성화했다면(운영 제외 의도) 조작된 POST로 grpId만 활성
     * 그룹의 것을 넣거나 비활성 그룹/위치 아래 여전히 useYn=true인 레인의 dtlId를 직접 보내는
     * 방식으로 리셋 명령이 등록되면 안 된다(2026-08-20 Codex 적대적 리뷰 지적 — 상위 계층 비활성화
     * 우회 경로).
     */
    @Query(
        "select d from GateDetail d join fetch d.location join fetch d.group " +
            "where d.dtlId in :dtlIds and d.group.grpId = :grpId " +
            "and d.useYn = true and d.analysisYn = true and d.group.useYn = true and d.location.useYn = true",
    )
    fun findByDtlIdInAndGroup_GrpId(@Param("dtlIds") dtlIds: Collection<Long>, @Param("grpId") grpId: Long): List<GateDetail>

    /**
     * #6 SetupSchedule(Phase 5) — 위치 단위로 예약 모드를 일괄 적용할 때 사용. 예약 명령은
     * `analysisYn` 여부와 무관하게 적용 대상이므로(레거시 `SelectGateDtlIPList`/`InsertSendDataAll`과
     * 동일 — [findByUseYnTrueOrderByDtlIp] 주석 참고, 2026-08-20 사용자 확인: "분석=N도 포함,
     * 사용=Y만 필터") 레인 자신의 `analysisYn`은 확인하지 않는다.
     *
     * 다만 이 위치 자신과 그 아래 그룹의 `useYn`은 함께 강제한다 — 위치·그룹 콤보는 활성 항목만
     * 보여주므로(GateLocationService.findAllActive/GateGroupService.findAllActiveByLocation) 정상
     * 경로에서는 비활성 위치/그룹의 locId/grpId가 애초에 선택되지 않지만, 조작된 POST로 비활성
     * 위치의 locId를 직접 보내면 그 아래 useYn=true인 레인에까지 예약 명령이 나갈 수 있었다
     * (2026-08-20 Codex 적대적 리뷰 지적 — 상위 계층 비활성화 우회 경로, GateResetController의
     * findByDtlIdInAndGroup_GrpId와 동일한 문제).
     */
    @Query(
        "select d from GateDetail d join fetch d.location join fetch d.group " +
            "where d.location.locId = :locId and d.useYn = true and d.location.useYn = true and d.group.useYn = true " +
            "order by d.group.grpId, d.dtlId",
    )
    fun findByLocation_LocIdAndUseYnTrue(@Param("locId") locId: Long): List<GateDetail>

    /**
     * #6 SetupSchedule(Phase 5) — 그룹 단위로 예약 모드를 일괄 적용/스케줄 화면의 레인 콤보에 쓴다.
     * 위 [findByLocation_LocIdAndUseYnTrue]와 동일한 이유로 레인 자신의 `analysisYn`은 확인하지
     * 않지만, 그룹·위치의 `useYn`은 함께 강제한다(같은 이유 — 조작된 POST로 비활성 그룹의 grpId를
     * 직접 보내는 우회 경로 차단).
     *
     * 화면의 레인 콤보(스케줄 화면)에 그대로 노출되는 목록이므로, 명시적인 `ORDER BY`가 없어 DB의
     * 임의 순서에 맡겨져 있던 것을 게이트 ID(`dtlId`) 순으로 고정한다(2026-08-21 사용자 요청 —
     * "게이트 목록의 표시 순서는 화면 어디서든 설치 위치 ID, 그룹 ID, 게이트 ID 순이어야 한다";
     * 이미 grpId로 필터링돼 위치·그룹이 고정이므로 남는 정렬 기준은 dtlId뿐이다).
     */
    @Query(
        "select d from GateDetail d join fetch d.location join fetch d.group " +
            "where d.group.grpId = :grpId and d.useYn = true and d.group.useYn = true and d.location.useYn = true " +
            "order by d.dtlId",
    )
    fun findByGroup_GrpIdAndUseYnTrue(@Param("grpId") grpId: Long): List<GateDetail>

    /**
     * #6 SetupSchedule(Phase 5) — 단건(dtlId) 예약 모드 대상. [findByGroup_GrpIdAndUseYnTrue]와
     * 동일한 이유로 레인 자신의 `useYn`뿐 아니라 그룹·위치의 `useYn`도 함께 강제한다.
     */
    @Query(
        "select d from GateDetail d join fetch d.location join fetch d.group " +
            "where d.dtlId = :dtlId and d.useYn = true and d.group.useYn = true and d.location.useYn = true",
    )
    fun findByIdAndUseYnTrueWithActiveParents(@Param("dtlId") dtlId: Long): GateDetail?

    /**
     * #7/#15(Phase 5) 타임존 저장/동기화 — 레거시 `SelectGateDtlIPList`/`InsertSendDataAll`과 동일하게
     * `analysis_yn`은 확인하지 않고 `use_yn='Y'`인 전체 게이트를 대상으로 한다.
     *
     * CLIENT 모드 아웃바운드 연결 대상 조회(`GateTcpClient.connectToAllDevices`)에도 동일하게
     * 쓰인다 — SERVER 모드(`findFirstByDtlIpAndUseYnTrueOrderByDtlLaneNo`)와 마찬가지로
     * analysis_yn 무관 전체 레인을 대상으로 해야, 모든 레인이 analysis_yn='N'인 디바이스도
     * 연결 대상에서 누락되지 않는다(2026-08-20 코드 리뷰 지적 수정 — 과거
     * `findByUseYnTrueAndAnalysisYnTrueOrderByDtlIp`는 이 메서드로 대체되며 제거됨).
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
     * GateDetail은 '사용'(useYn)과 '분석'(analysisYn) 컬럼을 모두 가지므로 둘 다 true인
     * 레인만 조회한다(GateLocation/GateGroup은 '분석' 컬럼이 없어 useYn만으로 필터한다).
     *
     * 정렬은 위치ID→그룹ID→게이트(레인) ID 순(2026-08-21 사용자 요청 — "게이트 목록의 표시
     * 순서는 화면 어디서든 설치 위치 ID, 그룹 ID, 게이트 ID 순이어야 한다"; [findByLocation_LocIdAndUseYnTrue]
     * 등 다른 게이트 목록 쿼리들과 동일 기준). `GateTreeController.buildTreeUncached()`가 결과를
     * `.sortedBy { locId }`/`.sortedBy { grpId }`/`.sortedBy { dtlId }`로 다시 정렬하므로 이 쿼리의
     * `ORDER BY`가 최종 화면 순서를 좌우하지는 않지만, 리포지토리 메서드 자체가 실제와 다른 정렬
     * 기준(과거 `dtlLaneNo` — 등록 순서에 따라 게이트 ID 순서와 어긋날 수 있었다)을 이름에 걸고
     * 있으면 다른 화면에서 이 메서드를 재사용할 때 오해를 부른다.
     */
    @Query(
        "select d from GateDetail d join fetch d.location join fetch d.group " +
            "where d.useYn = true and d.analysisYn = true " +
            "order by d.location.locId, d.group.grpId, d.dtlId",
    )
    fun findAllForTree(): List<GateDetail>
}
