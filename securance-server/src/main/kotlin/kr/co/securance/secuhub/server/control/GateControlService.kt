package kr.co.securance.secuhub.server.control

import kr.co.securance.secuhub.common.util.ServerIpDetector
import kr.co.securance.secuhub.protocol.SpeedGateControlCommand
import kr.co.securance.secuhub.protocol.SpeedGateControlPayload
import kr.co.securance.secuhub.protocol.SpeedGateSecurityMode
import org.slf4j.LoggerFactory
import java.net.Inet4Address
import java.net.InetAddress

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

/** `tb_data_snd.snd_server`(`VARCHAR(20)`) 컬럼 길이 — [localServerId]가 이 길이를 절대 넘지 않도록 강제한다. */
private const val MAX_SND_SERVER_LENGTH = 20

private val localServerIdLogger = LoggerFactory.getLogger("kr.co.securance.secuhub.server.control.LocalServerId")

/**
 * `tb_data_snd.snd_server` 기본값 — 다중 인스턴스 배포 시 어느 서버가 보냈는지 구분하는 용도.
 * [DirectGateControlService](즉시 전송)와 [GateControlDispatcher](QUEUED 선점 시)가 동일한 값을
 * 써야 인스턴스 추적이 일관되므로 여기 하나로 공유한다.
 *
 * **2026-08-25 Codex 적대적 리뷰 지적 반영**: 애초에 `InetAddress.getLocalHost().hostAddress`를
 * 그대로 썼는데, `snd_server`는 `VARCHAR(20)`이라 IPv6 주소(최대 39자)가 반환되면 INSERT가 길이
 * 초과로 실패해 QUEUED는 명령이 전송되지 않고 DIRECT는 감사 이력이 유실될 수 있었다. "이 배포는
 * IPv4만 쓴다"는 문서화만으로는 OS/컨테이너의 호스트명 해석 설정(`getLocalHost()`가 IPv6를
 * 고르는 것)까지 코드가 막지 못한다는 지적을 받아, 다음 두 단계로 실제로 강제한다.
 *
 * 1. [ServerIpDetector.detectServerIp]로 NetworkInterface를 직접 열거해 `Inet4Address`만 골라
 *    IPv6가 애초에 후보에 오르지 않게 한다. 실패하면 `getLocalHost()`의 반환값이 `Inet4Address`인
 *    경우에만 채택하고, 그마저 안 되면 `hostAddress`를 그대로 쓴다(둘 다 IPv6일 수 있음).
 * 2. 위 결과가 여전히 20자를 넘으면(IPv6 폴백 등) [MAX_SND_SERVER_LENGTH]로 잘라 INSERT 실패
 *    자체를 코드 레벨에서 막는다 — 잘린 값은 인스턴스 식별용으로만 쓰이므로(파싱 대상 아님)
 *    일부 유실보다 명령 전송/이력 저장이 막히는 쪽이 훨씬 크다. 잘렸다는 사실은 경고 로그로 남겨
 *    운영 환경이 실제로 IPv4 전용이 아님을 알아챌 수 있게 한다.
 */
val localServerId: String by lazy { sanitizeSndServer(detectRawServerId()) }

private fun detectRawServerId(): String =
    ServerIpDetector.detectServerIp()
        ?: (runCatching { InetAddress.getLocalHost() }.getOrNull() as? Inet4Address)?.hostAddress
        ?: runCatching { InetAddress.getLocalHost().hostAddress }.getOrNull()
        ?: "unknown"

/**
 * `tb_data_snd.snd_server` 컬럼 길이([MAX_SND_SERVER_LENGTH]) 초과를 코드 레벨에서 강제로 막는다
 * — [localServerId]의 순수 함수 부분만 분리해 네트워크 상태와 무관하게 테스트할 수 있게 했다.
 */
internal fun sanitizeSndServer(candidate: String): String {
    if (candidate.length > MAX_SND_SERVER_LENGTH) {
        localServerIdLogger.warn(
            "localServerId '{}'가 tb_data_snd.snd_server 길이({}자)를 초과해 잘라서 사용합니다 — " +
                "IPv4 전용 배포 가정이 깨진 환경일 수 있습니다.",
            candidate,
            MAX_SND_SERVER_LENGTH,
        )
    }
    return candidate.take(MAX_SND_SERVER_LENGTH)
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
