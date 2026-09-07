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
     * 게이트 타입 원시값(`tb_gate_dtl.dtl_type`) — 조회 전용. 실제 쓰기는 두 경로(신규 서버의
     * [kr.co.securance.secuhub.domain.repository.NetStateRepository.upsertIfNewer], 레거시
     * `usp_net_check_data`) 모두 네이티브 UPSERT를 거치며, 둘 다 한때 이 컬럼을 INSERT/UPDATE
     * 절에 담지 않아 영구히 NULL로 남는 결함이 있었다(2026-09-07 `tb_net_state` 재점검, V3 마이그레이션).
     */
    @Column(name = "dtl_type", insertable = false, updatable = false)
    val dtlType: Int? = null,

    /** `tb_gate_dtl.dtl_id` — 조회 전용, 위 [dtlType]와 동일한 이유로 네이티브 UPSERT가 채운다. */
    @Column(name = "dtl_id", insertable = false, updatable = false)
    val dtlId: Long? = null,

    /**
     * 이 행을 기록한 인스턴스의 연결 방향(SERVER/CLIENT,
     * [kr.co.securance.secuhub.server.config.GatewayMode].name) — 조회 전용. 코드베이스 전체에서
     * 실제로 이 컬럼을 채우는 곳이 전혀 없어 항상 NULL이었다(2026-09-07 확인) — 신규 서버 경로가
     * 이제 채운다.
     */
    @Column(name = "server_cd", insertable = false, updatable = false)
    val serverCd: String? = null,

    /**
     * 그 시점의 원시 수신 패킷 16진 문자열 — 조회 전용(코드 리뷰 지적 — 처음엔 `insertable/updatable
     * = false`가 빠져 있었다. 지금은 이 컬럼에 `.save()`를 호출하는 프로덕션 코드가 없어 실제로
     * 터지지 않지만, 나중에 누군가 이 엔티티로 `.save()`를 호출하면 기본값 `null`이 DB에 이미 쌓인
     * 값을 조용히 지워버릴 수 있었다 — 위 [dtlType]/[dtlId]/[serverCd]와 동일하게 막는다).
     * 레거시 `usp_net_check_data`는 항상 채웠지만, 신규 서버 경로는 온라인/오프라인 "전이" 이벤트에
     * 원시 패킷이 있을 때만(커넥션 종료로 인한 오프라인 전이처럼 관련 패킷이 없으면 null) 채운다.
     */
    @Column(name = "snd_raw", insertable = false, updatable = false)
    val sndRaw: String? = null,

    /**
     * 이 행에 마지막으로 반영된 [kr.co.securance.secuhub.server.connection.GateConnectionRegistryImpl]
     * 의 `netStateWriteSequence` 값(코드 리뷰 지적 R-8, V31 마이그레이션) — 더 오래된(작은) seq의
     * 지연 쓰기가 이 행을 덮어쓰지 못하게 막는 조건부 UPSERT([NetStateRepository.upsertIfNewer]
     * 참고)에 쓴다. 이 필드 자체는 JPA로 직접 갱신하지 않는다(엔티티는 조회 전용, 쓰기는 네이티브
     * UPSERT를 거친다) — 여기 있는 값은 화면 표시/디버깅용 참고치일 뿐이다.
     */
    @Column(name = "applied_seq", nullable = false)
    var appliedSeq: Long = 0,

    /**
     * `mod_date` 누락 수정(2026-09-08, 사용자 요청 — 운영 DB 실측)까지는 [NetStateRepository.upsertIfNewer]
     * 가 이 컬럼을 INSERT/UPDATE 절에 담지 않아 신규 서버 경로가 쓰는 행은 영구히 NULL이었다.
     * 지금은 그 메서드가 `NOW()`를 직접 실어 보낸다([NetStateRepository.upsertIfNewer] KDoc 참고) —
     * 운영 DB의 실제 컬럼 정의에 `ON UPDATE current_timestamp()`가 없어 DB 트리거에 의존할 수
     * 없기 때문이다. 이 필드 자체는 여전히 조회 전용이다.
     */
    @Column(name = "mod_date", insertable = false, updatable = false)
    val modDate: LocalDateTime? = null,
) {
    val isOnline: Boolean
        get() = dtlState == "Y"
}
