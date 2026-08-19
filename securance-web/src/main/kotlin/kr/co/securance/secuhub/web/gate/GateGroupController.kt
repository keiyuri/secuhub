package kr.co.securance.secuhub.web.gate

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import kr.co.securance.secuhub.domain.entity.CodeMaster
import kr.co.securance.secuhub.domain.entity.GateGroup
import kr.co.securance.secuhub.domain.repository.CodeMasterRepository
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

/** `tb_code`(코드그룹 `GATE_TYPE`)을 게이트 타입 선택 콤보용으로 조회한다(계획서 4.3절). */
@Service
class GateTypeCodeService(private val codeMasterRepository: CodeMasterRepository) {
    fun gateTypes(): List<CodeMaster> = codeMasterRepository.findById_CodeGroupAndUseYnTrueOrderByDisplayOrder("GATE_TYPE")
}

/**
 * #4 SR_F_SetupGateGroup — 게이트그룹 CRUD.
 * 위치→그룹 2단 콤보 검색 패턴의 1호 구현(계획서 `SR_Speed_Client_전환_계획.md` 5절 공통 패턴).
 */
@Service
class GateGroupService(
    private val groupRepository: GateGroupRepository,
    private val locationRepository: GateLocationRepository,
) {
    fun findByLocation(locId: Long?): List<GateGroup> =
        if (locId == null) groupRepository.findAll() else groupRepository.findByLocation_LocId(locId)

    fun findByIdOrNull(grpId: Long): GateGroup? = groupRepository.findById(grpId).orElse(null)

    // form.locId/gateTypeCode는 컨트롤러의 @Validated(@NotNull)가 이미 통과시킨 뒤에만 서비스로
    // 들어오지만, Kotlin 타입 자체는 여전히 nullable(Long?/Int?)이라 여기서 한 번 더 unwrap한다.

    @Transactional
    fun create(form: GateGroupForm) {
        val locId = requireNotNull(form.locId) { "위치를 선택하세요" }
        val gateTypeCode = requireNotNull(form.gateTypeCode) { "게이트 타입을 선택하세요" }
        val location = locationRepository.findById(locId).orElseThrow {
            NoSuchElementException("위치를 찾을 수 없습니다: $locId")
        }
        groupRepository.save(
            GateGroup(
                location = location,
                grpName = form.grpName,
                laneCount = form.laneCount,
                gateTypeCode = gateTypeCode,
                linkType = form.linkType,
                useYn = form.useYn,
            ),
        )
    }

    @Transactional
    fun update(grpId: Long, form: GateGroupForm) {
        val locId = requireNotNull(form.locId) { "위치를 선택하세요" }
        val gateTypeCode = requireNotNull(form.gateTypeCode) { "게이트 타입을 선택하세요" }
        val group = groupRepository.findById(grpId).orElseThrow {
            NoSuchElementException("그룹을 찾을 수 없습니다: $grpId")
        }
        if (group.location.locId != locId) {
            group.location = locationRepository.findById(locId).orElseThrow {
                NoSuchElementException("위치를 찾을 수 없습니다: $locId")
            }
        }
        group.grpName = form.grpName
        group.laneCount = form.laneCount
        group.gateTypeCode = gateTypeCode
        group.linkType = form.linkType
        group.useYn = form.useYn
    }

    @Transactional
    fun delete(grpId: Long) {
        groupRepository.deleteById(grpId)
    }

    /**
     * #5 SetupLocation 배치도 위 그룹 아이콘 좌표 저장([LocationMapController]에서 호출).
     * 좌표는 항상 [GateLocation.locMapWidth]/[locMapHeight] 원본 이미지 픽셀 기준으로 전달받는다 —
     * 화면 표시 크기와 원본 크기의 스케일 환산은 클라이언트(JS)에서 처리한다(레거시
     * `AddGroupIconToMap`의 scaleW/scaleH 변환과 동일한 책임 분담).
     */
    @Transactional
    fun updatePosition(grpId: Long, x: Int, y: Int) {
        val group = groupRepository.findById(grpId).orElseThrow {
            NoSuchElementException("그룹을 찾을 수 없습니다: $grpId")
        }
        group.grpX = x
        group.grpY = y
    }
}

