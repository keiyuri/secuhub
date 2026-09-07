package kr.co.securance.secuhub.domain.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Lob
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.LocalDateTime

/**
 * `tb_data_snd` — 제어 명령 발송 큐. 5.5절 QUEUED 경로에서 화면/API가 이 테이블에 INSERT하면
 * `securance-scheduler`의 `SendControlJob`이 폴링해 게이트로 전송한다.
 *
 * ## 상태 전이(2차 스프린트에서 정의, N/F는 코드 리뷰 R-1 대응으로 4차 스프린트에서 추가)
 * | snd_yn | chk_yn | 의미 |
 * |--------|--------|------|
 * | N | N | 전송 대기(폴링 대상) |
 * | Y | N | 장비로 전송했고 ACK 대기 중 |
 * | Y | Y | 장비 ACK 확인 완료 |
 * | Y | F | ACK 미수신으로 재시도 한도 초과 — 실패 확정 |
 * | N | F | 전송조차 되지 못한 채 유효기간(`pendingExpirySeconds`)이 지나 실패 확정 |
 *
 * N/F는 [kr.co.securance.secuhub.domain.repository.DataSendRepository.expireStalePending]이
 * 만든다 — 장비 미접속/삭제된 대상 등으로 영원히 전송되지 않는 대기 행이 `findPendingCommands`의
 * `LIMIT` 창을 영구히 점유해 그 뒤에 발행된 정상 명령이 조회 자체가 되지 않는 헤드 오브 라인
 * 차단을 막기 위함이다.
 *
 * 레거시는 `chk_yn`을 "서버가 전송을 확인했는지"라는 모호한 의미로만 쓰고 장비 ACK와 연결하지
 * 않아, 전송만 되고 장비가 무시한 명령을 성공으로 기록했다. 여기서는 `chk_yn`을 **장비 ACK
 * 확인 플래그**로 명확히 정의한다.
 *
 * 스키마의 `snd_user`/`snd_server`/`snd_raw`/`snd_data` 등은 `NOT NULL`이면서 DEFAULT가 없으므로
 * 엔티티 기본값을 빈 문자열로 두어 INSERT 시 제약 위반이 나지 않게 한다.
 *
 * ## 다중 인스턴스 동시성 (3차 스프린트)
 * `securance-app`을 2대 이상 띄우면(스케일아웃) 이 테이블은 모든 인스턴스가 공유하는 유일한
 * 상태다. [kr.co.securance.secuhub.server.control.GateControlDispatcher]는 대상 게이트의 TCP
 * 커넥션을 로컬로 들고 있는 인스턴스만 실제로 전송하도록 이미 자체 필터링하지만(다른 인스턴스가
 * 소유한 커넥션이면 `findConnection`이 null), 장비 재접속으로 커넥션 소유권이 인스턴스 간에
 * 넘어가는 짧은 틈에는 두 인스턴스가 동시에 같은 행을 갱신하려 할 수 있다. [version]은 그 경합을
 * 조용한 덮어쓰기 대신 명시적인 낙관적 잠금 예외로 바꿔, 패배한 쪽이 다음 폴링 주기에 안전하게
 * 재시도하게 한다.
 */
