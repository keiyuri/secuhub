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
 *
 * 레거시는 이 테이블을 애플리케이션이 아니라 MariaDB 저장 프로시저 `usp_process_status`로
 * 채웠다(2026-08-12 B6 조사, `92_DB_Script/20260805/securance_gate/usp_process_status.sql`) —
 * [kr.co.securance.secuhub.server.db.OprStatusPersister]가 이 로직을 그대로 이식한다.
 * `reg_user`/`reg_date`/`mod_user`/`mod_date`는 [DataSend]와 동일하게 DB 컬럼 기본값에
 * 맡기고 엔티티에 매핑하지 않는다(이 코드베이스의 기존 관례).
 */
@Entity
@Table(name = "tb_opr_status")
class OprStatus(
    @EmbeddedId
    val id: OprStatusId,

    @Column(name = "dtl_id")
    var dtlId: Long? = null,

    @Column(name = "dtl_type")
    var dtlType: Int? = null,

    @Column(name = "dtl_no")
    var dtlNo: Int? = null,

    @Column(name = "loc_id")
    var locId: Long? = null,

    @Column(name = "grp_id")
    var grpId: Long? = null,

    @Column(name = "opr_gate_type", length = 20)
    var gateType: String? = null,

    @Column(name = "opr_user_mode", length = 20)
    var userMode: String? = null,

    @Column(name = "opr_security_mode", length = 20)
    var securityMode: String? = null,

    @Column(name = "opr_inout_time")
    var inoutTime: Int? = null,

    /** 이 분(分) 버킷의 통행 증가분(delta) — `uvw_user_cnt.user_cnt`가 그대로 읽는 값. */
    @Column(name = "opr_user_count")
    var userCount: Int? = null,

    @Column(name = "opr_total_count")
    var totalCount: Long? = null,

    /** 직전 레코드(전일 포함 최근 24시간 이내)의 누적 총 카운트 — delta 계산 기준값. */
    @Column(name = "opr_before_total")
    var beforeTotal: Long? = null,

    @Column(name = "opr_in_count")
    var inCount: Int? = null,

    @Column(name = "opr_in_total")
    var inTotal: Long? = null,

    @Column(name = "opr_in_before")
    var inBefore: Long? = null,

    @Column(name = "opr_out_count")
    var outCount: Int? = null,

    @Column(name = "opr_out_total")
    var outTotal: Long? = null,

    @Column(name = "opr_door_count")
    var doorCount: Int? = null,

    @Column(name = "opr_door_total")
    var doorTotal: Long? = null,

    @Column(name = "opr_door_before")
    var doorBefore: Long? = null,

    @Column(name = "use_yn", length = 1)
    var useYn: String = "Y",
)
