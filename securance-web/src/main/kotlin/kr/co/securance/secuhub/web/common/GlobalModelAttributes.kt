package kr.co.securance.secuhub.web.common

import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ModelAttribute

/**
 * 모든 화면에 현재 요청 경로를 `currentPath`로 주입한다 — 사이드바 메뉴 활성 표시
 * (`fragments/sidebar.html`)에 사용한다(계획서 5.1절, adminlte-react의 active 판정 로직 대응).
 */
@ControllerAdvice
class GlobalModelAttributes {
    @ModelAttribute("currentPath")
    fun currentPath(request: HttpServletRequest): String = request.requestURI
}