@Entity
@Table(name = "tb_data_snd")
class DataSend(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "snd_id")
    val sndId: Long? = null,

    @Column(name = "snd_date", nullable = false, length = 20)
    var sndDate: String,

    /** 게이트로 전송 완료 여부(Y/N). */
    @Column(name = "snd_yn", nullable = false, length = 1)
    var sndYn: String = "N",

    /** 장비 ACK 확인 여부(Y/N/F). F는 재시도 한도 초과로 실패 확정. */
    @Column(name = "chk_yn", nullable = false, length = 1)
    var chkYn: String = "N",

    /**
     * [Codex 적대적 리뷰 지적] 전송 실패 시 다음 재시도 가능 시각. null이면 즉시 재시도 대상이다.
     * SendControlJob이 이 값을 조회 조건에 반영해, 계속 실패하는 큐 앞쪽 행이 뒤쪽 정상 행을
     * 영구히 가리는 헤드 오브 라인 차단을 막는다.
     */
    @Column(name = "next_attempt_at")
    var nextAttemptAt: LocalDateTime? = null,

    @Column(name = "dtl_ip", nullable = false, length = 20)
    var dtlIp: String,

    @Column(name = "dtl_lane_no", nullable = false)
    var dtlLaneNo: Int,

    @Column(name = "dtl_type", nullable = false)
    var dtlType: Int = 1,

    /**
     * 코드 리뷰 지적(2026-08-14): `tb_data_snd.dtl_id`는 V1부터 `BIGINT UNSIGNED NULL`로
     * 생성된 뒤 이후 마이그레이션에서도 NOT NULL로 바뀐 적이 없다(V8이 `loc_id`/`grp_id`는
     * 명시적으로 NOT NULL로 정합화했지만 `dtl_id`는 대상에서 빠졌다) — 엔티티만 `nullable=false`로
     * 앞서 있었다. 현재 앱 코드는 항상 기본값 0으로 쓰기 때문에 실질적 위험은 낮지만, 과거
     * 데이터를 조회할 가능성을 배제할 수 없어 스키마에 맞춰 nullable로 정정한다.
     */
    @Column(name = "dtl_id")
    var dtlId: Long? = 0,

    @Column(name = "loc_id", nullable = false)
    var locId: Long = 0,

    @Column(name = "grp_id", nullable = false)
    var grpId: Long = 0,

    @Column(name = "snd_user", nullable = false, length = 20)
    var sndUser: String = "",

    /** 전송을 수행한 서버 IP(다중 인스턴스 운영 시 추적용). */
    @Column(name = "snd_server", nullable = false, length = 20)
    var sndServer: String = "",

    /**
     * 컬럼 누락 수정(2026-09-08, 사용자 요청 — 운영 DB 실측): `snd_server_cd`/`reg_user`/`mod_user`
     * 세 컬럼은 이 저장소의 V1 마이그레이션(BASELINE)이 기록을 놓쳐 엔티티에 매핑돼 있지 않았다.
     * 그 결과 레거시 화면이 쓰던 행은 이 세 컬럼이 채워져 있는 반면, 이 앱이 새로 쓰는 행은 항상
     * 비어(NULL/기본값) 있었다(운영 DB 2026-09-08 실측 — 2026-08-19 이후 이 앱이 적재한 행만
     * 세 컬럼이 비어 있고, 그 이전 레거시 행은 `snd_server_cd='SERVER'`, `reg_user`가 채워져
     * 있었다). [V4__fix_tb_data_snd_tb_opr_status_missing_columns.sql] 참고.
     *
     * `server_cd`는 [kr.co.securance.secuhub.server.connection.GateConnectionRegistryImpl]이
     * `tb_net_state.server_cd`에 쓰는 것과 같은 개념(연결 방향 코드)이지만, 운영 DB에서 실측된
     * 값이 전부 `'SERVER'` 고정이라 지금은 상수로 채운다(V3 마이그레이션이 `tb_net_state.server_cd`에
     * `'SERVER'`를 고정한 것과 동일한 근거 — 이 값을 CLIENT 모드에서 채우는 경로가 없다).
     */
    @Column(name = "snd_server_cd", nullable = false, length = 20)
    var sndServerCd: String = "",

    /**
     * 명령을 발행(등록)한 사용자 — 운영 DB 실측 결과 값이 [sndUser]와 항상 같다(레거시가 등록 시점
     * 요청자를 두 컬럼에 동시에 기록). 컬럼 자체는 nullable이라 과거 데이터 조회 호환을 위해
     * `String?`으로 둔다.
     */
    @Column(name = "reg_user", length = 20)
    var regUser: String? = null,

    /**
     * 장비 ACK 확인(`chk_yn='Y'`) 시점에 그 확인을 처리한 서버를 기록 — 운영 DB 실측 결과
     * [sndServer]와 동일한 값이 이 시점에만 채워진다(대기/실패 확정 상태에서는 항상 비어 있었다).
     * [kr.co.securance.secuhub.server.control.GateControlDispatcher.confirmAckedCommands]가 확인
     * 처리와 동시에 채운다.
     */
    @Column(name = "mod_user", length = 20)
    var modUser: String? = null,

    /**
     * 제어 명령 종류 코드 — [kr.co.securance.secuhub.common.gate.GateTypeCodes]와 무관하며,
     * `SpeedGateControlCommand.legacyCode`(레거시 `sControlType` 2자 코드)를 저장한다.
     */
    @Column(name = "snd_type_cd", nullable = false, length = 20)
    var sndTypeCd: String = "",

    /**
     * 레거시 `snd_data_tp` — `RESET_MOTOR`/`RESET_OPER`/`RESET_GATE`처럼 `_`로 구분된 문자열이며
     * 전송 성공 후 어떤 장애를 해제할지 결정하는 데 쓰였다. 신규 코드는 이 문자열을 파싱하지 않고
     * [snd_type_cd]의 명령 열거형으로 분기하지만, 레거시 화면/리포트 호환을 위해 값은 채워 둔다.
     */
    @Column(name = "snd_data_tp", nullable = false, length = 20)
    var sndDataTp: String = "",

    @Lob
    @Column(name = "snd_raw", nullable = false, columnDefinition = "longtext")
    var sndRaw: String = "",

    @Column(name = "snd_header", nullable = false, length = 100)
    var sndHeader: String = "",

    @Lob
    @Column(name = "snd_data", nullable = false, columnDefinition = "longtext")
    var sndData: String = "",

    @Column(name = "snd_tail", nullable = false, length = 20)
    var sndTail: String = "",

    /** 낙관적 잠금 버전 — 다중 인스턴스 동시 갱신 감지용(클래스 KDoc 참고). */
    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0,
) {
    /** 아직 전송되지 않아 `SendControlJob`이 집어가야 하는 상태인지. */
    val isPending: Boolean
        get() = sndYn == "N" && chkYn == "N"

    /** 전송은 됐으나 장비 ACK를 아직 못 받은 상태인지. */
    val isAwaitingAck: Boolean
        get() = sndYn == "Y" && chkYn == "N"

    companion object {
        const val YES = "Y"
        const val NO = "N"

        /** ACK 재시도 한도 초과로 실패 확정된 명령(`chk_yn`). */
        const val FAILED = "F"
    }
}
