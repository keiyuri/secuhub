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

// `DataReceiveLog`(`tb_gate_log_event`, V10)는 2026-08-12 제거됐다 — GATE_LOG(0x61) 패킷을
// 저장하는 두 파이프라인 중 실제로는 도달 불가능한 죽은 경로였음이 확인되어, 코드에서
// GatePacketPersister의 해당 분기를 삭제하면서 이 엔티티도 함께 정리했다(GateLogService/
// tb_gate_log가 실제 사용되는 경로). 테이블 자체는 V20__drop_tb_gate_log_event.sql에서 제거한다.
// 자세한 경위는 docs/작업일지.md 0011 참고.
