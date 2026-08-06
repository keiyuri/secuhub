package kr.co.securance.secuhub.domain.entity

import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import kr.co.securance.secuhub.domain.converter.YnConverter
import java.time.LocalDateTime

/** `tb_gate_loc` — 게이트 설치 위치(최상위 계층: 위치 → 그룹 → 상세). */
@Entity
@Table(name = "tb_gate_loc")
class GateLocation(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "loc_id")
    val locId: Long? = null,

    @Column(name = "loc_nm", nullable = false, length = 200)
    var locName: String,

    @Convert(converter = YnConverter::class)
    @Column(name = "use_yn", nullable = false)
    var useYn: Boolean = true,

    @Column(name = "loc_x")
    var locX: Int? = null,

    @Column(name = "loc_y")
    var locY: Int? = null,

    /** #5 SetupLocation 배치도 이미지 파일명(loc-images 업로드 디렉터리 기준, 확장자 포함). */
    @Column(name = "loc_map", length = 200)
    var locMap: String? = null,

    /** 배치도 원본 이미지 가로/세로 픽셀 — 그룹 좌표([GateGroup.grpX]/[GateGroup.grpY])를
     * 뷰포트 크기와 무관하게 원본 기준으로 저장/환산하기 위한 스케일 기준값(레거시
     * `_mapOriginalWidth`/`_mapOriginalHeight`와 동일 목적). */
    @Column(name = "loc_map_w")
    var locMapWidth: Int? = null,

    @Column(name = "loc_map_h")
    var locMapHeight: Int? = null,

    @Column(name = "reg_date", updatable = false, insertable = false)
    val regDate: LocalDateTime? = null,

    @Column(name = "mod_date", insertable = false, updatable = false)
    val modDate: LocalDateTime? = null,
)
