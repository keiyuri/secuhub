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

    /**
     * `dtl_lane_no`와 동일한 값을 다시 담는 레거시 잔존 컬럼 — 레거시 SP `usp_process_status`가
     * `opr_lane_no = vRealLaneNo`(= `dtl_lane_no`)로 채운다(2026-09-08 운영 DB 실측). 이 엔티티에
     * 매핑이 빠져 있어 [OprStatusPersister]가 값을 실은 적이 없었고 항상 NULL로 남아 있었다.
     */
    @Column(name = "opr_lane_no")
    var oprLaneNo: Int? = null,

    @Column(name = "opr_gate_type", length = 20)
    var gateType: String? = null,

    @Column(name = "opr_user_mode", length = 20)
    var userMode: String? = null,

    /**
     * `opr_user_mode`(원시 코드 문자열) → 표시용 설명 문자열(예: "IN(CARD)/OUT(CARD)"). 레거시
     * SP는 `ufnc_get_user_mode()` DB 함수로 채우지만(2026-09-08 운영 DB 실측), 이 앱은 이미
     * 같은 매핑을 [kr.co.securance.secuhub.protocol.GateStatusAnalyzer.describeUserMode]로
     * 갖고 있다(`tb_data_rcv_anal.desc_user_mode`에 이미 쓰는 중) — 엔티티에 매핑이 빠져 있어
     * `tb_opr_status` 쪽은 항상 NULL로 남아 있었다.
     */
    @Column(name = "opr_user_mode_desc", length = 50)
    var userModeDesc: String? = null,

    @Column(name = "opr_security_mode", length = 20)
    var securityMode: String? = null,

    /** [userModeDesc]와 동일한 이유로 매핑이 빠져 있던 `opr_security_mode`의 설명 문자열. */
    @Column(name = "opr_security_mode_desc", length = 50)
    var securityModeDesc: String? = null,

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

    /**
     * 직전 레코드의 누적 출구 통행량 — [outTotal] 재계산 기준값(2026-08-12 codex 적대적 리뷰 지적,
     * B6 후속 수정). 레거시 `usp_process_status`는 `opr_out_total`을 아예 채우지 않아(원문에
     * `opr_out_count`만 UPDATE) 참고할 원본 컬럼이 없었다 — Total/In/Door과 달리 프로토콜에
     * "누적 출구 카운터" 원시 바이트가 없고 `deltaTotal - deltaIn`으로 매번 새로 계산되는 값이기
     * 때문이다. 이 필드는 그 델타를 in/door와 동일한 (증가분, 누적, 전일기준) 3종 패턴으로 맞추기
     * 위해 secuhub에서 새로 도입한 누적값이며, `AccessReportController`가 [outTotal]을 합산해
     * 리포트/CSV에 노출하므로 반드시 채워야 한다.
     */
    @Column(name = "opr_out_before")
    var outBefore: Long? = null,

    @Column(name = "opr_door_count")
    var doorCount: Int? = null,

    @Column(name = "opr_door_total")
    var doorTotal: Long? = null,

    @Column(name = "opr_door_before")
    var doorBefore: Long? = null,

    @Column(name = "use_yn", length = 1)
    var useYn: String = "Y",
)
