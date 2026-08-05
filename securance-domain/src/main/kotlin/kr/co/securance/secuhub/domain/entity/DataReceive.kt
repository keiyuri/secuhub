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

    @Column(name = "rcv_header", length = 100)
    var rcvHeader: String? = null,

    @Lob
    @Column(name = "rcv_data")
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

    @Lob
    @Column(name = "rcv_raw")
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

    @Lob
    @Column(name = "ack_raw")
    var ackRaw: String? = null,
)
