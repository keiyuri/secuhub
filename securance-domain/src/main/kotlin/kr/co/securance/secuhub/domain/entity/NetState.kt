package kr.co.securance.secuhub.domain.entity

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.io.Serializable
import java.time.LocalDateTime

/** `tb_net_state`의 복합키 `(dtl_ip, dtl_lane_no, loc_id, grp_id)`. */
@Embeddable
data class NetStateId(
    @Column(name = "dtl_ip", length = 20)
    val dtlIp: String = "",

    @Column(name = "dtl_lane_no")
    val dtlLaneNo: Int = 0,

    @Column(name = "loc_id")
    val locId: Long = 0,

    @Column(name = "grp_id")
    val grpId: Long = 0,
) : Serializable

/**
 * `tb_net_state` — 게이트(레인)별 현재 연결 상태. `usp_net_check_data`/`NetCheckJob`이 갱신한다.
 * `dtlState`가 "Y"면 온라인, "N"이면 오프라인(계획서 3.7절 NetCheckJob 참고).
 */
@Entity
@Table(name = "tb_net_state")
class NetState(
    @EmbeddedId
    val id: NetStateId,

    @Column(name = "dtl_state", nullable = false, length = 1)
    var dtlState: String = "N",

    @Column(name = "server_ip", length = 20)
    var serverIp: String? = null,

    @Column(name = "check_time", length = 20)
    var checkTime: String? = null,

    /**
     * 이 행에 마지막으로 반영된 [kr.co.securance.secuhub.server.connection.GateConnectionRegistryImpl]
     * 의 `netStateWriteSequence` 값(코드 리뷰 지적 R-8, V31 마이그레이션) — 더 오래된(작은) seq의
     * 지연 쓰기가 이 행을 덮어쓰지 못하게 막는 조건부 UPSERT([NetStateRepository.upsertIfNewer]
     * 참고)에 쓴다. 이 필드 자체는 JPA로 직접 갱신하지 않는다(엔티티는 조회 전용, 쓰기는 네이티브
     * UPSERT를 거친다) — 여기 있는 값은 화면 표시/디버깅용 참고치일 뿐이다.
     */
    @Column(name = "applied_seq", nullable = false)
    var appliedSeq: Long = 0,

    @Column(name = "mod_date", insertable = false, updatable = false)
    val modDate: LocalDateTime? = null,
) {
    val isOnline: Boolean
        get() = dtlState == "Y"
}
