package kr.co.securance.secuhub.server.control

import kr.co.securance.secuhub.protocol.SpeedGateControlCommand
import kr.co.securance.secuhub.protocol.SpeedGateControlPayload
import kr.co.securance.secuhub.protocol.SpeedGateSecurityMode

/** 제어 명령 1건의 요청 정보. */
data class GateControlRequest(
    val dtlIp: String,
    val dtlLaneNo: Int,
    val command: SpeedGateControlCommand,
    /** 보안 등급. null이면 장비의 현재 설정을 유지한다. */
    val securityMode: SpeedGateSecurityMode? = null,
    /** 사용자 통행 시간 3바이트(스케줄 화면). null이면 변경하지 않는다. */
    val userTime: ByteArray? = null,
    /** 보안 시간 3바이트(스케줄 화면). null이면 변경하지 않는다. */
    val securityTime: ByteArray? = null,
    /** 요청한 사용자 ID(`tb_users.user_id`) — 감사 추적용. 시스템 발행이면 null. */
    val requestedBy: String? = null,
) {
    /** 코덱에 넘길 인코딩 파라미터 묶음. */
    val payload: SpeedGateControlPayload
        get() = SpeedGateControlPayload(command, securityMode, userTime, securityTime)

    override fun equals(other: Any?): Boolean =
        other is GateControlRequest &&
            dtlIp == other.dtlIp &&
            dtlLaneNo == other.dtlLaneNo &&
            payload == other.payload &&
            requestedBy == other.requestedBy

    override fun hashCode(): Int {
        var result = dtlIp.hashCode()
        result = 31 * result + dtlLaneNo
        result = 31 * result + payload.hashCode()
        result = 31 * result + (requestedBy?.hashCode() ?: 0)
        return result
    }
}

/** 제어 명령 처리 결과. 호출자가 "전송 시도조차 되지 않음"과 "전송 실패"를 구분할 수 있어야 한다. */
enum class GateControlResult {
    /** (DIRECT) 커넥션 액터 체인에 전송이 등록되었다. */
    SENT,

    /** (QUEUED) `tb_data_snd`에 접수되었다. 실제 전송은 `SendControlJob`이 수행한다. */
    QUEUED,

    /** 해당 IP의 커넥션이 없다(장비 미접속). */
    NOT_CONNECTED,

    /** 커넥션은 있으나 해당 레인을 이 소켓이 담당하지 않거나, 처리 대기열이 가득 찼다. */
    REJECTED,
}

/**
 * 게이트 제어 명령 발행 진입점(계획서 5.5절).
 *
 * 전송 방식(`securance.control.dispatch-mode`)에 따라 구현이 갈린다.
 * - **DIRECT**: [DirectGateControlService] — 커넥션 액터 체인에 즉시 태워 보낸다.
 * - **QUEUED**: [QueuedGateControlService] — `tb_data_snd`에 INSERT만 하고
 *   [GateControlDispatcher]가 폴링해 전송·ACK 확인·재전송까지 책임진다.
 *
 * 이 인터페이스는 연결 방향(SERVER/CLIENT)과 무관하다 — 어느 쪽이 소켓을 열었는지는
 * [kr.co.securance.secuhub.server.connection.GateConnectionRegistry]가 감춘다.
 */
interface GateControlService {

    /** 제어 명령 1건을 발행한다. 이력은 구현체가 `tb_data_snd`에 남긴다. */
    fun send(request: GateControlRequest): GateControlResult
}
