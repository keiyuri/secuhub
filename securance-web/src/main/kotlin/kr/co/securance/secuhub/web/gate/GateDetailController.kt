package kr.co.securance.secuhub.web.gate

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import kr.co.securance.secuhub.domain.entity.GateDetail
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
    fun findByGroup(grpId: Long?): List<GateDetail> =
        if (grpId == null) emptyList() else detailRepository.findByGroup_GrpIdOrderByDtlLaneNo(grpId)

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
    fun list(@RequestParam(required = false) grpId: Long?, model: Model): String {
        populateCommon(model, grpId)
        model.addAttribute("form", GateDetailForm(grpId = grpId))
        return "gates/details"
    }

    @GetMapping("/{dtlId}/edit")
    fun edit(@PathVariable dtlId: Long, model: Model): String {
        val detail = detailService.findByIdOrNull(dtlId) ?: return "redirect:/gates/details"
        populateCommon(model, detail.group.grpId)
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
        model: Model,
        redirectAttributes: RedirectAttributes,
    ): String {
        if (binding.hasErrors()) {
            populateCommon(model, form.grpId)
            return "gates/details"
        }
        detailService.create(form)
        redirectAttributes.addFlashAttribute("message", "게이트 상세(레인)가 등록되었습니다.")
        return "redirect:/gates/details" + (form.grpId?.let { "?grpId=$it" } ?: "")
    }

    @PostMapping("/{dtlId}")
    fun update(
        @PathVariable dtlId: Long,
        @Validated @ModelAttribute("form") form: GateDetailForm,
        binding: BindingResult,
        model: Model,
        redirectAttributes: RedirectAttributes,
    ): String {
        if (binding.hasErrors()) {
            populateCommon(model, form.grpId)
            model.addAttribute("editingId", dtlId)
            return "gates/details"
        }
        detailService.update(dtlId, form)
        redirectAttributes.addFlashAttribute("message", "게이트 상세(레인) 정보가 수정되었습니다.")
        return "redirect:/gates/details" + (form.grpId?.let { "?grpId=$it" } ?: "")
    }

    @PostMapping("/{dtlId}/delete")
    fun delete(
        @PathVariable dtlId: Long,
        @RequestParam(required = false) grpId: Long?,
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
        return "redirect:/gates/details" + (grpId?.let { "?grpId=$it" } ?: "")
    }

    private fun populateCommon(model: Model, grpId: Long?) {
        model.addAttribute("menu", menuProvider.menu())
        model.addAttribute("pageTitle", "게이트 상세(레인) 관리")
        model.addAttribute("allLocations", locationService.findAll())
        model.addAttribute("allGroups", groupService.findByLocation(null))
        model.addAttribute("gateTypes", gateTypeCodeService.gateTypes())
        model.addAttribute("selectedGrpId", grpId)
        model.addAttribute("details", detailService.findByGroup(grpId))
    }
}
