package kr.co.securance.secuhub.web.gate

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import kr.co.securance.secuhub.domain.entity.GateLocation
import kr.co.securance.secuhub.domain.repository.GateLocationRepository
import kr.co.securance.secuhub.web.menu.MenuProvider
import org.springframework.beans.factory.annotation.Value
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
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import java.io.File
import java.util.UUID
import javax.imageio.ImageIO

/**
 * #5 SR_F_SetupLocation — 위치 이름 CRUD(#4 SetupGateGroup이 필요로 하는 최소 범위, Phase 2에서
 * 우선 이관)에 더해, Phase 6에서 배치도 이미지 업로드 + 그룹 좌표 배치([LocationMapController])를
 * 추가했다. 레거시의 개별 장비 아이콘 드래그앤드롭·구역(전체) 맵 계층은 옮기지 않았다 — 스키마에
 * 장비별 좌표 컬럼(`dtl_map_x`/`dtl_map_y`)이 애초에 없고, 이 화면의 실사용 목적(운영자가 "이 위치에
 * 어떤 그룹들이 있는지" 한눈에 보는 것)에는 위치 배치도 + 그룹 아이콘 배치만으로 충분하다고 판단했다
 * (2026-08-06, 범위 축소 — Phase 5의 SR_F_Schedule 죽은 코드 배제와 동일한 성격의 자체 판단이며
 * 사용자 확인 없이 진행했다. 상세는 `docs/SR_Speed_Client_전환_계획.md` 참고).
 */
@Service
class GateLocationService(
    private val locationRepository: GateLocationRepository,
    @Value("\${securance.location.image-dir:./data/loc-images}") private val imageDir: String,
) {
    fun findAll(): List<GateLocation> = locationRepository.findAll(org.springframework.data.domain.Sort.by("locName"))

    fun findByIdOrNull(locId: Long): GateLocation? = locationRepository.findById(locId).orElse(null)

    @Transactional
    fun create(form: GateLocationForm) {
        locationRepository.save(GateLocation(locName = form.locName, useYn = form.useYn))
    }

    @Transactional
    fun update(locId: Long, form: GateLocationForm) {
        val location = locationRepository.findById(locId).orElseThrow {
            NoSuchElementException("위치를 찾을 수 없습니다: $locId")
        }
        location.locName = form.locName
        location.useYn = form.useYn
    }

    @Transactional
    fun delete(locId: Long) {
        locationRepository.deleteById(locId)
    }

    /**
     * 배치도 이미지를 저장하고 원본 가로/세로(px)를 [GateLocation.locMapWidth]/[locMapHeight]에
     * 기록한다(레거시가 `pnlMap.BackgroundImage.Save(...)` 저장 시 파일명만 남기고 크기는 별도로
     * 다시 읽던 것과 달리, 업로드 시점에 한 번에 계산해 둔다). 파일명은 원본 확장자를 유지한 채
     * UUID로 새로 부여한다 — 레거시(원본 파일명 그대로 저장, 충돌 시 덮어쓰기 없음 가정)와 달리
     * 동시에 여러 사용자가 업로드해도 충돌하지 않도록 하기 위함(레거시 그대로 재현 원칙의 예외 —
     * 물리 프로토콜이 아닌 순수 웹 인프라 영역이라 안전 우선으로 개선).
     */
    @Transactional
    fun uploadMap(locId: Long, file: MultipartFile) {
        require(!file.isEmpty) { "이미지 파일을 선택하세요" }
        val location = locationRepository.findById(locId).orElseThrow {
            NoSuchElementException("위치를 찾을 수 없습니다: $locId")
        }

        // 확장자를 클라이언트가 보낸 원본 파일명에서 그대로 가져오면, 유효한 이미지 바이트 뒤에
        // HTML/스크립트를 이어붙인 폴리글롯 파일이 ImageIO 검증을 통과한 채 `.html`/`.svg` 등으로
        // 저장되어 정적 서빙(/loc-images/**) 시 그대로 실행될 수 있다(저장형 XSS). 허용 확장자를
        // 화이트리스트로 강제해 이를 차단한다.
        val ext = file.originalFilename?.substringAfterLast('.', "")?.lowercase().orEmpty()
        require(ext in ALLOWED_IMAGE_EXTENSIONS) { "지원하지 않는 파일 형식입니다(png/jpg/jpeg/gif/bmp만 가능)" }
        val fileName = "${UUID.randomUUID()}.$ext"
        val dir = File(imageDir).absoluteFile.also { it.mkdirs() }
        val target = File(dir, fileName)
        file.transferTo(target)

        // ImageIO 판독 실패(비이미지 업로드 등) 시 방금 쓴 target 파일이 어디에도 참조되지 않은 채
        // 디스크에 고아로 남는다 — 실패 케이스가 반복되면 loc-images 디렉터리에 쓰레기가 쌓이므로
        // 예외를 던지기 전에 반드시 지운다.
        val image = try {
            ImageIO.read(target)
                ?: throw IllegalArgumentException("이미지 파일을 읽을 수 없습니다(지원하지 않는 형식일 수 있습니다)")
        } catch (e: Exception) {
            target.delete()
            throw e
        }

        // 기존 이미지 파일은 새 이미지로 교체된 뒤에는 더 이상 참조되지 않으므로 정리한다.
        // 주의: 이 파일시스템 쓰기는 DB 트랜잭션과 원자적으로 묶이지 않는다 — 이 메서드가 트랜잭션의
        // 마지막 단계라 현재는 위험이 낮지만, 이후 같은 트랜잭션에 로직이 더 붙는다면 DB 롤백 시
        // 파일 상태와 어긋날 수 있음을 유의한다.
        location.locMap?.let { old -> File(dir, old).takeIf { it.exists() }?.delete() }

        location.locMap = fileName
        location.locMapWidth = image.width
        location.locMapHeight = image.height
    }

    companion object {
        private val ALLOWED_IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "bmp")
    }
}

