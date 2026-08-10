package kr.co.securance.secuhub.domain.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Lob
import jakarta.persistence.Table
import jakarta.persistence.Version

/**
 * `tb_data_snd` — 제어 명령 발송 큐. 5.5절 QUEUED 경로에서 화면/API가 이 테이블에 INSERT하면
 * `securance-scheduler`의 `SendControlJob`이 폴링해 게이트로 전송한다.
 *
 * ## 상태 전이(2차 스프린트에서 정의)
 * | snd_yn | chk_yn | 의미 |
 * |--------|--------|------|
 * | N | N | 전송 대기(폴링 대상) |
 * | Y | N | 장비로 전송했고 ACK 대기 중 |
 * | Y | Y | 장비 ACK 확인 완료 |
 * | Y | F | ACK 미수신으로 재시도 한도 초과 — 실패 확정 |
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

    @Column(name = "dtl_ip", nullable = false, length = 20)
    var dtlIp: String,

    @Column(name = "dtl_lane_no", nullable = false)
    var dtlLaneNo: Int,

    @Column(name = "dtl_type", nullable = false)
    var dtlType: Int = 1,

    @Column(name = "dtl_id", nullable = false)
    var dtlId: Long = 0,

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
