package kr.co.securance.secuhub.domain.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Lob
import jakarta.persistence.Table

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
