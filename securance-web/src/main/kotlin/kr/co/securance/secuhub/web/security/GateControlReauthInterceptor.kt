package kr.co.securance.secuhub.web.security

import tools.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.servlet.HandlerInterceptor

/**
 * `securance.security.gate-control-reauth-required=true`일 때 상태를 바꾸는 게이트 제어 요청마다
 * `reauthPassword` 파라미터를 [GateControlReauthService]로 검증한다(2026-08-12 사용자 확인).
 *
 * 컨트롤러마다 검증 로직을 반복하지 않도록 [kr.co.securance.secuhub.web.common.WebConfig]가
 * `/api/gate-control` 하위, `/gates/details/{id}/mode`, `/gates/details/{id}/motor`,
 * `/gates/reset/execute` 경로에 이 인터셉터 하나만 등록해 일괄 적용한다. `/schedule` 하위는
 * [SecurityConfig]에 권한 규칙은 이미 있지만 실제 컨트롤러가 아직 스캐폴드 단계라(SecurityConfig
 * 주석 참고) 대상에서 제외했다 — 컨트롤러가 추가되면 경로 패턴도 함께 추가해야 한다.
 */
class GateControlReauthInterceptor(
    private val properties: SecuritySettingsProperties,
    private val reauthService: GateControlReauthService,
    private val objectMapper: ObjectMapper,
) : HandlerInterceptor {

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        if (!properties.gateControlReauthRequired) return true
        // GET(조회 화면)은 상태를 바꾸지 않으므로 재인증 대상이 아니다 — /gates/details/{id}/mode,
        // /motor는 GET(폼 표시)/POST(제출)를 같은 경로로 공유해 경로 패턴만으로는 구분할 수 없다.
        if (request.method !in STATE_CHANGING_METHODS) return true

        val username = SecurityContextHolder.getContext().authentication?.name
        val password = request.getParameter("reauthPassword")
        if (username != null && reauthService.verify(username, password)) return true

        if (request.requestURI.startsWith("/api/")) {
            respondJson(response)
        } else {
            redirectBack(request, response)
        }
        return false
    }

    private fun respondJson(response: HttpServletResponse) {
        response.status = HttpServletResponse.SC_UNAUTHORIZED
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = "UTF-8"
        // GateControlApiController.GateControlResponse와 같은 형태(result/message)로 응답해
        // gate-tree.js/dashboard-realtime.js가 기존에 읽던 필드를 그대로 쓸 수 있게 하고,
        // reauthRequired만 추가로 얹어 "재인증 프롬프트를 띄우고 재시도하라"는 신호를 준다.
        response.writer.write(
            objectMapper.writeValueAsString(
                mapOf(
                    "result" to "REJECTED",
                    "message" to "재인증이 필요합니다. 비밀번호를 다시 입력해 주세요.",
                    "reauthRequired" to true,
                ),
            ),
        )
    }

    /**
     * 폼 제출(모드 변경/모터 설정/일괄 리셋) 실패 시 원래 화면으로 돌려보낸다. 인터셉터 단계에서는
     * 컨트롤러의 `RedirectAttributes`(플래시 메시지)를 쓸 수 없어, 대신 쿼리 파라미터
     * (`reauthError=1`)로 실패를 알리고 각 화면 템플릿이 이를 읽어 오류 문구를 표시한다.
     */
    private fun redirectBack(request: HttpServletRequest, response: HttpServletResponse) {
        val referer = request.getHeader("Referer")
        val target = referer?.substringBefore('?') ?: "/dashboard"
        response.sendRedirect("$target?reauthError=1")
    }

    private companion object {
        val STATE_CHANGING_METHODS = setOf("POST", "PUT", "DELETE")
    }
}
