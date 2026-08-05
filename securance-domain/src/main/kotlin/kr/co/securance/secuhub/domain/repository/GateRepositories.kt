package kr.co.securance.secuhub.domain.repository

import kr.co.securance.secuhub.domain.entity.GateDetail
import kr.co.securance.secuhub.domain.entity.GateGroup
import kr.co.securance.secuhub.domain.entity.GateLocation
import org.springframework.data.jpa.repository.JpaRepository

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
}
