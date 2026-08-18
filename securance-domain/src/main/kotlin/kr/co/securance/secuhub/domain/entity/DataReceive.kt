package kr.co.securance.secuhub.domain.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Lob
import jakarta.persistence.Table

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

    /**
     * DataInfo 구간(패킷 헤더 바로 뒤, 레인별 상태 블록이 시작되기 전) 16진 문자열 —
     * 헤더의 `DATA_INFO_LENGTH` 필드가 가리키는 실제 길이만큼만 담는다. [rcvData]는 이미
     * DataInfo+레인데이터를 합쳐서 담고 있었지만, 이 컬럼은 V1 스키마에 정의돼 있었음에도
     * 채우는 코드가 없어 항상 NULL로 저장되고 있었다(2026-08-18 확인).
     */
    @Column(name = "rcv_data_info", length = 150)
    var rcvDataInfo: String? = null,

    /** DataInfo 뒤의 레인별 상태 블록 구간 16진 문자열 — 위 [rcvDataInfo]와 짝을 이룬다. */
    @Lob
    @Column(name = "rcv_data_lane", columnDefinition = "longtext")
    var rcvDataLane: String? = null,

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

    /**
     * `ack_header`/`ack_data`/`ack_tail` — [ackRaw](ACK 패킷 전체 원본)를 [rcvHeader]/[rcvData]/
     * [rcvTail]과 같은 Header(27)/Data/Tail(4) 규약으로 나눈 것. V1 스키마에 컬럼은 있었지만
     * 채우는 코드가 없어 항상 NULL(dev DB 실제로는 NOT NULL DEFAULT '')로 저장되고 있었다
     * (2026-08-18 확인).
     */
    @Column(name = "ack_header", length = 100)
    var ackHeader: String? = null,

    @Lob
    @Column(name = "ack_data", columnDefinition = "longtext")
    var ackData: String? = null,

    @Column(name = "ack_tail", length = 20)
    var ackTail: String? = null,
)

// `DataReceiveLog`(`tb_gate_log_event`, V10)는 2026-08-12 제거됐다 — GATE_LOG(0x61) 패킷을
// 저장하는 두 파이프라인 중 실제로는 도달 불가능한 죽은 경로였음이 확인되어, 코드에서
// GatePacketPersister의 해당 분기를 삭제하면서 이 엔티티도 함께 정리했다(GateLogService/
// tb_gate_log가 실제 사용되는 경로). 테이블 자체는 V20__drop_tb_gate_log_event.sql에서 제거한다.
// 자세한 경위는 docs/작업일지.md 0011 참고.
