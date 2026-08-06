package kr.co.securance.secuhub.web.gate

import kr.co.securance.secuhub.domain.repository.NetStateRepository
import kr.co.securance.secuhub.web.menu.MenuProvider
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.stereotype.Service
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes

/**
 * #3 SR_F_GateReset — 검색 그리드(계획서 3절 Phase 2) + 리셋 실행(Phase 6, [GateControlService]
 * 의존).
 * 위치→그룹 2단 콤보 + 레인 목록(현재 연결 상태 포함) 조회, 선택한 레인에 리셋 명령을 큐에 적재한다.
 */
@Service
class GateResetGridService(
    private val detailRepository: kr.co.securance.secuhub.domain.repository.GateDetailRepository,
    private val netStateRepository: NetStateRepository,
) {
    fun rowsFor(grpId: Long?): List<GateResetRow> {
        if (grpId == null) return emptyList()
        val netStateByLane = netStateRepository.findByIdGrpId(grpId).associateBy { it.id.dtlLaneNo }
        return detailRepository.findByGroup_GrpIdOrderByDtlLaneNo(grpId).map { detail ->
            GateResetRow(
                dtlId = requireNotNull(detail.dtlId),
                dtlLaneNo = detail.dtlLaneNo,
                dtlIp = detail.dtlIp,
                dtlName = detail.dtlName,
                online = netStateByLane[detail.dtlLaneNo]?.isOnline ?: false,
            )
        }
    }
}

data class GateResetRow(
    val dtlId: Long,
    val dtlLaneNo: Int,
    val dtlIp: String,
    val dtlName: String?,
    val online: Boolean,
)

@Controller
@RequestMapping("/gates/reset")
class GateResetController(
    private val gateResetGridService: GateResetGridService,
    private val locationService: GateLocationService,
    private val groupService: GateGroupService,
    private val gateControlService: GateControlService,
    private val menuProvider: MenuProvider,
) {
    @GetMapping
    fun list(
        @RequestParam(required = false) locId: Long?,
        @RequestParam(required = false) grpId: Long?,
        model: Model,
    ): String {
        model.addAttribute("menu", menuProvider.menu())
        model.addAttribute("pageTitle", "게이트 일괄 리셋")
        model.addAttribute("allLocations", locationService.findAll())
        model.addAttribute("allGroups", groupService.findByLocation(locId))
        model.addAttribute("selectedLocId", locId)
        model.addAttribute("selectedGrpId", grpId)
        model.addAttribute("rows", gateResetGridService.rowsFor(grpId))
        return "gates/reset"
    }

    /**
     * 레거시는 체크된 레인마다 [GateControlService.sendReset]에 해당하는 명령을 순차 전송한다
     * (`SR_F_GateReset.pbReset_MouseClick`). 여기서는 실패(대상 없음)한 레인만 모아 결과 메시지에
     * 반영해, 레거시가 실패 시 각 게이트마다 개별 팝업을 띄우던 것과 동일한 "실패를 조용히 삼키지
     * 않는다"는 취지를 웹에서는 하나의 요약 메시지로 재현한다.
     */
    @PostMapping("/execute")
    fun execute(
        @RequestParam(required = false) locId: Long?,
        @RequestParam(required = false) grpId: Long?,
        @RequestParam(required = false, name = "dtlIds") dtlIds: List<Long>?,
        redirectAttributes: RedirectAttributes,
    ): String {
        val targets = dtlIds.orEmpty()
        if (targets.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "리셋할 게이트를 선택해 주세요.")
            return "redirect:/gates/reset?locId=${locId ?: ""}&grpId=${grpId ?: ""}"
        }

        val requestedBy = currentUsername()
        val failed = targets.filterNot { gateControlService.sendReset(it, requestedBy) }

        if (failed.isEmpty()) {
            redirectAttributes.addFlashAttribute("message", "리셋 명령 ${targets.size}건을 전송 대기열에 등록했습니다.")
        } else {
            redirectAttributes.addFlashAttribute(
                "error",
                "리셋 명령 ${targets.size - failed.size}건 등록, ${failed.size}건 실패(대상 없음): $failed",
            )
        }
        return "redirect:/gates/reset?locId=${locId ?: ""}&grpId=${grpId ?: ""}"
    }

    private fun currentUsername(): String = SecurityContextHolder.getContext().authentication?.name ?: "system"
}
