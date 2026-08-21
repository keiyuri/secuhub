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
    /**
     * 정렬 전용 식별자 — `tb_gate_dtl`에 등록되지 않은 레인(신규 접속 직후 등)이면 null이고,
     * 그 경우 [GateControlPageController.controlPage]가 목록 맨 뒤로 보낸다.
     */
    val locId: Long? = null,
    val grpId: Long? = null,
    val dtlId: Long? = null,
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
                    val laneInfo = state.laneInfoOf(lane)
                    ConnectedGateView(
                        dtlIp = state.dtlIp,
                        dtlLaneNo = lane,
                        gateTypeCode = state.gateTypeCode,
                        laneConfirmed = state.hasAuthoritativeLaneInfo,
                        locId = laneInfo?.locId,
                        grpId = laneInfo?.grpId,
                        dtlId = laneInfo?.dtlId,
                    )
                }
            }
            // 정렬은 위치ID→그룹ID→게이트ID 순(2026-08-21 사용자 요청 — 게이트 목록의 표시 순서는
            // 화면 어디서든 이 기준을 따라야 한다, GateTreeController 트리뷰와 동일한 기준).
            // `tb_gate_dtl`에 아직 등록되지 않은 레인(locId/grpId/dtlId를 모름)은 식별자가 있는
            // 레인들 뒤로 밀어내되, 그 안에서는 IP·레인 번호로 결정적인 순서를 유지한다.
            .sortedWith(
                compareBy(
                    { it.locId == null },
                    { it.locId ?: Long.MAX_VALUE },
                    { it.grpId == null },
                    { it.grpId ?: Long.MAX_VALUE },
                    { it.dtlId == null },
                    { it.dtlId ?: Long.MAX_VALUE },
                    { it.dtlIp },
                    { it.dtlLaneNo },
                ),
            )

        model.addAttribute("gates", gates)
        model.addAttribute("modeCommands", SpeedGateControlCommand.entries.filter { !it.isReset })
        model.addAttribute("resetCommands", SpeedGateControlCommand.entries.filter { it.isReset })
        model.addAttribute("securityModes", SpeedGateSecurityMode.entries)
        return "gate-control"
    }
}
