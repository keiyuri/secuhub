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

    /**
     * #5 SetupLocation — 소속 [GateLocation]의 배치도 이미지 위 그룹 아이콘 좌표(원본 이미지
     * 픽셀 기준, [GateLocation.locMapWidth]/[GateLocation.locMapHeight]로 환산). 미배치 시 -1.
     *
     * 컬럼 타입 재점검(2026-09-09, 개발 DB 실측): 실제 DB는 `grp_x`/`grp_y` 모두
     * `NOT NULL DEFAULT 0`인데 엔티티는 `Int?`(nullable)로 선언돼 있었다. 이 프로젝트에는
     * `@DynamicInsert`가 없어 Hibernate가 매핑된 컬럼을 항상 INSERT 문에 포함하므로, 신규
     * 등록 화면(`GateGroupController.create`)처럼 값을 지정하지 않으면 Kotlin 기본값 `null`이
     * 그대로 바인딩돼 `NOT NULL` 위반으로 INSERT가 실패한다 — 우선 DB 기본값과 같은 0으로
     * 정정했었다.
     *
     * [Codex 적대적 리뷰 지적, 2026-09-09] 미배치 센티널로 0을 쓰면, 사용자가 배치도 이미지의
     * 정확히 좌상단 모서리(픽셀 0,0)에 마커를 드래그해 놓는 정상적인 저장과 구분되지 않는다 —
     * 저장은 성공하지만 화면을 다시 열면 "미배치"로 오판정되어 임의 폴백 위치에 표시되는 왕복
     * 불변성 붕괴가 생긴다. `grp_x`/`grp_y`는 부호 있는 `int(11)`이라 음수를 저장할 수 있고,
     * 드래그 저장 경로([location-map.html]의 `savePosition`)는 `Math.round(percent/100 * width)`로
     * 항상 0 이상의 픽셀값만 보내 -1이 나올 수 없으므로, 실제 배치 좌표와 절대 충돌하지 않는
     * -1을 미배치 센티널로 쓴다(실측 결과 기존 데이터에도 음수 좌표는 0건).
     */
    @Column(name = "grp_x", nullable = false)
    var grpX: Int = -1,

    @Column(name = "grp_y", nullable = false)
    var grpY: Int = -1,
) {
    /** 물리적 게이트(차단바) 유닛 수 = 레인 수 + 1 (계획서 3.2절 펜스포스트 규칙). */
    val physicalGateCount: Int
        get() = laneCount + 1
}
