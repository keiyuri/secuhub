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
 * `tb_data_snd` — 제어 명령 발송 큐. 5.5절 QUEUED 경로에서 프론트엔드가 이 테이블에 INSERT하면
 * `securance-scheduler`의 `SendControlJob`이 폴링해 게이트로 전송한다.
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

    /** 서버가 전송을 확인했는지 여부(Y/N). */
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

    @Column(name = "snd_user", length = 20)
    var sndUser: String? = null,

    /**
     * 레거시 `snd_data_tp`(예: "RESET_MOTOR")에 대응하는 필드 — `_` 구분 서브타입 규칙까지는
     * 이 엔티티에서 강제하지 않고 호출부(`SendControlJob`)가 필요 시 파싱한다.
     * 스키마(`V1__init_schema.sql`)에는 있었으나 엔티티 매핑이 누락되어 있었다.
     */
    @Column(name = "snd_type_cd", length = 20)
    var sndTypeCd: String? = null,

    @Lob
    @Column(name = "snd_raw")
    var sndRaw: String? = null,
) {
    val isPending: Boolean
        get() = sndYn == "N" && chkYn == "N"
}
