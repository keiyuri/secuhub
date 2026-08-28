package kr.co.securance.secuhub.domain.entity

import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import kr.co.securance.secuhub.domain.converter.YnConverter

/**
 * `tb_gate_grp` — 게이트 그룹(레인 여러 개를 묶는 단위).
 *
 * [laneCount]는 논리적 레인 수, [physicalGateCount]는 계획서 3.2절의 도메인 규칙
 * (`gateCount = laneCount + 1`, 인접 레인이 차단바를 공유)에 따른 파생값이다 —
 * 저장하지 않고 항상 계산해서 노출한다(레인 수 변경 시 불일치 방지).
 */
@Entity
@Table(name = "tb_gate_grp")
class GateGroup(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "grp_id")
    val grpId: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "loc_id", nullable = false)
    var location: GateLocation,

    @Column(name = "grp_nm", nullable = false, length = 200)
    var grpName: String,

    @Column(name = "lane_cnt", nullable = false)
    var laneCount: Int = 1,

    /** 연결 방식: 1=1:1, 2=1:N (계획서 3.2절, `securance.server.mode`와는 별개의 축). */
    @Column(name = "link_type", nullable = false)
    var linkType: Int = 1,

    @Convert(converter = YnConverter::class)
    @Column(name = "use_yn", nullable = false)
    var useYn: Boolean = true,

    /** #5 SetupLocation — 소속 [GateLocation]의 배치도 이미지 위 그룹 아이콘 좌표(원본 이미지
     * 픽셀 기준, [GateLocation.locMapWidth]/[GateLocation.locMapHeight]로 환산). 미배치 시 null. */
    @Column(name = "grp_x")
    var grpX: Int? = null,

    @Column(name = "grp_y")
    var grpY: Int? = null,
) {
    /** 물리적 게이트(차단바) 유닛 수 = 레인 수 + 1 (계획서 3.2절 펜스포스트 규칙). */
    val physicalGateCount: Int
        get() = laneCount + 1
}