data class GateLocationForm(
    @field:NotBlank(message = "위치명은 필수입니다")
    var locName: String = "",
    var useYn: Boolean = true,
)

@Controller
@RequestMapping("/gates/locations")
class GateLocationController(
    private val locationService: GateLocationService,
    private val menuProvider: MenuProvider,
) {
    @GetMapping
    fun list(model: Model): String {
        model.addAttribute("menu", menuProvider.menu())
        model.addAttribute("pageTitle", "위치 관리")
        model.addAttribute("locations", locationService.findAll())
        model.addAttribute("form", GateLocationForm())
        return "gates/locations"
    }

    @GetMapping("/{locId}/edit")
    fun edit(@PathVariable locId: Long, model: Model): String {
        val location = locationService.findByIdOrNull(locId)
            ?: return "redirect:/gates/locations"
        model.addAttribute("menu", menuProvider.menu())
        model.addAttribute("pageTitle", "위치 관리")
        model.addAttribute("locations", locationService.findAll())
        model.addAttribute("editingId", locId)
        model.addAttribute("form", GateLocationForm(locName = location.locName, useYn = location.useYn))
        return "gates/locations"
    }

    @PostMapping
    fun create(
        @Validated @ModelAttribute("form") form: GateLocationForm,
        binding: BindingResult,
        model: Model,
        redirectAttributes: RedirectAttributes,
    ): String {
        if (binding.hasErrors()) {
            model.addAttribute("menu", menuProvider.menu())
            model.addAttribute("pageTitle", "위치 관리")
            model.addAttribute("locations", locationService.findAll())
            return "gates/locations"
        }
        locationService.create(form)
        redirectAttributes.addFlashAttribute("message", "위치가 등록되었습니다.")
        return "redirect:/gates/locations"
    }

    @PostMapping("/{locId}")
    fun update(
        @PathVariable locId: Long,
        @Valid @ModelAttribute("form") form: GateLocationForm,
        binding: BindingResult,
        model: Model,
        redirectAttributes: RedirectAttributes,
    ): String {
        if (binding.hasErrors()) {
            model.addAttribute("menu", menuProvider.menu())
            model.addAttribute("pageTitle", "위치 관리")
            model.addAttribute("locations", locationService.findAll())
            model.addAttribute("editingId", locId)
            return "gates/locations"
        }
        locationService.update(locId, form)
        redirectAttributes.addFlashAttribute("message", "위치 정보가 수정되었습니다.")
        return "redirect:/gates/locations"
    }

    @PostMapping("/{locId}/delete")
    fun delete(@PathVariable locId: Long, redirectAttributes: RedirectAttributes): String {
        locationService.delete(locId)
        redirectAttributes.addFlashAttribute("message", "위치가 삭제되었습니다.")
        return "redirect:/gates/locations"
    }
}
