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
 *
 * **두 쓰기 경로**: 신규 서버는 [kr.co.securance.secuhub.domain.repository.NetStateRepository.upsertIfNewer]
 * 로, 레거시 저장 프로시저 `usp_net_check_data`는 직접 SQL로 이 테이블을 쓴다. 개발 DB
 * (192.168.0.26:28031) 진단(2026-09-04)에서 `usp_net_check_data`가 `applied_seq` 순서 보장을
 * 전혀 모르는 무조건 UPSERT라는 것이 확인돼, V38 마이그레이션으로 해당 프로시저도 동일한 전역
 * 시퀀스(`tb_net_state_seq`)/조건부 갱신 규칙을 쓰도록 고쳤다 — 두 경로 중 실제로 더 최신인
 * 쪽만 반영된다.
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
