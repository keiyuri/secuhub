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
        // Opus 전체 리뷰 지적: dtlLaneNo만으로 키를 만들면 같은 그룹 안에 IP가 다른 두 장비가
        // 같은 레인 번호를 쓸 때 한쪽 상태가 다른 쪽에 덮어써진다. tb_net_state의 실제 복합키
        // (dtlIp, dtlLaneNo, ...)와 동일하게 (dtlIp, dtlLaneNo) 조합으로 키를 만든다.
        val netStateByKey = netStateRepository.findByIdGrpId(grpId).associateBy { it.id.dtlIp to it.id.dtlLaneNo }
        // 관리(CRUD) 목록 화면이 아니므로 '사용=Y'·'분석=Y' 대상만 노출한다
        // (2026-08-20 "예외 없이 전체 목록 조회에 적용" 지시).
        return detailRepository.findByGroup_GrpIdAndUseYnTrueAndAnalysisYnTrueOrderByDtlId(grpId).map { detail ->
            GateResetRow(
                dtlId = requireNotNull(detail.dtlId),
                dtlLaneNo = detail.dtlLaneNo,
                dtlIp = detail.dtlIp,
                dtlName = detail.dtlName,
                online = netStateByKey[detail.dtlIp to detail.dtlLaneNo]?.isOnline ?: false,
            )
        }
    }

    /**
     * 요청된 dtlId 목록 중 실제로 grpId에 속하고 사용/분석 대상(둘 다 'Y')인 것만 남긴다(전체
     * 프로젝트 재감사 지적 — 서버는 이전까지 dtlId 존재 여부만 확인하고 grpId 소속은 확인하지
     * 않아, 클라이언트가 화면에 표시된 그룹과 다른 dtlId를 함께 보내도 그대로 처리됐다).
     *
     * grpId가 없으면 전량 거부한다 — 화면(reset.html)은 그룹을 선택해야만 리셋 버튼을 노출하므로
     * 정상 경로에서는 grpId가 항상 채워져 있다. 예전에는 grpId가 없으면 소속 검증 자체를 생략하고
     * 요청받은 dtlId를 그대로 통과시켰는데, 이는 grpId 파라미터를 비운 조작된 POST로 소속/상태
     * 검증을 완전히 우회할 수 있는 구멍이었다(2026-08-20 Codex 적대적 리뷰 지적).
     */
    fun filterByGroupMembership(grpId: Long?, dtlIds: List<Long>): List<Long> {
        if (dtlIds.isEmpty()) return dtlIds
        if (grpId == null) return emptyList()
        val validIds = detailRepository.findByDtlIdInAndGroup_GrpId(dtlIds, grpId).mapNotNull { it.dtlId }.toSet()
        return dtlIds.filter { it in validIds }
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
        model.addAttribute("allLocations", locationService.findAllActive())
        model.addAttribute("allGroups", groupService.findAllActiveByLocation(locId))
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
        val requested = dtlIds.orEmpty()
        if (requested.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "리셋할 게이트를 선택해 주세요.")
            return "redirect:/gates/reset?locId=${locId ?: ""}&grpId=${grpId ?: ""}"
        }

        // grpId 소속 교차 검증(전체 프로젝트 재감사 지적) — 화면에 표시된 그룹과 무관한 dtlId가
        // 섞여 들어와도 서버가 그대로 처리하지 않도록, 요청된 grpId에 속한 dtlId만 실제 대상으로 삼는다.
        val targets = gateResetGridService.filterByGroupMembership(grpId, requested)
        val rejected = requested - targets.toSet()

        val requestedBy = currentUsername()
        val failed = targets.filterNot { gateControlService.sendReset(it, requestedBy) }

        when {
            rejected.isNotEmpty() -> redirectAttributes.addFlashAttribute(
                "error",
                "리셋 명령 ${targets.size - failed.size}건 등록, " +
                    "${failed.size}건 실패(대상 없음), ${rejected.size}건 거부(선택한 그룹에 속하지 않음): $rejected",
            )
            failed.isNotEmpty() -> redirectAttributes.addFlashAttribute(
                "error",
                "리셋 명령 ${targets.size - failed.size}건 등록, ${failed.size}건 실패(대상 없음): $failed",
            )
            else -> redirectAttributes.addFlashAttribute("message", "리셋 명령 ${targets.size}건을 전송 대기열에 등록했습니다.")
        }
        return "redirect:/gates/reset?locId=${locId ?: ""}&grpId=${grpId ?: ""}"
    }

    private fun currentUsername(): String = SecurityContextHolder.getContext().authentication?.name ?: "system"
}
