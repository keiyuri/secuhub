package kr.co.securance.secuhub.web.gate

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import kr.co.securance.secuhub.domain.entity.CodeMaster
import kr.co.securance.secuhub.domain.entity.GateGroup
import kr.co.securance.secuhub.domain.entity.GateLocation
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

    /**
     * 그룹 관리(CRUD) 목록 화면이 아닌 곳(리포트/스케줄/리셋/배치도 등)에서 사용 — '사용=Y'인 그룹만
     * 노출한다(2026-08-20 "예외 없이 전체 목록 조회에 적용" 지시, 단 이 화면 자신의 findByLocation()은
     * 비활성 그룹도 재활성화할 수 있어야 하므로 사용자 확인에 따라 그대로 둔다).
     */
    fun findAllActiveByLocation(locId: Long?): List<GateGroup> =
        if (locId == null) groupRepository.findAllByUseYnTrue() else groupRepository.findByLocation_LocIdAndUseYnTrue(locId)

    /**
     * 그룹 관리(CRUD) 목록 자체의 조회 — 기본은 '사용=Y'인 그룹만 보여주고, [showInactive]가
     * true일 때만 비활성 그룹까지 전부 노출한다(2026-08-20 지시: 관리 화면도 기본은 사용=Y만,
     * 비활성 항목은 별도 보기 기능으로 확인). 비활성 그룹을 재활성화하려면 먼저 이 토글로 찾아야 한다.
     */
    fun findAllForManagement(locId: Long?, showInactive: Boolean): List<GateGroup> =
        if (showInactive) findByLocation(locId) else findAllActiveByLocation(locId)

    // location까지 fetch join된 조회 — 반환값을 뷰 렌더링 단계에서 .location.locName처럼
    // 읽어도 LazyInitializationException이 나지 않는다(2026-08-20 Codex 리뷰 지적, 상세는
    // GateGroupRepository.findByIdWithLocation 주석 참고).
    fun findByIdOrNull(grpId: Long): GateGroup? = groupRepository.findByIdWithLocation(grpId)

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
    fun list(
        @RequestParam(required = false) locId: Long?,
        @RequestParam(required = false, defaultValue = "false") showInactive: Boolean,
        model: Model,
    ): String {
        populateCommon(model, locId, showInactive)
        model.addAttribute("form", GateGroupForm(locId = locId))
        return "gates/groups"
    }

    @GetMapping("/{grpId}/edit")
    fun edit(
        @PathVariable grpId: Long,
        @RequestParam(required = false, defaultValue = "false") showInactive: Boolean,
        model: Model,
    ): String {
        val group = groupService.findByIdOrNull(grpId) ?: return "redirect:/gates/groups"
        populateCommon(model, group.location.locId, showInactive)
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
        @RequestParam(required = false, defaultValue = "false") showInactive: Boolean,
        model: Model,
        redirectAttributes: RedirectAttributes,
    ): String {
        if (binding.hasErrors()) {
            populateCommon(model, form.locId, showInactive)
            return "gates/groups"
        }
        groupService.create(form)
        redirectAttributes.addFlashAttribute("message", "게이트그룹이 등록되었습니다.")
        return "redirect:/gates/groups" + queryString(form.locId, showInactive)
    }

    @PostMapping("/{grpId}")
    fun update(
        @PathVariable grpId: Long,
        @Validated @ModelAttribute("form") form: GateGroupForm,
        binding: BindingResult,
        @RequestParam(required = false, defaultValue = "false") showInactive: Boolean,
        model: Model,
        redirectAttributes: RedirectAttributes,
    ): String {
        if (binding.hasErrors()) {
            populateCommon(model, form.locId, showInactive)
            model.addAttribute("editingId", grpId)
            return "gates/groups"
        }
        groupService.update(grpId, form)
        redirectAttributes.addFlashAttribute("message", "게이트그룹 정보가 수정되었습니다.")
        return "redirect:/gates/groups" + queryString(form.locId, showInactive)
    }

    @PostMapping("/{grpId}/delete")
    fun delete(
        @PathVariable grpId: Long,
        @RequestParam(required = false) locId: Long?,
        @RequestParam(required = false, defaultValue = "false") showInactive: Boolean,
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
        return "redirect:/gates/groups" + queryString(locId, showInactive)
    }

    private fun queryString(locId: Long?, showInactive: Boolean): String {
        val params = buildList {
            locId?.let { add("locId=$it") }
            if (showInactive) add("showInactive=true")
        }
        return if (params.isEmpty()) "" else "?" + params.joinToString("&")
    }

    /**
     * 위치 선택 콤보 목록 — 기본은 활성 위치만 보여주되, 현재 선택된 위치([locId])가 비활성이라
     * 이 목록에 없으면 별도로 조회해 끼워 넣는다(2026-08-20 Codex 리뷰 지적: `showInactive=true`
     * 경로로 비활성 위치에 속한 그룹이 목록에 나타나도, 위치 콤보는 항상 활성 위치만 구성돼 있어
     * 그 그룹을 수정하려 하면 현재 위치에 대응하는 `<option>`이 없어 필수 위치 값이 비거나 다른
     * 활성 위치로 바뀌어 저장될 수 있었다 — 비활성 위치에 속한 그룹을 안전하게 재활성화할 수 없는
     * 문제). `showInactive=true`일 때는 처음부터 비활성 위치까지 전부 보여준다.
     */
    private fun locationOptions(locId: Long?, showInactive: Boolean): List<GateLocation> {
        if (showInactive) return locationService.findAll()
        val active = locationService.findAllActive()
        if (locId == null || active.any { it.locId == locId }) return active
        val selected = locationService.findByIdOrNull(locId) ?: return active
        return active + selected
    }

    private fun populateCommon(model: Model, locId: Long?, showInactive: Boolean) {
        model.addAttribute("menu", menuProvider.menu())
        model.addAttribute("pageTitle", "게이트그룹 관리")
        model.addAttribute("allLocations", locationOptions(locId, showInactive))
        model.addAttribute("gateTypes", gateTypeCodeService.gateTypes())
        model.addAttribute("selectedLocId", locId)
        model.addAttribute("showInactive", showInactive)
        model.addAttribute("groups", groupService.findAllForManagement(locId, showInactive))
    }
}
