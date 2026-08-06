package kr.co.securance.secuhub.web.gate

import kr.co.securance.secuhub.web.menu.MenuProvider
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.servlet.mvc.support.RedirectAttributes

/**
 * #5 SR_F_SetupLocation의 지도 좌표(drag&drop) UI — 계획서 4절에서 별도 스파이크로 미뤘던 부분을
 * Phase 6에서 이관한다. [GateLocationController](위치 이름 CRUD)와 분리한 이유는 이 컨트롤러가
 * 다루는 대상(배치도 이미지, 그룹 좌표)이 순수 CRUD와는 관심사가 다르고, 좌표 저장 엔드포인트가
 * AJAX 전용(JSON 응답)이라 폼 기반 CRUD 컨트롤러의 리다이렉트 패턴과 섞이지 않는 편이 낫기
 * 때문이다.
 *
 * 레거시 `SR_F_SetupLocation`의 드래그앤드롭(`DoDragDrop`/`PnlMap_DragDrop`)은 WinForms 네이티브
 * 드래그소스 API라 웹으로 1:1 이식할 수 없다 — 대신 HTML5 Pointer 이벤트로 동등한 사용자 경험
 * (마우스 다운 → 이동 → 업 시 저장)을 구현한다. 좌표 변환(화면 픽셀 ↔ 원본 이미지 픽셀) 공식은
 * 레거시 `AddGroupIconToMap`의 scaleW/scaleH 계산과 동일하게 유지한다.
 */
@Controller
class LocationMapController(
    private val locationService: GateLocationService,
    private val groupService: GateGroupService,
    private val menuProvider: MenuProvider,
) {
    @GetMapping("/gates/locations/{locId}/map")
    fun mapEditor(@PathVariable locId: Long, model: Model): String {
        val location = locationService.findByIdOrNull(locId) ?: return "redirect:/gates/locations"
        model.addAttribute("menu", menuProvider.menu())
        model.addAttribute("pageTitle", "위치 배치도 — ${location.locName}")
        model.addAttribute("location", location)
        model.addAttribute("groups", groupService.findByLocation(locId))
        return "gates/location-map"
    }

    @PostMapping("/gates/locations/{locId}/map/upload")
    fun uploadMap(
        @PathVariable locId: Long,
        @RequestParam("file") file: MultipartFile,
        redirectAttributes: RedirectAttributes,
    ): String {
        try {
            locationService.uploadMap(locId, file)
            redirectAttributes.addFlashAttribute("message", "배치도 이미지가 저장되었습니다.")
        } catch (e: IllegalArgumentException) {
            redirectAttributes.addFlashAttribute("error", e.message)
        }
        return "redirect:/gates/locations/$locId/map"
    }

    /** 그룹 아이콘을 드래그해 놓은 좌표를 저장한다(원본 이미지 픽셀 기준, JS에서 환산해 전달). */
    @PostMapping("/gates/groups/{grpId}/position")
    @ResponseBody
    fun updatePosition(
        @PathVariable grpId: Long,
        @RequestParam x: Int,
        @RequestParam y: Int,
    ): ResponseEntity<Void> {
        return try {
            groupService.updatePosition(grpId, x, y)
            ResponseEntity.ok().build()
        } catch (e: NoSuchElementException) {
            ResponseEntity.notFound().build()
        }
    }
}
