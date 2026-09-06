package kr.co.securance.secuhub.domain.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Lob
import jakarta.persistence.Table
import java.time.LocalDateTime

/**
 * `tb_data_rcv_anal` — 수신 패킷 분석(파싱) 결과. 레거시 4개 샤드 테이블
 * (evt/plm/state + 통합)을 이 테이블 하나로 통합했다(계획서 4.2절).
 * 실제 스키마는 `anal_tp`(NOR/EVT/PLM/STA) LIST 파티션으로 나뉜다.
 *
 * 레거시에서 이 테이블을 채우던 주체는 **DB 트리거**(`utrg_data_rcv_anlz`)였다. 2차 스프린트에서
 * 분석 로직을 애플리케이션([kr.co.securance.secuhub.protocol.GateStatusAnalyzer])으로 끌어올렸으므로
 * 트리거가 쓰던 컬럼을 여기에 매핑한다.
 *
 * `hasStatusEvent`/`hasErrorEvent`는 MariaDB VIRTUAL 생성 컬럼이므로 애플리케이션에서 쓰지 않고
 * 읽기 전용으로만 매핑한다(insertable=false updatable=false, 계획서 4.1절).
 *
 * `desc_*` 컬럼은 전부 `NOT NULL DEFAULT ''`이므로 기본값을 빈 문자열로 둔다 — JPA는 매핑된
 * 컬럼을 모두 INSERT 문에 포함하므로 null을 넣으면 제약 위반이 난다.
 */