data class GateGroupForm(
    @field:NotNull(message = "위치를 선택하세요")
    var locId: Long? = null,
    @field:NotBlank(message = "그룹명은 필수입니다")
    var grpName: String = "",
    @field:Min(value = 1, message = "레인 수는 1 이상이어야 합니다")
    var laneCount: Int = 1,
    @field:NotNull(message = "게이트 타입을 선택하세요")
    var gateTypeCode: Int? = null,
    var linkType: Int = 1,
    var useYn: Boolean = true,
) {
    /** [kr.co.securance.secuhub.domain.entity.GateGroup] 펜스포스트 규칙(레인수+1) 미리보기용. */
    val physicalGateCountPreview: Int get() = laneCount + 1
}

@Controller
@RequestMapping("/gates/groups")
class GateGroupController(
    private val groupService: GateGroupService,
    private val locationService: GateLocationService,
    private val gateTypeCodeService: GateTypeCodeService,
    private val menuProvider: MenuProvider,
) {
    @GetMapping
    fun list(@RequestParam(required = false) locId: Long?, model: Model): String {
        populateCommon(model, locId)
        model.addAttribute("form", GateGroupForm(locId = locId))
        return "gates/groups"
    }

    @GetMapping("/{grpId}/edit")
    fun edit(@PathVariable grpId: Long, model: Model): String {
        val group = groupService.findByIdOrNull(grpId) ?: return "redirect:/gates/groups"
        populateCommon(model, group.location.locId)
        model.addAttribute("editingId", grpId)
        model.addAttribute(
            "form",
            GateGroupForm(
                locId = group.location.locId,
                grpName = group.grpName,
                laneCount = group.laneCount,
                gateTypeCode = group.gateTypeCode,
                linkType = group.linkType,
                useYn = group.useYn,
            ),
        )
        return "gates/groups"
    }

    @PostMapping
    fun create(
        @Validated @ModelAttribute("form") form: GateGroupForm,
        binding: BindingResult,
        model: Model,
        redirectAttributes: RedirectAttributes,
    ): String {
        if (binding.hasErrors()) {
            populateCommon(model, form.locId)
            return "gates/groups"
        }
        groupService.create(form)
        redirectAttributes.addFlashAttribute("message", "게이트그룹이 등록되었습니다.")
        return "redirect:/gates/groups" + (form.locId?.let { "?locId=$it" } ?: "")
    }

    @PostMapping("/{grpId}")
    fun update(
        @PathVariable grpId: Long,
        @Validated @ModelAttribute("form") form: GateGroupForm,
        binding: BindingResult,
        model: Model,
        redirectAttributes: RedirectAttributes,
    ): String {
        if (binding.hasErrors()) {
            populateCommon(model, form.locId)
            model.addAttribute("editingId", grpId)
            return "gates/groups"
        }
        groupService.update(grpId, form)
        redirectAttributes.addFlashAttribute("message", "게이트그룹 정보가 수정되었습니다.")
        return "redirect:/gates/groups" + (form.locId?.let { "?locId=$it" } ?: "")
    }

    @PostMapping("/{grpId}/delete")
    fun delete(
        @PathVariable grpId: Long,
        @RequestParam(required = false) locId: Long?,
        redirectAttributes: RedirectAttributes,
    ): String {
        // 하위 게이트(레인)가 남아있는 그룹을 삭제하면 FK 제약 위반으로
        // DataIntegrityViolationException이 던져진다 — GateLocationController.delete와 동일한
        // 이유로 잡아서 안내 메시지로 바꾼다(2026-08-20 Opus 전체 리뷰 지적).
        try {
            groupService.delete(grpId)
            redirectAttributes.addFlashAttribute("message", "게이트그룹이 삭제되었습니다.")
        } catch (ex: DataIntegrityViolationException) {
            redirectAttributes.addFlashAttribute("error", "하위 게이트(레인)가 남아있어 그룹을 삭제할 수 없습니다.")
        }
        return "redirect:/gates/groups" + (locId?.let { "?locId=$it" } ?: "")
    }

    private fun populateCommon(model: Model, locId: Long?) {
        model.addAttribute("menu", menuProvider.menu())
        model.addAttribute("pageTitle", "게이트그룹 관리")
        model.addAttribute("allLocations", locationService.findAll())
        model.addAttribute("gateTypes", gateTypeCodeService.gateTypes())
        model.addAttribute("selectedLocId", locId)
        model.addAttribute("groups", groupService.findByLocation(locId))
    }
}
