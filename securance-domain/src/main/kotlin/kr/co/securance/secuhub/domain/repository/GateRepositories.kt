package kr.co.securance.secuhub.domain.repository

import kr.co.securance.secuhub.domain.entity.GateDetail
import kr.co.securance.secuhub.domain.entity.GateGroup
import kr.co.securance.secuhub.domain.entity.GateLocation
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
)

interface GateLocationRepository : JpaRepository<GateLocation, Long>

interface GateGroupRepository : JpaRepository<GateGroup, Long> {
    fun findByLocation_LocId(locId: Long): List<GateGroup>
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
            d.location.locId, d.group.grpId, d.dtlId, d.dtlLaneNo, d.dtlType, d.analysisYn
        )
        FROM GateDetail d
        WHERE d.dtlIp = :dtlIp AND d.useYn = true
        ORDER BY d.dtlLaneNo
        """,
    )
    fun findLaneInfoByDtlIp(@Param("dtlIp") dtlIp: String): List<GateLaneInfo>
}
