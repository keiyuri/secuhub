package kr.co.securance.secuhub.server.control

import org.springframework.boot.context.properties.ConfigurationProperties

/** 프론트엔드 제어 명령 전송 방식(계획서 5.5절). 연결 방향(SERVER/CLIENT)과는 독립된 축이다. */
enum class DispatchMode {
    /**
     * 커넥션 액터 체인에 즉시 태워 보낸다. 응답성이 좋지만 애플리케이션 인스턴스가 죽으면
     * 전송 중이던 명령이 사라진다.
     */
    DIRECT,

    /**
     * `tb_data_snd`에 INSERT만 하고 `SendControlJob`이 폴링해 전송한다.
     * 명령이 DB에 남으므로 재기동/장비 재접속 후에도 이어서 전송되고, ACK 미수신 시 재전송된다.
     */
    QUEUED,
}

/**
 * `securance.control.*` 설정(계획서 5.5절).
 *
 * 타임아웃/재시도 값의 기본치는 레거시 `ClsQuartzJobSendControl`의 상수를 따랐다 —
 * `RESEND_GUARD`(5초), `SEND_WAIT_TIMEOUT`(5초), `INTERVAL_SEND_CONTROL`(1초).
 */
@ConfigurationProperties(prefix = "securance.control")
data class ControlProperties(
    /** DIRECT | QUEUED. */
    val dispatchMode: DispatchMode = DispatchMode.QUEUED,

    /** `SendControlJob` 폴링 주기(초) — 레거시 `INTERVAL_SEND_CONTROL`. */
    val pollIntervalSeconds: Long = 1,

    /**
     * 폴링 1회에 처리할 최대 명령 수. 장비 장기 단절로 대기열이 폭증했을 때 한 사이클이
     * 무한정 길어지는 것을 막는다(레거시에는 상한이 없었다).
     */
    val batchSize: Int = 200,

    /**
     * 같은 `snd_id`를 다시 물리 전송하기까지의 최소 간격(초) — 레거시 `RESEND_GUARD`.
     * DB 갱신이 지연/실패하는 동안 매 폴링마다 같은 명령이 반복 전송되어 게이트가 여러 번
     * 동작하는 사고를 막는다.
     */
    val resendGuardSeconds: Long = 5,

    /**
     * 전송 후 장비 ACK를 기다리는 시간(초). 초과하면 재전송하거나(재시도 여유가 있으면)
     * 실패로 확정한다. 레거시에는 이 개념 자체가 없어 "전송했으나 장비가 무시한" 명령이
     * 영원히 성공으로 남았다.
     */
    val ackTimeoutSeconds: Long = 10,

    /** ACK 미수신 시 최대 전송 시도 횟수(최초 전송 포함). */
    val maxSendAttempts: Int = 3,
)
