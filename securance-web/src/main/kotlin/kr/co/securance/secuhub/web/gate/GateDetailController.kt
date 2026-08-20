package kr.co.securance.secuhub.web.gate

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import kr.co.securance.secuhub.domain.entity.GateDetail
import kr.co.securance.secuhub.domain.entity.GateGroup
import kr.co.securance.secuhub.domain.repository.GateDetailRepository
import kr.co.securance.secuhub.domain.repository.GateGroupRepository
import kr.co.securance.secuhub.domain.repository.GateLocationRepository
import kr.co.securance.secuhub.web.menu.MenuProvider
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Controller
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.ui.Model
import org.springframework.validation.BindingResult
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes

/**
 * #4 SR_F_SetupGateGroup의 나머지 절반 — 그룹에 속한 레인(게이트 상세, `tb_gate_dtl`) CRUD.
 * 자연키 `(dtlIp, dtlLaneNo)`는 [kr.co.securance.secuhub.server.tcp.GateTcpServer] 등 실제 커넥션
 * 매칭에 쓰이므로(계획서 4.2절) 여기서 등록한 값이 그대로 게이트 연동에 반영된다.
 */
@Service
class GateDetailService(
    private val detailRepository: GateDetailRepository,
    private val groupRepository: GateGroupRepository,
) {
    /**
     * 레인 관리(CRUD) 목록 자체의 조회 — 기본은 사용/분석 대상(둘 다 'Y')인 레인만 보여주고,
     * [showInactive]가 true일 때만 비활성·미분석 레인까지 전부 노출한다(2026-08-20 코드 리뷰
     * 지적 — Location/Group/User 관리 화면에만 있던 "비활성 항목 표시" 토글이 이 화면에는 빠져
     * 있어 비활성/미분석 레인을 목록에서 찾아 재활성화할 방법이 없었다). 그룹 미선택(grpId=null)
     * 시에는 토글 여부와 무관하게 빈 목록을 반환한다.
     */
    fun findAllForManagement(grpId: Long?, showInactive: Boolean): List<GateDetail> =
        if (grpId == null) {
            emptyList()
        } else if (showInactive) {
            detailRepository.findByGroup_GrpIdOrderByDtlLaneNo(grpId)
        } else {
            detailRepository.findByGroup_GrpIdAndUseYnTrueAndAnalysisYnTrueOrderByDtlLaneNo(grpId)
        }

    /**
     * 스케줄 화면(#6 SetupSchedule)의 레인 콤보 — 예약 명령은 `analysisYn` 여부와 무관하게 적용
     * 대상이므로(레거시 `SelectGateDtlIPList`/`InsertSendDataAll`과 동일, [ScheduleApplyService.targets]
     * 참고) `useYn=true`인 레인만 필터하고 `analysisYn`은 확인하지 않는다(2026-08-20 사용자 확인:
     * "분석=N도 포함, 사용=Y만 필터").
     */
    fun findByGroupForSchedule(grpId: Long?): List<GateDetail> =
        if (grpId == null) emptyList() else detailRepository.findByGroup_GrpIdAndUseYnTrue(grpId)

    fun findByIdOrNull(dtlId: Long): GateDetail? = detailRepository.findById(dtlId).orElse(null)

    // form.grpId/dtlType은 컨트롤러의 @Validated(@NotNull)를 통과한 뒤에만 들어오지만,
    // Kotlin 타입 자체는 여전히 nullable(Long?/Int?)이라 여기서 한 번 더 unwrap한다.

    @Transactional
    fun create(form: GateDetailForm) {
        val grpId = requireNotNull(form.grpId) { "그룹을 선택하세요" }
        val dtlType = requireNotNull(form.dtlType) { "게이트 타입을 선택하세요" }
        val group = groupRepository.findById(grpId).orElseThrow {
            NoSuchElementException("그룹을 찾을 수 없습니다: $grpId")
        }
        detailRepository.save(
            GateDetail(
                location = group.location,
                group = group,
                dtlIp = form.dtlIp,
                dtlLaneNo = form.dtlLaneNo,
                dtlType = dtlType,
                connectType = form.connectType,
                dtlName = form.dtlName?.ifBlank { null },
                useYn = form.useYn,
                analysisYn = form.analysisYn,
            ),
        )
    }

    @Transactional
    fun update(dtlId: Long, form: GateDetailForm) {
        val grpId = requireNotNull(form.grpId) { "그룹을 선택하세요" }
        val dtlType = requireNotNull(form.dtlType) { "게이트 타입을 선택하세요" }
        val detail = detailRepository.findById(dtlId).orElseThrow {
            NoSuchElementException("게이트 상세를 찾을 수 없습니다: $dtlId")
        }
        if (detail.group.grpId != grpId) {
            val group = groupRepository.findById(grpId).orElseThrow {
                NoSuchElementException("그룹을 찾을 수 없습니다: $grpId")
            }
            detail.group = group
            detail.location = group.location
        }
        detail.dtlIp = form.dtlIp
        detail.dtlLaneNo = form.dtlLaneNo
        detail.dtlType = dtlType
        detail.connectType = form.connectType
        detail.dtlName = form.dtlName?.ifBlank { null }
        detail.useYn = form.useYn
        detail.analysisYn = form.analysisYn
    }

    @Transactional
    fun delete(dtlId: Long) {
        detailRepository.deleteById(dtlId)
    }
}

