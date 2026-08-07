package kr.co.securance.secuhub.domain.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

/**
 * `tb_gate_log` — `GATE_LOG`(ObjectCode `0x61`) 패킷으로 수신한 로그 엔트리(계획서 3.8절, 레거시
 * 미구현 기능이라 신규 설계). 필드는 `securance-protocol`의 `LogEventCodec.LogEvent`를 그대로
 * 옮긴 것이다 — 상세 필드 의미는 그쪽 KDoc 참고.
 *
 * `DataReceiveAnalysis`(tb_data_rcv_anal)와 달리 `loc_id`/`grp_id`를 저장하지 않는다 — 이 테이블은
 * 게이트가 보낸 로그 원본을 그대로 적재하는 용도이고, 위치/그룹은 `tb_gate_dtl`(dtl_ip, dtl_lane_no)
 * 조인으로 필요할 때 구할 수 있어 중복 저장하지 않는다(반정규화를 하지 않기로 한 의도적 결정).
 */
@Entity
@Table(name = "tb_gate_log")
class GateLog(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "log_id")
    val logId: Long? = null,

    @Column(name = "dtl_ip", nullable = false, length = 20)
    var dtlIp: String,

    /** 로그 엔트리의 Module Number(문서상 "Gate Lane Number", 0~14) 필드. */
    @Column(name = "dtl_lane_no", nullable = false)
    var dtlLaneNo: Int,

    @Column(name = "event_type", nullable = false)
    var eventType: Int,

    @Column(name = "object_code", nullable = false)
    var objectCode: Int,

    @Column(name = "code", nullable = false)
    var code: Int,

    @Column(name = "err_code", nullable = false)
    var errCode: Int,

    @Column(name = "operation_mode", nullable = false)
    var operationMode: Int,

    /** 문서상 "Not Used" 필드지만 원본 그대로 보관한다. */
    @Column(name = "reader_type", nullable = false)
    var readerType: Int,

    @Column(name = "reader_number", nullable = false)
    var readerNumber: Int,

    @Column(name = "door_status", nullable = false)
    var doorStatus: Int,

    /** 문서 명시 범위는 0~254이나, 실수신 샘플에서 0xFF(255)가 관측되어 범위를 강제하지 않는다
     * (`LogEventCodec` KDoc/`docs/작업일지.md` 참고). */
    @Column(name = "function_code", nullable = false)
    var functionCode: Int,

    /** 로그 엔트리의 BCD 인코딩 시각을 디코딩한 값(장치 자체 시각 — 수신 시각이 아님). */
    @Column(name = "event_time", nullable = false)
    var eventTime: LocalDateTime,

    /** User Data1(12바이트)의 16진 문자열 — 의미가 다중이라([LogEventCodec] 참고) 파싱하지 않고 원본 보관. */
    @Column(name = "user_data1", length = 24)
    var userData1: String? = null,

    /** User Data2(8바이트, Old User ID)의 16진 문자열. */
    @Column(name = "user_data2", length = 16)
    var userData2: String? = null,

    /** 이 행이 실제로 DB에 적재된 시각(수신 시각) — [eventTime](장치 시각)과 구분된다. */
    @Column(name = "reg_date", insertable = false, updatable = false)
    val regDate: LocalDateTime? = null,
)
