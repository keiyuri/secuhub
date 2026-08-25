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
 * 레거시 `tb_data_snd.snd_data_tp` 문자열을 채운다(`RESET_MOTOR` 등).
 * 신규 코드는 이 값을 파싱하지 않지만, 레거시 리포트/화면이 이 컬럼을 읽으므로 값을 남긴다.
 * DIRECT/QUEUED 두 [GateControlService] 구현이 동일하게 채워야 하는 값이라 여기 공유해 둔다.
 */
fun legacyDataTypeOf(request: GateControlRequest): String {
    val category = GateFaultCategory.of(request.command) ?: return "CONTROL"
    return when (category) {
        GateFaultCategory.ALL -> "RESET_GATE"
        GateFaultCategory.SENSOR -> "RESET_OPER"
        GateFaultCategory.MOTOR -> "RESET_MOTOR"
        GateFaultCategory.FIRE -> "RESET_FIRE"
    }
}

/**
 * `tb_data_snd.snd_server` 기본값 — 다중 인스턴스 배포 시 어느 서버가 보냈는지 구분하는 용도.
 * [DirectGateControlService](즉시 전송)와 [GateControlDispatcher](QUEUED 선점 시)가 동일한 값을
 * 써야 인스턴스 추적이 일관되므로 여기 하나로 공유한다.
 *
 * **IPv4 전용 배포 가정(2026-08-25, Codex 적대적 리뷰 지적)**: `snd_server`는 `VARCHAR(20)`이라
 * `InetAddress.getLocalHost().hostAddress`가 IPv6 주소(최대 39자, scope ID 포함 시 더 길어짐)를
 * 반환하면 INSERT가 데이터 길이 초과로 실패한다. 이 프로젝트는 게이트 장비와의 통신 자체가
 * [kr.co.securance.secuhub.common.util.ServerIpDetector]부터 이미 IPv4(`Inet4Address`)만
 * 다루도록 설계돼 있고 운영 환경도 사설 IPv4 대역만 쓰므로, IPv6 호스트 주소가 반환될 가능성은
 * 배포 환경 범위 밖으로 보고 별도 처리를 추가하지 않는다 — 이 서버가 IPv6 전용/우선 호스트에
 * 배치되면 안 된다는 전제가 깨지지 않는 한 안전하다.
 */
val localServerId: String by lazy {
    runCatching { java.net.InetAddress.getLocalHost().hostAddress }.getOrDefault("unknown")
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
