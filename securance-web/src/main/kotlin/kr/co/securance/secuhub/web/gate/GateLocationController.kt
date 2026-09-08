package kr.co.securance.secuhub.web.gate

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import kr.co.securance.secuhub.domain.entity.GateLocation
import kr.co.securance.secuhub.domain.repository.GateLocationRepository
import kr.co.securance.secuhub.web.menu.MenuProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Controller
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
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

    /**
     * 위치 관리(CRUD) 목록 화면이 아닌 곳(리포트/스케줄/리셋/그룹 관리의 위치 드롭다운 등)에서 사용 —
     * '사용=Y'인 위치만 노출한다(2026-08-20 "예외 없이 전체 목록 조회에 적용" 지시, 단 이 화면
     * 자신의 findAll()은 비활성 위치도 재활성화할 수 있어야 하므로 사용자 확인에 따라 그대로 둔다).
     */
    fun findAllActive(): List<GateLocation> = locationRepository.findByUseYnTrueOrderByLocName()

    /**
     * 위치 관리(CRUD) 목록 자체의 조회 — 기본은 '사용=Y'만 보여주고, [showInactive]가 true일
     * 때만 비활성 위치까지 전부 노출한다(2026-08-20 지시: 관리 화면도 기본은 사용=Y만, 비활성
     * 항목은 별도 보기 기능으로 확인). 비활성 위치를 재활성화하려면 먼저 이 토글로 찾아야 한다.
     */
    fun findAllForManagement(showInactive: Boolean): List<GateLocation> =
        if (showInactive) findAll() else findAllActive()

    fun findByIdOrNull(locId: Long): GateLocation? = locationRepository.findById(locId).orElse(null)

    /**
     * 버그 수정(2026-09-02): [GateLocation.locMap]에 파일명이 채워져 있어도, 그 참조가 가리키는
     * 파일이 실제로 이 서버의 [imageDir]에 존재한다는 보장은 없다 — 공유 개발 DB(여러 환경이
     * 같은 DB에 접속)와 로컬 디스크(환경마다 별도)를 함께 쓰는 구성에서는, 다른 환경에서 업로드된
     * 위치를 이 환경에서 열면 DB의 loc_map은 정상이지만 그 파일은 이 서버 디스크에 아예 없다.
     * 이전에는 이 경우를 구분하지 않고 `<img>`를 그대로 렌더링해, 브라우저가 이미지 로드에 실패한
     * 채(깨진 아이콘/빈 화면) 그 위의 그룹 마커만 눈에 띄는 상태로 보였다 — 게이트 관리 위치 팝업의
     * "지도"를 눌렀을 때 "그룹 아이콘만 보이고 지도 이미지는 안 보인다"는 증상의 실제 원인이었다.
     * 파일 존재 여부를 서버에서 미리 확인해, 없으면 원인이 분명한 안내로 대체한다.
     */
    fun mapImageFileExists(fileName: String): Boolean = File(File(imageDir).absoluteFile, fileName).exists()

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
        // mod_date는 DB에 ON UPDATE 트리거가 없어 직접 갱신해야 한다(GateLocationRepository.touchModDate 참고).
        locationRepository.touchModDate(locId)
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

        // 버그 수정(Opus 리뷰 지적, 2026-09-02): 기존 이미지 파일 삭제는 파일시스템에 대한
        // 즉시·비가역 부수효과라 DB 트랜잭션과 원자적으로 묶이지 않는다 — 엔티티 필드 갱신 "전에"
        // 여기서 바로 지우면, 이후(같은 트랜잭션 안에서 이 메서드 뒤에 붙는 로직 실패, 커밋 시점의
        // 제약조건 위반 등 어떤 이유로든) 트랜잭션이 롤백될 때 DB는 옛 파일명을 계속 가리키는데
        // 실제 파일은 이미 사라진 "유령 참조" 상태가 된다 — 배치도를 다시 열어도 이미지가 표시되지
        // 않는, 이번에 조사한 증상과 정확히 같은 결과를 만든다. 트랜잭션이 실제로 커밋된 뒤에만
        // 옛 파일을 지우도록 삭제를 afterCommit으로 미룬다(커밋 전에는 옛 파일이 그대로 남아 있어
        // 롤백돼도 DB와 파일 상태가 계속 일치한다).
        val oldFileName = location.locMap

        location.locMap = fileName
        location.locMapWidth = image.width
        location.locMapHeight = image.height
        // Codex 리뷰 지적(2026-09-09): mod_date 직접 갱신을 update()에만 넣으면 배치도만 새로
        // 업로드한 경우 mod_date가 갱신되지 않는다 — 이 엔티티를 변경하는 모든 경로에서 호출한다.
        locationRepository.touchModDate(locId)

        if (oldFileName != null) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() {
                    File(dir, oldFileName).takeIf { it.exists() }?.delete()
                }
            })
        }
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
    fun list(@RequestParam(required = false, defaultValue = "false") showInactive: Boolean, model: Model): String {
        model.addAttribute("menu", menuProvider.menu())
        model.addAttribute("pageTitle", "위치 관리")
        model.addAttribute("locations", locationService.findAllForManagement(showInactive))
        model.addAttribute("showInactive", showInactive)
        model.addAttribute("form", GateLocationForm())
        return "gates/locations"
    }

    @GetMapping("/{locId}/edit")
    fun edit(
        @PathVariable locId: Long,
        @RequestParam(required = false, defaultValue = "false") showInactive: Boolean,
        model: Model,
    ): String {
        val location = locationService.findByIdOrNull(locId)
            ?: return "redirect:/gates/locations"
        model.addAttribute("menu", menuProvider.menu())
        model.addAttribute("pageTitle", "위치 관리")
        model.addAttribute("locations", locationService.findAllForManagement(showInactive))
        model.addAttribute("showInactive", showInactive)
        model.addAttribute("editingId", locId)
        model.addAttribute("form", GateLocationForm(locName = location.locName, useYn = location.useYn))
        return "gates/locations"
    }

    @PostMapping
    fun create(
        @Validated @ModelAttribute("form") form: GateLocationForm,
        binding: BindingResult,
        @RequestParam(required = false, defaultValue = "false") showInactive: Boolean,
        model: Model,
        redirectAttributes: RedirectAttributes,
    ): String {
        if (binding.hasErrors()) {
            model.addAttribute("menu", menuProvider.menu())
            model.addAttribute("pageTitle", "위치 관리")
            model.addAttribute("locations", locationService.findAllForManagement(showInactive))
            model.addAttribute("showInactive", showInactive)
            return "gates/locations"
        }
        locationService.create(form)
        redirectAttributes.addFlashAttribute("message", "위치가 등록되었습니다.")
        return "redirect:/gates/locations?showInactive=$showInactive"
    }

    @PostMapping("/{locId}")
    fun update(
        @PathVariable locId: Long,
        @Valid @ModelAttribute("form") form: GateLocationForm,
        binding: BindingResult,
        @RequestParam(required = false, defaultValue = "false") showInactive: Boolean,
        model: Model,
        redirectAttributes: RedirectAttributes,
    ): String {
        if (binding.hasErrors()) {
            model.addAttribute("menu", menuProvider.menu())
            model.addAttribute("pageTitle", "위치 관리")
            model.addAttribute("locations", locationService.findAllForManagement(showInactive))
            model.addAttribute("showInactive", showInactive)
            model.addAttribute("editingId", locId)
            return "gates/locations"
        }
        locationService.update(locId, form)
        redirectAttributes.addFlashAttribute("message", "위치 정보가 수정되었습니다.")
        return "redirect:/gates/locations?showInactive=$showInactive"
    }

    @PostMapping("/{locId}/delete")
    fun delete(
        @PathVariable locId: Long,
        @RequestParam(required = false, defaultValue = "false") showInactive: Boolean,
        redirectAttributes: RedirectAttributes,
    ): String {
        // 하위 그룹/게이트가 남아있는 위치를 삭제하면 FK 제약(fk_gate_dtl_loc 등) 위반으로
        // DataIntegrityViolationException이 던져진다 — 잡지 않으면 500 에러 페이지로 직행한다
        // (2026-08-20 Opus 전체 리뷰 지적). UserController.delete와 동일한 패턴으로 안내 메시지로
        // 바꾼다.
        try {
            locationService.delete(locId)
            redirectAttributes.addFlashAttribute("message", "위치가 삭제되었습니다.")
        } catch (ex: DataIntegrityViolationException) {
            redirectAttributes.addFlashAttribute("error", "하위 그룹/게이트가 남아있어 위치를 삭제할 수 없습니다.")
        }
        return "redirect:/gates/locations?showInactive=$showInactive"
    }
}
