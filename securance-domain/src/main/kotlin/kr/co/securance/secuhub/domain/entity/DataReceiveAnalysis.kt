package kr.co.securance.secuhub.domain.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

/**
 * `tb_data_rcv_anal` — 수신 패킷 분석(파싱) 결과. 레거시 4개 샤드 테이블
 * (evt/plm/state + 통합)을 이 테이블 하나로 통합했다(계획서 4.2절).
 *
 * `hasStatusEvent`/`hasErrorEvent`는 MariaDB VIRTUAL 생성 컬럼이므로 애플리케이션에서 쓰지 않고
 * 읽기 전용으로만 매핑한다(insertable=false updatable=false, 계획서 4.1절).
 */
@Entity
@Table(name = "tb_data_rcv_anal")
class DataReceiveAnalysis(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "anal_id")
    val analId: Long? = null,

    @Column(name = "anal_date", nullable = false, length = 20)
    var analDate: String,

    /** NOR(정상) / EVT(이벤트) / PLM(문제) / STA(상태) / ERR(파싱오류). */
    @Column(name = "anal_tp", nullable = false, length = 5)
    var analType: String,

    @Column(name = "dtl_ip", nullable = false, length = 20)
    var dtlIp: String,

    @Column(name = "dtl_lane_no", nullable = false)
    var dtlLaneNo: Int,

    @Column(name = "loc_id")
    var locId: Long? = null,

    @Column(name = "grp_id")
    var grpId: Long? = null,

    @Column(name = "dtl_id")
    var dtlId: Long? = null,

    @Column(name = "desc_gate_status07", length = 50)
    var descFireAlarm: String? = null,

    @Column(name = "desc_gate_status10", length = 50)
    var descMainMotorError: String? = null,

    @Column(name = "desc_gate_status11", length = 50)
    var descSlaveMotorError: String? = null,

    @Column(name = "err_type")
    var errType: Int? = null,

    @Column(name = "resolve_yn", nullable = false, length = 1)
    var resolveYn: String = "N",

    /** 오류를 resolve 처리한 사용자(또는 시스템 주체). 스키마에는 있었으나 엔티티 매핑이 누락되어 있었다. */
    @Column(name = "resolve_user", length = 50)
    var resolveUser: String? = null,

    /** resolve 처리 시각. `SendControlJob`의 리셋 명령 전송 성공 시 `now()`로 채워진다. */
    @Column(name = "resolve_date")
    var resolveDate: LocalDateTime? = null,

    @Column(name = "has_status_event", insertable = false, updatable = false)
    val hasStatusEvent: Boolean = false,

    @Column(name = "has_error_event", insertable = false, updatable = false)
    val hasErrorEvent: Boolean = false,

    @Column(name = "reg_date", insertable = false, updatable = false)
    val regDate: LocalDateTime? = null,
)
