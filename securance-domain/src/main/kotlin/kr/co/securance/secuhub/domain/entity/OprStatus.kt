package kr.co.securance.secuhub.domain.entity

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.io.Serializable

/** `tb_opr_status`의 복합키 `(opr_date, opr_seq, dtl_ip, dtl_lane_no)`. */
@Embeddable
data class OprStatusId(
    @Column(name = "opr_date", length = 20)
    val oprDate: String = "",

    @Column(name = "opr_seq")
    val oprSeq: Int = 1,

    @Column(name = "dtl_ip", length = 20)
    val dtlIp: String = "",

    @Column(name = "dtl_lane_no")
    val dtlLaneNo: Int = 0,
) : Serializable

/**
 * `tb_opr_status` — 분단위 운영 카운터(통행량 대시보드 위젯의 원본 데이터, `uvw_user_cnt` 대응).
 * total/in/out/door 각각 (증가분, 누적, 전일 기준) 3종 카운터를 갖는다.
 */
@Entity
@Table(name = "tb_opr_status")
class OprStatus(
    @EmbeddedId
    val id: OprStatusId,

    @Column(name = "loc_id")
    var locId: Long? = null,

    @Column(name = "grp_id")
    var grpId: Long? = null,

    @Column(name = "opr_total_count")
    var totalCount: Long? = null,

    @Column(name = "opr_in_total")
    var inTotal: Long? = null,

    @Column(name = "opr_out_total")
    var outTotal: Long? = null,

    @Column(name = "opr_door_total")
    var doorTotal: Long? = null,
)
