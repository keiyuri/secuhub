package kr.co.securance.secuhub.web.gate

import kr.co.securance.secuhub.protocol.SpeedGateControlCommand
import kr.co.securance.secuhub.protocol.SpeedGateSecurityMode
import kr.co.securance.secuhub.server.control.GateControlRequest
import kr.co.securance.secuhub.server.control.GateControlResult
import kr.co.securance.secuhub.server.control.GateControlService
import kr.co.securance.secuhub.server.control.GateFaultResolutionService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 제어 명령 처리 결과 응답 본문.
 *
 * @param resolvedFaults 이 요청으로 해제된 `tb_data_rcv_anal` 장애 건수(리셋 명령에서만 0 초과).
 */
data class GateControlResponse(
    val result: GateControlResult,
    val message: String,
    val resolvedFaults: Int = 0,
)

/**
 * 게이트 제어/리셋 엔드포인트(계획서 5.5절).
 *
 * 레거시 `SR_F_GateControl`(개방/폐쇄/모드 변경)과 `SR_F_GateReset`(시스템/센서/모터 리셋)의
 * 버튼 핸들러에 대응한다. 접근 권한은 별도 애너테이션 없이
 * [kr.co.securance.secuhub.web.security.SecurityConfig]의 URL 규칙
 * (`/api/gate-control` 하위 전체 → ROLE_CONTROL 또는 ROLE_ADMIN)으로 통제한다.
 */
@RestController
@RequestMapping("/api/gate-control")
class GateControlController(
    private val gateControlService: GateControlService,
    private val faultResolutionService: GateFaultResolutionService,
) {
    private val logger = LoggerFactory.getLogger(GateControlController::class.java)

    /** 운영 모드/도어 개폐 등 일반 제어 명령. */
    @PostMapping("/command")
    fun command(
        @RequestParam dtlIp: String,
        @RequestParam dtlLaneNo: Int,
        @RequestParam command: SpeedGateControlCommand,
        @RequestParam(required = false) securityMode: SpeedGateSecurityMode?,
        @AuthenticationPrincipal user: UserDetails?,
    ): ResponseEntity<GateControlResponse> = dispatch(dtlIp, dtlLaneNo, command, securityMode, user)

    /**
     * 리셋/장애 해제 명령 — 레거시 `SR_F_GateReset` + `UpdateResetFlag*` 계열.
     *
     * ### 장애 해제(`tb_data_rcv_anal.resolve_yn`) 시점
     * - **QUEUED 모드**: 여기서 해제하지 않는다. 명령이 접수만 된 상태이므로,
     *   [kr.co.securance.secuhub.server.control.GateControlDispatcher]가 **장비 ACK를 확인한 뒤**
     *   해제한다. 레거시는 전송 직후 해제해, 장비가 명령을 받지 못했어도 화면에서는 장애가
     *   사라지는 문제가 있었다.
     * - **DIRECT 모드**: 액터 체인에 전송이 등록된 시점(SENT)에 해제한다. DIRECT에는 ACK 추적이
     *   없으므로 이것이 가능한 가장 늦은 시점이다.
     */
    @PostMapping("/reset")
    fun reset(
        @RequestParam dtlIp: String,
        @RequestParam dtlLaneNo: Int,
        @RequestParam command: SpeedGateControlCommand,
        @AuthenticationPrincipal user: UserDetails?,
    ): ResponseEntity<GateControlResponse> {
        if (!command.isReset) {
            return ResponseEntity.badRequest().body(
                GateControlResponse(GateControlResult.REJECTED, "리셋 명령이 아닙니다: $command"),
            )
        }

        val response = dispatch(dtlIp, dtlLaneNo, command, securityMode = null, user = user)
        if (response.body?.result != GateControlResult.SENT) return response

        val resolved = try {
            faultResolutionService.resolveByResetCommand(
                dtlIp = dtlIp,
                dtlLaneNo = dtlLaneNo,
                command = command,
                resolvedBy = user?.username ?: "SYSTEM",
            )
        } catch (ex: Exception) {
            // 명령은 이미 장비로 나갔다 — 해제 실패로 요청 전체를 실패 처리하면 운영자가 같은
            // 리셋을 반복해 게이트를 여러 번 리셋하게 된다. 실패는 로그로만 남긴다.
            logger.error("게이트[{}] 레인 {} 리셋 후 장애 해제 실패", dtlIp, dtlLaneNo, ex)
            0
        }

        return ResponseEntity.ok(
            GateControlResponse(
                GateControlResult.SENT,
                "리셋 명령을 게이트로 전송했습니다. 장애 ${resolved}건을 해제했습니다.",
                resolved,
            ),
        )
    }

    private fun dispatch(
        dtlIp: String,
        dtlLaneNo: Int,
        command: SpeedGateControlCommand,
        securityMode: SpeedGateSecurityMode?,
        user: UserDetails?,
    ): ResponseEntity<GateControlResponse> {
        val result = gateControlService.send(
            GateControlRequest(
                dtlIp = dtlIp,
                dtlLaneNo = dtlLaneNo,
                command = command,
                securityMode = securityMode,
                requestedBy = user?.username,
            ),
        )
        logger.info(
            "제어 명령 요청: ip={}, lane={}, cmd={}, 보안등급={}, 사용자={}, 결과={}",
            dtlIp, dtlLaneNo, command, securityMode, user?.username, result,
        )

        return when (result) {
            GateControlResult.SENT ->
                ResponseEntity.ok(GateControlResponse(result, "명령을 게이트로 전송했습니다."))

            GateControlResult.QUEUED ->
                ResponseEntity.accepted()
                    .body(GateControlResponse(result, "명령을 접수했습니다. 곧 게이트로 전송됩니다."))

            GateControlResult.NOT_CONNECTED ->
                ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(GateControlResponse(result, "게이트가 접속되어 있지 않습니다."))

            GateControlResult.REJECTED ->
                ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(GateControlResponse(result, "게이트 처리 대기열이 가득 차 명령이 거부되었습니다."))
        }
    }
}
