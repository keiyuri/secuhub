package kr.co.securance.secuhub.domain.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

/**
 * `tb_opr_status_outbox` — 큐 드롭 durable 재작성(2026-08-12, `docs/작업일지.md` 참고).
 *
 * [kr.co.securance.secuhub.server.db.GateDbWriteQueue]가 `UpsertOprStatus` 작업을 드롭하거나
 * (큐 포화) 재시도를 모두 소진해도 끝내 실패하면, [kr.co.securance.secuhub.server.db.OprStatusPersister]가
 * 이 테이블에 원래 UPSERT에 필요한 원시값을 동기(direct) 저장한다 — `GateDbWriteQueue`의 비동기
 * 큐를 다시 거치지 않고, 이미 실패가 확인된 시점에 그 즉시 최소한의 durable 기록을 남기는 것이
 * 목적이다. `OprStatusOutboxReplayJob`(securance-scheduler)이 주기적으로 미처리 행을 읽어 원래
 * UPSERT를 재시도하고, 성공하면 [processed]를 true로 표시한다.
 *
 * 이 fallback 저장 자체가 실패하는 경우(예: DB 자체가 완전히 다운된 상태)는 여전히 데이터가
 * 유실된다 — 다만 그 경우는 원본 작업도 같은 DB에 쓰려던 것이었으므로 애초에 무엇을 해도 손실을
 * 막을 수 없는 이중 장애 상황이며, 로그로 크게 남긴다([OprStatusPersister] 참고).
 */
@Entity
@Table(name = "tb_opr_status_outbox")
class OprStatusOutbox(
    @Column(name = "dtl_ip", length = 20)
    var dtlIp: String,

    @Column(name = "dtl_lane_no")
    var dtlLaneNo: Int,

    @Column(name = "dtl_id")
    var dtlId: Long,

    @Column(name = "dtl_type")
    var dtlType: Int,

    @Column(name = "loc_id")
    var locId: Long,

    @Column(name = "grp_id")
    var grpId: Long,

    /** 분(分) 버킷 키 — [kr.co.securance.secuhub.server.db.OprStatusPersister]의 `dateKey`. */
    @Column(name = "opr_date", length = 20)
    var oprDate: String,

    /** PREV 조회 하한 — [kr.co.securance.secuhub.server.db.OprStatusPersister]의 `sinceDateKey`. */
    @Column(name = "since_date", length = 20)
    var sinceDate: String,

    @Column(name = "curr_total")
    var currTotal: Long,

    @Column(name = "curr_door")
    var currDoor: Long,

    @Column(name = "curr_in")
    var currIn: Long,

    @Column(name = "gate_type_raw")
    var gateTypeRaw: Int,

    @Column(name = "user_mode_raw")
    var userModeRaw: Int,

    @Column(name = "security_mode_raw")
    var securityModeRaw: Int,

    @Column(name = "inout_time")
    var inoutTime: Int,

    /** `DROPPED`(큐 포화) 또는 `FINAL_FAILURE`(재시도 소진) — 관측/디버깅용, 재처리 로직은 동일하다. */
    @Column(name = "reason", length = 20)
    var reason: String,

    @Column(name = "retry_count")
    var retryCount: Int = 0,

    @Column(name = "processed")
    var processed: Boolean = false,

    @Column(name = "processed_date")
    var processedDate: LocalDateTime? = null,

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "outbox_id")
    var outboxId: Long? = null,
)
