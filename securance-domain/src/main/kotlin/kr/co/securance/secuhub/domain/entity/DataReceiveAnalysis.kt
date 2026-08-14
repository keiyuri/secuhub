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

    // ── 레거시 `anal_data_*` 33개 컬럼(2026-08-14 코드 리뷰 지적 대응) ──────────────────
    //
    // 이 컬럼들은 이 리포지토리의 Flyway 마이그레이션이 만든 적이 없다 — 애플리케이션이 실제로
    // 연결하는 운영 DB(application.yml의 하드코딩된 URL, `baseline-on-migrate: true` +
    // 기본 baselineVersion=1 설정)는 Flyway 도입 이전부터 이미 이 컬럼들을 갖고 있던 레거시
    // 스키마이며, V23__default_legacy_anal_data_columns.sql이 그 경위를 기록한다: 레거시
    // GateControl(SR_Speed_Server, C#/.NET)의 저장 프로시저 `usp_process_analysis`가 게이트
    // 패킷을 받을 때마다 이 33개 컬럼 전부에 실시간으로 값을 채워 넣고, `anal_data_object_code`는
    // GateControl 대시보드가 지금도 `obj_cd` 대신 조회하는 컬럼이다.
    //
    // secuhub(Kotlin) 엔티티가 이 컬럼들을 매핑하지 않고 있던 탓에, JPA가 만드는 INSERT 문에는
    // 이 컬럼들이 아예 빠졌고 항상 DEFAULT ''(V23 이후)로만 남아 있었다 — GateControl이 채운 값과
    // secuhub가 채운 값이 뒤섞여, secuhub가 저장한 행만 이 컬럼들이 비어 있는 상태였다.
    //
    // 값 자체는 [SpeedGateProtocolConstants.HeaderOffset] 필드(패킷 헤더 27바이트)를 1:1로
    // 옮긴 것이다 — `usp_process_analysis`의 정확한 파싱 로직(원본 프로시저 SQL)은 이 저장소에
    // 없으므로, 이미 이 코드베이스가 신뢰하는 유일한 소스(SpeedGate 프로토콜 헤더 스펙)로 채울 수
    // 있는 필드만 채운다. `anal_data_gate_name`/`anal_data_ip`는 패킷 바이트가 아니라
    // `desc_gate_name`/`desc_gate_ip`와 동일한 소스(연결 IP/DB 조회 게이트명)로 채운다 — 패킷
    // DataInfo(45바이트) 안에서 이 두 필드가 정확히 어느 오프셋인지는 프로토콜 문서에 없다.
    // `anal_data_mac`은 이 코드베이스 어디에도 게이트 MAC 주소를 추적하는 경로가 없어(연결은
    // IP로만 식별) 값을 채울 수 없으므로 DEFAULT ''로 남긴다 — 잘못된 추측값을 넣는 대신
    // GateControl이 채운 값이 그대로 보존되게(secuhub가 UPDATE가 아니라 별도 행을 INSERT하므로
    // 실질적 영향은 없지만) 빈 문자열을 명시한다.

    /** 헤더 STX(고정 `0x02`) — [SpeedGateProtocolConstants.HeaderOffset.STX]. */
    @Column(name = "anal_data_stx", nullable = false, length = 10)
    var analDataStx: String = "",

    /** 헤더 패킷 전체 길이(2바이트) — [SpeedGateProtocolConstants.HeaderOffset.PACKET_LENGTH]. */
    @Column(name = "anal_data_packet_len", nullable = false, length = 10)
    var analDataPacketLen: String = "",

    /** 헤더 프로토콜 버전(고정 `0x04`) — [SpeedGateProtocolConstants.HeaderOffset.PROTOCOL_VERSION]. */
    @Column(name = "anal_data_protocol_ver", nullable = false, length = 10)
    var analDataProtocolVer: String = "",

    /** 헤더 프레임 옵션(2바이트) — [SpeedGateProtocolConstants.HeaderOffset.FRAME_OPTION]. */
    @Column(name = "anal_data_frame_option", nullable = false, length = 10)
    var analDataFrameOption: String = "",

    /** 헤더 주소(13바이트) — [SpeedGateProtocolConstants.HeaderOffset.ADDRESS]. */
    @Column(name = "anal_data_address", nullable = false, length = 40)
    var analDataAddress: String = "",

    /** 헤더 CMD1(명령 대분류) — [SpeedGateProtocolConstants.HeaderOffset.COMMAND1]. */
    @Column(name = "anal_data_command", nullable = false, length = 10)
    var analDataCommand: String = "",

    /** 헤더 CMD2(명령 세부) — [SpeedGateProtocolConstants.HeaderOffset.COMMAND2]. */
    @Column(name = "anal_data_subcommand", nullable = false, length = 10)
    var analDataSubcommand: String = "",

    /** 헤더 오브젝트 코드 — [SpeedGateProtocolConstants.HeaderOffset.OBJECT_CODE]. `obj_cd`와 동일 바이트. */
    @Column(name = "anal_data_object_code", nullable = false, length = 10)
    var analDataObjectCode: String = "",

    /** 헤더 DataInfo 길이(1바이트) — [SpeedGateProtocolConstants.HeaderOffset.DATA_INFO_LENGTH]. */
    @Column(name = "anal_data_info_length", nullable = false, length = 10)
    var analDataInfoLength: String = "",

    /** 헤더 Data Count(2바이트) — [SpeedGateProtocolConstants.HeaderOffset.DATA_COUNT]. */
    @Column(name = "anal_data_count", nullable = false, length = 10)
    var analDataCount: String = "",

    /** 헤더 Data Length(2바이트) — [SpeedGateProtocolConstants.HeaderOffset.DATA_LENGTH]. */
    @Column(name = "anal_data_length", nullable = false, length = 10)
    var analDataLength: String = "",

    /** 게이트 이름 — `desc_gate_name`과 동일 소스(DB 조회, 패킷 바이트 아님). */
    @Column(name = "anal_data_gate_name", nullable = false, length = 80)
    var analDataGateName: String = "",

    /** 게이트 IP — `desc_gate_ip`와 동일 소스(연결 IP, 패킷 바이트 아님). */
    @Column(name = "anal_data_ip", nullable = false, length = 20)
    var analDataIp: String = "",

    /** 게이트 MAC 주소 — 이 코드베이스는 MAC을 추적하지 않아 채우지 않는다(위 KDoc 참고). */
    @Column(name = "anal_data_mac", nullable = false, length = 20)
    var analDataMac: String = "",

    // ── 헤더/정보 영역 해석값 ────────────────────────────────────────
    @Column(name = "desc_data_info_length", nullable = false, length = 10)
    var descDataInfoLength: String = "",

    @Column(name = "desc_gate_name", nullable = false, length = 80)
    var descGateName: String = "",

    @Column(name = "desc_gate_ip", nullable = false, length = 20)
    var descGateIp: String = "",

    @Column(name = "desc_gate_lane_count", nullable = false, length = 20)
    var descGateLaneCount: String = "",

    @Column(name = "desc_gate_lane_number", nullable = false, length = 20)
    var descGateLaneNumber: String = "",

    @Column(name = "desc_gate_type", nullable = false, length = 50)
    var descGateType: String = "",

    @Column(name = "desc_user_mode", nullable = false, length = 30)
    var descUserMode: String = "",

    @Column(name = "desc_security_mode", nullable = false, length = 20)
    var descSecurityMode: String = "",

    @Column(name = "desc_inout_time", nullable = false, length = 20)
    var descInoutTime: String = "",

    @Column(name = "desc_user_count", nullable = false, length = 20)
    var descUserCount: String = "",

    @Column(name = "desc_total_count", nullable = false, length = 20)
    var descTotalCount: String = "",

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

    @Column(name = "desc_motor_count", nullable = false)
    var descMotorCount: Int = 0,

    @Column(name = "desc_master_in_total", nullable = false)
    var descMasterInTotal: Int = 0,

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