@Entity
@Table(name = "tb_data_rcv_anal")
class DataReceiveAnalysis(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "anal_id")
    val analId: Long? = null,

    /** 분석 시각 `yyyyMMddHHmm`. */
    @Column(name = "anal_date", nullable = false, length = 20)
    var analDate: String,

    /** NOR(정상) / EVT(이벤트) / PLM(장애) / STA(상태 변경) — 파티션 키를 겸한다. */
    @Column(name = "anal_tp", nullable = false, length = 5)
    var analTp: String,

    /** 분석 데이터 형식 구분. 레거시 트리거가 항상 'B'(binary)를 넣었으므로 동일하게 둔다. */
    @Column(name = "anal_type", nullable = false, length = 5)
    var analType: String = "B",

    @Column(name = "dtl_ip", nullable = false, length = 20)
    var dtlIp: String,

    @Column(name = "dtl_lane_no", nullable = false)
    var dtlLaneNo: Int,

    /**
     * 게이트 종류(1:Speed, 2:Flap, 3:Turn, 4:Fast).
     *
     * 코드 리뷰 지적(2026-08-14): `tb_data_rcv_anal.dtl_type` 컬럼은 V8 마이그레이션이 명시적으로
     * `NULL` 허용으로 정합화했다(레거시 데이터 중 NULL 행이 실제로 존재) — 앱 코드는 항상 채워
     * 넣지만, 과거 데이터를 조회할 때 이 필드가 `nullable=false`였다면 hydration 시점에 예외가
     * 났다. 스키마를 강제로 NOT NULL로 되돌리는 대신(운영 데이터의 기존 NULL 행을 깨뜨릴 위험)
     * 엔티티를 실제 스키마에 맞춘다.
     */
    @Column(name = "dtl_type")
    var dtlType: Int? = 1,

    /**
     * `dtl_type`의 문자열 코드값(예: "1") — 레거시 `usp_process_analysis`가 함께 채우던 컬럼인데
     * 이 저장소 Flyway 이력이 만든 적이 없어(V30에서 뒤늦게 추가, [anal_data_stx] 등과 동일한
     * 경위) 엔티티에도 매핑이 없었다. 실 DB 조회(2026-08-18)로 secuhub가 저장한 행만 이 컬럼이
     * NULL인 것을 확인했다 — [dtlType]과 항상 같은 값의 문자열이므로 그대로 반영한다.
     */
    @Column(name = "dtl_type_cd", length = 10)
    var dtlTypeCd: String? = null,

    @Column(name = "dtl_no", nullable = false)
    var dtlNo: Int = 1,

    @Column(name = "dtl_id", nullable = false)
    var dtlId: Long = 0,

    @Column(name = "loc_id")
    var locId: Long? = null,

    @Column(name = "grp_id")
    var grpId: Long? = null,

    /** 원본 수신 시각 `yyyyMMddHHmm` — `tb_data_rcv.rcv_date`와 같은 값을 넣는다. */
    @Column(name = "rcv_date", nullable = false, length = 20)
    var rcvDate: String,

    /** `tb_data_rcv.rcv_id`. 비동기 적재라 원본 행의 PK를 모를 수 있어 0을 허용한다. */
    @Column(name = "rcv_id", nullable = false)
    var rcvId: Long = 0,

    /** 분석 근거가 된 원시 상태 블록(16진) — 사후 재분석/디버깅용. */
    @Lob
    @Column(name = "rcv_raw", columnDefinition = "longtext")
    var rcvRaw: String? = null,

    @Column(name = "anal_header", length = 100)
    var analHeader: String? = null,

    @Lob
    @Column(name = "anal_data", columnDefinition = "longtext")
    var analData: String? = null,

    @Column(name = "anal_tail", length = 20)
    var analTail: String? = null,

    /** 패킷 오브젝트 코드(예: `4D`=상태, `4E`=전원오류, `4C`=설정). */
    @Column(name = "obj_cd", nullable = false, length = 10)
    var objCd: String = "",

    // ── 레거시 `anal_data_*` 컬럼(2026-08-14 코드 리뷰 지적 대응, 2026-08-26 갱신) ────────
    //
    // 원래 33개였으나, GateControl(별도 저장소)이 2026-08-26 dev DB(192.168.0.26:28031/
    // securance_gate)에서 write-only로 판단한 30개 컬럼을 `usp_process_analysis_v4`
    // INSERT 문에서 제거하며 실제로 DROP했다(GateControl 커밋 8c840c3/41696f2, dev DB
    // 실측 검증 완료). secuhub 엔티티가 그 30개를 계속 매핑하고 있으면 상태 패킷(objectCode
    // `4D`, 최고빈도)마다 "Unknown column 'anal_data_stx' in 'field list'"로 INSERT가
    // 전면 실패하므로 이 엔티티에서도 함께 제거한다.
    //
    // GateControl이 계속 유지하는 3개만 남긴다: `anal_data_object_code`(GateControl 대시보드가
    // `obj_cd` 대신 조회), `anal_data_motor_operation_count`/`anal_data_master_in_total_count`
    // (write-only 판단에서 제외됨 — 이유는 GateControl 쪽 마이그레이션 문서 참고).

    /** 헤더 오브젝트 코드 — [SpeedGateProtocolConstants.HeaderOffset.OBJECT_CODE]. `obj_cd`와 동일 바이트. */
    @Column(name = "anal_data_object_code", nullable = false, length = 10)
    var analDataObjectCode: String = "",

    /** 모터 카운트(4바이트) — `desc_motor_count`의 hex 원본. [GateStatusAnalyzer.StatusOffset.MOTOR_COUNT]. */
    @Column(name = "anal_data_motor_operation_count", nullable = false, length = 20)
    var analDataMotorOperationCount: String = "",

    /** Master-In 누적 카운트(4바이트) — `desc_master_in_total`의 hex 원본. [GateStatusAnalyzer.StatusOffset.MASTER_IN_COUNT]. */
    @Column(name = "anal_data_master_in_total_count", nullable = false, length = 20)
    var analDataMasterInTotalCount: String = "",

    // ── 헤더/정보 영역 해석값 ────────────────────────────────────────
    // 2026-09-04: 개발 DB(192.168.0.26:28031) 실측 기준으로 desc_data_info_length/
    // desc_gate_lane_count/desc_gate_lane_number/desc_inout_time/desc_user_count/
    // desc_total_count/desc_master_in_total/desc_motor_count를 VARCHAR/INT에서
    // TINYINT/INT/BIGINT(unsigned 계열)로 정합화(hibernate.ddl-auto=validate가 매핑 불일치 시
    // 기동을 막으므로 엔티티도 함께 수정).
    //
    // 2026-09-07: 위 정합화가 타입 "종류"만 맞추고 폭은 안 맞았던 결함 발견(작업일지 0113) —
    // Kotlin Int/Long은 Hibernate 기본 매핑상 각각 SQL INTEGER/BIGINT로 스키마 검증되는데, 실제
    // 컬럼은 그보다 좁은 TINYINT/INT(unsigned)라 hibernate.ddl-auto=validate가 계속 기동을
    // 막았다. 처음엔 @JdbcTypeCode로 검증용 JDBC 타입 자체를 TINYINT/INTEGER로 좁혔으나, 이는
    // 바인딩까지 좁은 타입(getByte/getInt)으로 바꿔버려 실제 unsigned 상한(255 / 42억)에 가까운
    // 값에서 오버플로가 나는 새 결함이었다(Codex 리뷰 P1/P2 지적, 작업일지 0113 4차 참고). DB
    // 컬럼(BASELINE 자동 ALTER 금지 원칙 대상)은 그대로 두고, `columnDefinition`으로 검증 시
    // 비교할 문자열만 실제 컬럼과 일치시킨다 — 바인딩/추출은 Java 타입(Int/Long)이 그대로
    // 결정하므로(getInt/getLong) 값 범위 계산 로직에 영향이 없다.
    @Column(name = "desc_data_info_length", nullable = false, columnDefinition = "TINYINT UNSIGNED")
    var descDataInfoLength: Int = 0,

    @Column(name = "desc_gate_name", nullable = false, length = 80)
    var descGateName: String = "",

    @Column(name = "desc_gate_ip", nullable = false, length = 20)
    var descGateIp: String = "",

    @Column(name = "desc_gate_lane_count", nullable = false, columnDefinition = "TINYINT UNSIGNED")
    var descGateLaneCount: Int = 0,

    @Column(name = "desc_gate_lane_number", nullable = false, columnDefinition = "TINYINT UNSIGNED")
    var descGateLaneNumber: Int = 0,

    @Column(name = "desc_gate_type", nullable = false, length = 50)
    var descGateType: String = "",

    @Column(name = "desc_user_mode", nullable = false, length = 30)
    var descUserMode: String = "",

    /**
     * `desc_user_mode`(표시 문자열)의 원본 코드값(예: "1") — [dtlTypeCd]와 같은 경위(레거시
     * `usp_process_analysis`가 함께 채우던 컬럼인데 이 저장소 엔티티에 매핑이 누락돼 있었다).
     * 실 DB 조회(2026-08-26, `192.168.0.26:28031/securance_gate`)로 secuhub가 저장한 행만
     * `user_mode_cd`/`security_mode_cd`가 NULL인 것을 확인했다.
     */
    @Column(name = "user_mode_cd", length = 10)
    var userModeCd: String? = null,

    @Column(name = "desc_security_mode", nullable = false, length = 20)
    var descSecurityMode: String = "",

    /** `desc_security_mode`의 원본 코드값 — [userModeCd]와 동일한 이유로 추가. */
    @Column(name = "security_mode_cd", length = 10)
    var securityModeCd: String? = null,

    @Column(name = "desc_inout_time", nullable = false, columnDefinition = "INT UNSIGNED")
    var descInoutTime: Long = 0,

    @Column(name = "desc_user_count", nullable = false)
    var descUserCount: Long = 0,

    @Column(name = "desc_total_count", nullable = false)
    var descTotalCount: Long = 0,

    // ── 센서 장애 12채널(S00~S11) ────────────────────────────────────
    @Column(name = "desc_operation01", nullable = false, length = 20)
    var descOperation01: String = "",

    @Column(name = "desc_operation02", nullable = false, length = 20)
    var descOperation02: String = "",

    @Column(name = "desc_operation03", nullable = false, length = 20)
    var descOperation03: String = "",

    @Column(name = "desc_operation04", nullable = false, length = 20)
    var descOperation04: String = "",

    @Column(name = "desc_safety01", nullable = false, length = 20)
    var descSafety01: String = "",

    @Column(name = "desc_safety02", nullable = false, length = 20)
    var descSafety02: String = "",

    @Column(name = "desc_safety03", nullable = false, length = 20)
    var descSafety03: String = "",

    @Column(name = "desc_safety04", nullable = false, length = 20)
    var descSafety04: String = "",

    @Column(name = "desc_operation05", nullable = false, length = 20)
    var descOperation05: String = "",

    @Column(name = "desc_operation06", nullable = false, length = 20)
    var descOperation06: String = "",

    @Column(name = "desc_operation07", nullable = false, length = 20)
    var descOperation07: String = "",

    @Column(name = "desc_operation08", nullable = false, length = 20)
    var descOperation08: String = "",

    // unsigned 32비트 카운터(최대 4,294,967,295)라 Int로는 범위를 다 담지 못한다(Codex 리뷰 P2,
    // 2026-09-04) — Long으로 매핑한다. DB 컬럼 자체는 dev DB 실측대로 int(10) unsigned 그대로 둔다.
    // columnDefinition으로 검증 문자열만 맞추고 바인딩은 Long(getLong/setLong)을 그대로 써서
    // 42억대 값에서도 오버플로가 나지 않게 한다(작업일지 0113 4차, Codex 리뷰 P1 반영).
    @Column(name = "desc_motor_count", nullable = false, columnDefinition = "INT UNSIGNED")
    var descMotorCount: Long = 0,

    @Column(name = "desc_master_in_total", nullable = false)
    var descMasterInTotal: Long = 0,

    // ── 게이트 동작 상태 12항목 ──────────────────────────────────────
    /** 역방향 진입(BACK RUSH). */
    @Column(name = "desc_gate_status01", nullable = false, length = 50)
    var descGateStatus01: String = "",

    /** 뒤따름(TAIL GATING). */
    @Column(name = "desc_gate_status02", nullable = false, length = 50)
    var descGateStatus02: String = "",

    /** 진입 후 장시간 대기(WAITING). */
    @Column(name = "desc_gate_status03", nullable = false, length = 50)
    var descGateStatus03: String = "",

    /** 인증 진입 후 되돌아나옴(ANTI-PASS). */
    @Column(name = "desc_gate_status04", nullable = false, length = 50)
    var descGateStatus04: String = "",

    /** 일반 개폐 — 레거시 통합 INSERT에서 주석 처리되어 항상 공백이다. */
    @Column(name = "desc_gate_status05", nullable = false, length = 50)
    var descGateStatus05: String = "",

    /** 리모컨 개폐. */
    @Column(name = "desc_gate_status06", nullable = false, length = 50)
    var descGateStatus06: String = "",

    /** 화재 경보(FIRE ALARM). */
    @Column(name = "desc_gate_status07", nullable = false, length = 50)
    var descGateStatus07: String = "",

    /** AC 전원 리셋. */
    @Column(name = "desc_gate_status08", nullable = false, length = 50)
    var descGateStatus08: String = "",

    /** 서브보드 통신 오류. */
    @Column(name = "desc_gate_status09", nullable = false, length = 50)
    var descGateStatus09: String = "",

    /** 메인 모터 장애. */
    @Column(name = "desc_gate_status10", nullable = false, length = 50)
    var descGateStatus10: String = "",

    /** 서브 모터 장애. */
    @Column(name = "desc_gate_status11", nullable = false, length = 50)
    var descGateStatus11: String = "",

    /** 비상(EMERGENCY). */
    @Column(name = "desc_gate_status12", nullable = false, length = 50)
    var descGateStatus12: String = "",

    /**
     * ERROR CHECK 값 — 3이면 장애, 1 Active, 2 Inactive, 9 Event.
     *
     * [dtlType]과 동일한 이유(코드 리뷰 지적, 2026-08-14)로 `tb_data_rcv_anal.err_type`도
     * V8부터 `NULL` 허용이라 nullable로 맞춘다.
     */
    @Column(name = "err_type")
    var errType: Int? = 0,

    @Column(name = "resolve_yn", nullable = false, length = 1)
    var resolveYn: String = "N",

    /** 오류를 resolve 처리한 사용자(또는 시스템 주체). 스키마에는 있었으나 엔티티 매핑이 누락되어 있었다. */
    @Column(name = "resolve_user", length = 50)
    var resolveUser: String? = null,

    /** resolve 처리 시각. `SendControlJob`의 리셋 명령 전송 성공 시 `now()`로 채워진다. */
    @Column(name = "resolve_date")
    var resolveDate: LocalDateTime? = null,

    @Column(name = "has_status_event", insertable = false, updatable = false)
    val hasStatusEvent: Boolean = false,

    @Column(name = "has_error_event", insertable = false, updatable = false)
    val hasErrorEvent: Boolean = false,

    @Column(name = "reg_date", insertable = false, updatable = false)
    val regDate: LocalDateTime? = null,
) {
    /** 화면 표시용 별칭 — 기존 코드가 쓰던 이름을 유지한다. */
    val descFireAlarm: String get() = descGateStatus07
    val descMainMotorError: String get() = descGateStatus10
    val descSlaveMotorError: String get() = descGateStatus11
}
