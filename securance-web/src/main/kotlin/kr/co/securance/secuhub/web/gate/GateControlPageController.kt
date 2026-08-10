package kr.co.securance.secuhub.web.gate

import kr.co.securance.secuhub.protocol.SpeedGateControlCommand
import kr.co.securance.secuhub.protocol.SpeedGateSecurityMode
import kr.co.securance.secuhub.server.connection.GateConnectionRegistry
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping

/** 화면에 뿌릴 접속 게이트 1개(레인 단위) 정보. */
data class ConnectedGateView(
    val dtlIp: String,
    val dtlLaneNo: Int,
    val gateTypeCode: Int,
    /** `0x4D` 상태 패킷으로 레인 정보를 확인한 커넥션인지 — 아니면 제어 대상 레인이 불확실하다. */
    val laneConfirmed: Boolean,
)

/**
 * 게이트 제어/리셋 화면(계획서 5.5절, 2차 스프린트 5번 항목).
 *
 * 레거시 `SR_F_GateControl`/`SR_F_GateReset` 폼의 최소 이식이다. 실제 명령 발행은 화면이 아니라
 * [GateControlController]의 REST 엔드포인트가 담당하고, 이 화면은 폼과 결과 표시만 맡는다 —
 * 제어 권한 검사가 URL 규칙 한 곳(`/api/gate-control` 하위)에만 존재하도록 유지하기 위함이다.
 *
 * 대상 게이트 목록은 DB가 아니라 **현재 접속 중인 커넥션**에서 가져온다. 제어는 접속된 장비에만
 * 의미가 있고, `tb_gate_dtl` 전체를 나열하면 운영자가 꺼져 있는 게이트에 명령을 쏘게 된다.
 */
@Controller
class GateControlPageController(
    private val registry: GateConnectionRegistry,
) {

    @GetMapping("/gates/control")
    fun controlPage(model: Model): String {
        val gates = registry.allConnections()
            .flatMap { state ->
                val lanes = state.laneSnapshot().sorted().ifEmpty { listOf(1) }
                lanes.map { lane ->
                    ConnectedGateView(
                        dtlIp = state.dtlIp,
                        dtlLaneNo = lane,
                        gateTypeCode = state.gateTypeCode,
                        laneConfirmed = state.hasAuthoritativeLaneInfo,
                    )
                }
            }
            .sortedWith(compareBy({ it.dtlIp }, { it.dtlLaneNo }))

        model.addAttribute("gates", gates)
        model.addAttribute("modeCommands", SpeedGateControlCommand.entries.filter { !it.isReset })
        model.addAttribute("resetCommands", SpeedGateControlCommand.entries.filter { it.isReset })
        model.addAttribute("securityModes", SpeedGateSecurityMode.entries)
        return "gate-control"
    }
}
