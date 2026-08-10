package kr.co.securance.secuhub.domain.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Lob
import jakarta.persistence.Table
import java.time.LocalDateTime

/** `tb_data_rcv` — 원시 수신 패킷(고빈도 적재 테이블). */
@Entity
@Table(name = "tb_data_rcv")
class DataReceive(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "rcv_id")
    val rcvId: Long? = null,

    @Column(name = "rcv_date", nullable = false, length = 20)
    var rcvDate: String,

    @Column(name = "dtl_ip", nullable = false, length = 20)
    var dtlIp: String,

    @Column(name = "dtl_lane_no", nullable = false)
    var dtlLaneNo: Int,

    /** 게이트 타입 원시값(`tb_gate_dtl.dtl_type`) — 커넥션 캐시([kr.co.securance.secuhub.domain.repository.GateLaneInfo])에서 채운다. */
    @Column(name = "dtl_type")
    var dtlType: Int? = null,

    @Column(name = "dtl_id")
    var dtlId: Long? = null,

    @Column(name = "loc_id")
    var locId: Long? = null,

    @Column(name = "grp_id")
    var grpId: Long? = null,

    @Column(name = "rcv_header", length = 100)
    var rcvHeader: String? = null,

    @Lob
    @Column(name = "rcv_data", columnDefinition = "longtext")
    var rcvData: String? = null,

    @Column(name = "rcv_tail", length = 20)
    var rcvTail: String? = null,
)

/** `tb_data_rcv_fail` — 보드 타입 불일치 등으로 거부된 원시 패킷(Dead-letter). */
@Entity
@Table(name = "tb_data_rcv_fail")
class DataReceiveFail(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "fail_id")
    val failId: Long? = null,

    @Column(name = "fail_date", nullable = false, length = 20)
    var failDate: String,

    @Column(name = "dtl_ip", length = 20)
    var dtlIp: String? = null,

    /** 레인을 식별할 수 있는 경우에만 채운다 — 체크섬 불일치 패킷은 파싱 자체를 신뢰할 수 없어 보통 null. */
    @Column(name = "dtl_lane_no")
    var dtlLaneNo: Int? = null,

    @Lob
    @Column(name = "rcv_raw", columnDefinition = "longtext")
    var rcvRaw: String? = null,
)

/** `tb_data_rcv_ack` — 게이트가 보낸 ACK 응답. */
@Entity
@Table(name = "tb_data_rcv_ack")
class DataReceiveAck(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ack_id")
    val ackId: Long? = null,

    @Column(name = "ack_date", nullable = false, length = 20)
    var ackDate: String,

    @Column(name = "dtl_ip", nullable = false, length = 20)
    var dtlIp: String,

    @Column(name = "dtl_lane_no", nullable = false)
    var dtlLaneNo: Int,

    @Column(name = "dtl_id")
    var dtlId: Long? = null,

    @Lob
    @Column(name = "ack_raw", columnDefinition = "longtext")
    var ackRaw: String? = null,
)

/**
 * `tb_data_rcv_log` — 게이트 로그 이벤트(Object Code `0x61`, 36바이트 고정 구조) 적재 테이블.
 *
 * 레거시 `TB_DATA_RCV_LOG`(`SR_F_ViewLog`가 조회하던 원본 테이블, `docs/SR_Speed_Client_전환_계획.md`
 * 4절 9번 참고)에 대응한다. 화면(`SR_F_ViewLog` → `/reports/logs`)은 이미 `tb_data_rcv_anal` 재사용
 * 방식으로 완료됐지만(event_type 세분류를 재현하지 못하는 한계가 있었음), 이 테이블은
 * `SpeedGateLogCodec`가 파싱한 원본 필드를 그대로 구조화해 저장하므로 필요하면 화면을 이 테이블
 * 기준으로 다시 붙일 수 있다.
 *
 * `SpeedGateLogCodec.decodeEntry`가 반환하는 [kr.co.securance.secuhub.protocol.GateLogEntry] 1건 =
 * 이 엔티티 1행. 숫자 필드를 전부 `Int`로 두는 이유는 이전 스프린트에서 `TINYINT` 매핑 불일치로
 * Hibernate 스키마 검증이 실패한 사례(V2 마이그레이션 참고)를 재현하지 않기 위해서다 — 마이그레이션도
 * 처음부터 `INT`로 만든다.
 */
@Entity
@Table(name = "tb_data_rcv_log")
class DataReceiveLog(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "log_id")
    val logId: Long? = null,

    /** yyyyMMddHHmm — 조회 화면 필터 기준(레거시 `TB_DATA_RCV_LOG.rcv_date`). */
    @Column(name = "rcv_date", nullable = false, length = 20)
    var rcvDate: String,

    /** yyyyMMddHHmmss — 목록 정렬 기준(레거시 `SelectGateLog ORDER BY sort_date DESC`). */
    @Column(name = "sort_date", nullable = false, length = 20)
    var sortDate: String,

    @Column(name = "dtl_ip", nullable = false, length = 20)
    var dtlIp: String,

    @Column(name = "dtl_lane_no", nullable = false)
    var dtlLaneNo: Int,

    @Column(name = "dtl_type")
    var dtlType: Int? = null,

    @Column(name = "dtl_id")
    var dtlId: Long? = null,

    @Column(name = "loc_id")
    var locId: Long? = null,

    @Column(name = "grp_id")
    var grpId: Long? = null,

    /** [kr.co.securance.secuhub.protocol.SpeedGateProtocolConstants.LogEventType]. */
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

    /** 문서상 "security mode/Reader Type" — Not Used로 명시되어 있으나 원본 값은 보존한다. */
    @Column(name = "reader_type", nullable = false)
    var readerType: Int,

    /** Gate Lane Number(0~14). */
    @Column(name = "module_number", nullable = false)
    var moduleNumber: Int,

    @Column(name = "reader_number", nullable = false)
    var readerNumber: Int,

    /** [kr.co.securance.secuhub.protocol.SpeedGateProtocolConstants.LogDoorStatus]. */
    @Column(name = "door_status", nullable = false)
    var doorStatus: Int,

    @Column(name = "function_code", nullable = false)
    var functionCode: Int,

    /** 장비가 기록한 발생 시각(6바이트 BCD 디코딩). BCD 값이 달력 범위를 벗어나면 null. */
    @Column(name = "event_time")
    var eventTime: LocalDateTime? = null,

    /** User ID(8)+User Revision(4) 또는 Card ID(8) — HEX 문자열. */
    @Column(name = "user_data1", nullable = false, length = 24)
    var userData1: String = "",

    /** Old User ID(8) — HEX 문자열. */
    @Column(name = "user_data2", nullable = false, length = 16)
    var userData2: String = "",

    /** 엔트리 원본 36바이트 HEX — 사후 재해석용. */
    @Lob
    @Column(name = "log_raw", columnDefinition = "longtext")
    var logRaw: String? = null,
)
