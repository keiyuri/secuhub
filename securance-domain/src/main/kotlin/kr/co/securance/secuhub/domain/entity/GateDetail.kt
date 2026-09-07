package kr.co.securance.secuhub.domain.entity

import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import kr.co.securance.secuhub.domain.converter.YnConverter

/**
 * `tb_gate_dtl` — 게이트(레인) 상세. 실제 하드웨어 커넥션의 대상이 되는 최소 단위.
 *
 * 자연키 `(dtlIp, dtlLaneNo)`가 레거시 뷰 전반에서 실제 조인 키로 쓰이므로(계획서 4.2절)
 * 대리키(dtlId)와 별개로 유니크 제약을 유지한다.
 */
@Entity
@Table(
    name = "tb_gate_dtl",
    uniqueConstraints = [UniqueConstraint(name = "uq_gate_dtl_ip_lane", columnNames = ["dtl_ip", "dtl_lane_no"])],
)
class GateDetail(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "dtl_id")
    val dtlId: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "loc_id", nullable = false)
    var location: GateLocation,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "grp_id", nullable = false)
    var group: GateGroup,

    @Column(name = "dtl_ip", nullable = false, length = 20)
    var dtlIp: String,

    @Column(name = "dtl_lane_no", nullable = false)
    var dtlLaneNo: Int,

    /** 게이트 타입 원시값(1=Speed,2=Flap,3=Turn,4=Fast) — [kr.co.securance.secuhub.common.gate.GateTypeCodes] 참고. */
    @Column(name = "dtl_type", nullable = false)
    var dtlType: Int,

    /** 1=Net/Wifi, 4=BLE. */
    @Column(name = "connect_type", nullable = false)
    var connectType: Int = 1,

    @Column(name = "dtl_nm", length = 200)
    var dtlName: String? = null,

    /**
     * Serial 연결 번호 — TCP 경로에서는 실질적으로 미사용이지만, `tb_opr_status.dtl_no`에 그대로
     * 적재하는 원본 값이다(2026-09-08 운영 DB 실측 — [kr.co.securance.secuhub.server.db.OprStatusPersister]가
     * 이 컬럼을 조회할 방법이 없어 항상 0을 하드코딩했었다). 레거시 `usp_rcv_data_raw`는
     * `SELECT dtl_no FROM tb_gate_dtl ...`로 이 값을 그대로 조회해 넘긴다.
     *
     * **nullable로 두는 이유(Codex 리뷰 지적, 2026-09-08)**: 이 저장소의 `V1__init_schema.sql`은
     * `dtl_no`를 `DEFAULT NULL`로 정의한다(운영 DB 실측은 `NOT NULL DEFAULT 1`이지만, V3/V4가
     * 이미 확인했듯 BASELINE 환경의 실제 스키마는 V1 문서와 다를 수 있다 — 신규 DB 기준으로는
     * NULL이 가능하다고 봐야 한다). non-null `Int`로 매핑하면 NULL 행을 읽을 때 Hibernate가
     * 프로젝션 생성 자체에 실패해 그 게이트의 커넥션 초기화가 통째로 막힌다 — `Int?`로 두고
     * 소비 측([kr.co.securance.secuhub.server.db.OprStatusPersister])에서 안전한 기본값을 적용한다.
     */
    @Column(name = "dtl_no")
    var dtlNo: Int? = null,

    @Convert(converter = YnConverter::class)
    @Column(name = "use_yn", nullable = false)
    var useYn: Boolean = true,

    /** 분석/제어 대상 여부 — false면 SendControlJob/NetCheckJob 등이 이 레인을 건너뛴다. */
    @Convert(converter = YnConverter::class)
    @Column(name = "analysis_yn", nullable = false)
    var analysisYn: Boolean = true,
)