data class GateDetailForm(
    @field:NotNull(message = "그룹을 선택하세요")
    var grpId: Long? = null,
    @field:NotBlank(message = "IP는 필수입니다")
    var dtlIp: String = "",
    @field:Min(value = 0, message = "레인 번호는 0 이상이어야 합니다")
    var dtlLaneNo: Int = 0,
    @field:NotNull(message = "게이트 타입을 선택하세요")
    var dtlType: Int? = null,
    var connectType: Int = 1,
    var dtlName: String? = null,
    var useYn: Boolean = true,
    var analysisYn: Boolean = true,
)

@Controller
@RequestMapping("/gates/details")
class GateDetailController(
    private val detailService: GateDetailService,
    private val locationService: GateLocationService,
    private val groupService: GateGroupService,
    private val gateTypeCodeService: GateTypeCodeService,
    private val menuProvider: MenuProvider,
) {
    @GetMapping
    fun list(
        @RequestParam(required = false) grpId: Long?,
        @RequestParam(required = false, defaultValue = "false") showInactive: Boolean,
        model: Model,
    ): String {
        populateCommon(model, grpId, showInactive)
        model.addAttribute("form", GateDetailForm(grpId = grpId))
        return "gates/details"
    }

    @GetMapping("/{dtlId}/edit")
    fun edit(
        @PathVariable dtlId: Long,
        @RequestParam(required = false, defaultValue = "false") showInactive: Boolean,
        model: Model,
    ): String {
        val detail = detailService.findByIdOrNull(dtlId) ?: return "redirect:/gates/details"
        populateCommon(model, detail.group.grpId, showInactive)
        model.addAttribute("editingId", dtlId)
        model.addAttribute(
            "form",
            GateDetailForm(
                grpId = detail.group.grpId,
                dtlIp = detail.dtlIp,
                dtlLaneNo = detail.dtlLaneNo,
                dtlType = detail.dtlType,
                connectType = detail.connectType,
                dtlName = detail.dtlName,
                useYn = detail.useYn,
                analysisYn = detail.analysisYn,
            ),
        )
        return "gates/details"
    }

    @PostMapping
    fun create(
        @Validated @ModelAttribute("form") form: GateDetailForm,
        binding: BindingResult,
        @RequestParam(required = false, defaultValue = "false") showInactive: Boolean,
        model: Model,
        redirectAttributes: RedirectAttributes,
    ): String {
        if (binding.hasErrors()) {
            populateCommon(model, form.grpId, showInactive)
            return "gates/details"
        }
        detailService.create(form)
        redirectAttributes.addFlashAttribute("message", "게이트 상세(레인)가 등록되었습니다.")
        return "redirect:/gates/details" + queryString(form.grpId, showInactive)
    }

    @PostMapping("/{dtlId}")
    fun update(
        @PathVariable dtlId: Long,
        @Validated @ModelAttribute("form") form: GateDetailForm,
        binding: BindingResult,
        @RequestParam(required = false, defaultValue = "false") showInactive: Boolean,
        model: Model,
        redirectAttributes: RedirectAttributes,
    ): String {
        if (binding.hasErrors()) {
            populateCommon(model, form.grpId, showInactive)
            model.addAttribute("editingId", dtlId)
            return "gates/details"
        }
        detailService.update(dtlId, form)
        redirectAttributes.addFlashAttribute("message", "게이트 상세(레인) 정보가 수정되었습니다.")
        return "redirect:/gates/details" + queryString(form.grpId, showInactive)
    }

    @PostMapping("/{dtlId}/delete")
    fun delete(
        @PathVariable dtlId: Long,
        @RequestParam(required = false) grpId: Long?,
        @RequestParam(required = false, defaultValue = "false") showInactive: Boolean,
        redirectAttributes: RedirectAttributes,
    ): String {
        // 다른 테이블(제어 이력 등)이 이 dtlId를 FK로 참조하는 경우
        // DataIntegrityViolationException이 던져질 수 있다 — GateLocationController.delete와
        // 동일한 이유로 잡아서 안내 메시지로 바꾼다(2026-08-20 Opus 전체 리뷰 지적).
        try {
            detailService.delete(dtlId)
            redirectAttributes.addFlashAttribute("message", "게이트 상세(레인)가 삭제되었습니다.")
        } catch (ex: DataIntegrityViolationException) {
            redirectAttributes.addFlashAttribute("error", "연관된 데이터가 남아있어 이 게이트(레인)를 삭제할 수 없습니다.")
        }
        return "redirect:/gates/details" + queryString(grpId, showInactive)
    }

    private fun queryString(grpId: Long?, showInactive: Boolean): String {
        val params = buildList {
            grpId?.let { add("grpId=$it") }
            if (showInactive) add("showInactive=true")
        }
        return if (params.isEmpty()) "" else "?" + params.joinToString("&")
    }

    /**
     * 그룹 선택 콤보 목록 — 기본은 활성 그룹만 보여주되, 현재 선택된 그룹([grpId])이 비활성이라
     * 이 목록에 없으면 별도로 조회해 끼워 넣는다(2026-08-20 Codex 리뷰 지적: 비활성 그룹의
     * `레인` 링크로 들어오면 `selectedGrpId`는 설정돼도 콤보에 그 그룹이 없어 선택이 빈 값으로
     * 렌더링되고, 이후 "비활성 레인 표시" 체크박스를 누르면 폼이 빈 grpId를 제출해 그룹 선택
     * 자체가 사라졌다). `showInactive=true`일 때는 처음부터 비활성 그룹까지 전부 보여준다.
     */
    private fun groupOptions(grpId: Long?, showInactive: Boolean): List<GateGroup> {
        if (showInactive) return groupService.findByLocation(null)
        val active = groupService.findAllActiveByLocation(null)
        if (grpId == null || active.any { it.grpId == grpId }) return active
        val selected = groupService.findByIdOrNull(grpId) ?: return active
        return active + selected
    }

    private fun populateCommon(model: Model, grpId: Long?, showInactive: Boolean) {
        model.addAttribute("menu", menuProvider.menu())
        model.addAttribute("pageTitle", "게이트 상세(레인) 관리")
        model.addAttribute("allLocations", locationService.findAllActive())
        model.addAttribute("allGroups", groupOptions(grpId, showInactive))
        model.addAttribute("gateTypes", gateTypeCodeService.gateTypes())
        model.addAttribute("selectedGrpId", grpId)
        model.addAttribute("showInactive", showInactive)
        model.addAttribute("details", detailService.findAllForManagement(grpId, showInactive))
    }
}
