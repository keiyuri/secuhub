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

    @Column(name = "reg_date", updatable = false, insertable = false)
    val regDate: LocalDateTime? = null,

    @Column(name = "mod_date", insertable = false, updatable = false)
    val modDate: LocalDateTime? = null,
)
