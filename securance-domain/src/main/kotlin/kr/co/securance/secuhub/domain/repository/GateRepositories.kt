package kr.co.securance.secuhub.domain.repository

import kr.co.securance.secuhub.domain.entity.GateDetail
import kr.co.securance.secuhub.domain.entity.GateGroup
import kr.co.securance.secuhub.domain.entity.GateLocation
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface GateLocationRepository : JpaRepository<GateLocation, Long>

interface GateGroupRepository : JpaRepository<GateGroup, Long> {
    // Opus 전체 리뷰 지적: GateGroup.location은 LAZY고 open-in-view: false라, 단순 파생 쿼리로
    // 가져온 뒤 컨트롤러 메서드(트랜잭션 범위) 밖인 뷰 렌더링 단계에서 grp.location.locName을
    // 읽으면 LazyInitializationException이 난다. JOIN FETCH로 쿼리 시점에 함께 로딩한다.
    @Query("select g from GateGroup g join fetch g.location where g.location.locId = :locId")
    fun findByLocation_LocId(locId: Long): List<GateGroup>

    @Query("select g from GateGroup g join fetch g.location")
    override fun findAll(): List<GateGroup>
}

interface GateDetailRepository : JpaRepository<GateDetail, Long> {
    /** 자연키 `(dtl_ip, dtl_lane_no)` 조회 — 게이트 연결 수립 시 사용(계획서 3.1절 `SelectGateInfo` 대응). */
    fun findByDtlIpAndDtlLaneNo(dtlIp: String, dtlLaneNo: Int): GateDetail?

    /**
     * IP로 대표 레인 1건을 조회한다 — SERVER 모드에서 신규 커넥션 수락 시 `dtl_type`(게이트 타입)을
     * 알아내는 데 쓴다(레거시 `SelectGateInfo`처럼 소켓 1개가 여러 레인을 실어나르므로 접속 시점에는
     * 어떤 레인인지 아직 모른다 — 계획서 3.1/3.2절).
     */
    fun findFirstByDtlIpAndUseYnTrueOrderByDtlLaneNo(dtlIp: String): GateDetail?

    /** CLIENT 모드에서 IP 단위로 그룹핑해 아웃바운드 연결 대상을 조회할 때 사용(계획서 3.1절). */
    fun findByUseYnTrueAndAnalysisYnTrueOrderByDtlIp(): List<GateDetail>

    /** #4 SetupGateGroup 화면 — 그룹에 속한 레인(게이트 상세) 목록 조회. */
    fun findByGroup_GrpIdOrderByDtlLaneNo(grpId: Long): List<GateDetail>

    /**
     * #3 SR_F_GateReset — 클라이언트가 보낸 dtlId 목록이 실제로 grpId에 속하는지 서버에서
     * 교차 검증할 때 사용(전체 프로젝트 재감사 지적: 이전에는 dtlId 존재 여부만 확인하고
     * grpId/locId 소속은 확인하지 않았다).
     */
    fun findByDtlIdInAndGroup_GrpId(dtlIds: Collection<Long>, grpId: Long): List<GateDetail>

    /** #6 SetupSchedule(Phase 5) — 위치 단위로 예약 모드를 일괄 적용할 때 사용. */
    fun findByLocation_LocIdAndUseYnTrue(locId: Long): List<GateDetail>

    /** #6 SetupSchedule(Phase 5) — 그룹 단위로 예약 모드를 일괄 적용할 때 사용. */
    fun findByGroup_GrpIdAndUseYnTrue(grpId: Long): List<GateDetail>

    /**
     * #7/#15(Phase 5) 타임존 저장/동기화 — 레거시 `SelectGateDtlIPList`/`InsertSendDataAll`과 동일하게
     * `analysis_yn`은 확인하지 않고 `use_yn='Y'`인 전체 게이트를 대상으로 한다.
     */
    fun findByUseYnTrueOrderByDtlIp(): List<GateDetail>
}
