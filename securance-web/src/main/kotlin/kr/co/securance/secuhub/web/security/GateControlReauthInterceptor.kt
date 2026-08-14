package kr.co.securance.secuhub.web.security

import tools.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.servlet.HandlerInterceptor
import java.net.URI

/**
 * `securance.security.gate-control-reauth-required=true`일 때 상태를 바꾸는 게이트 제어 요청마다
 * `reauthPassword` 파라미터를 [GateControlReauthService]로 검증한다(2026-08-12 사용자 확인).
 *
 * 컨트롤러마다 검증 로직을 반복하지 않도록 [kr.co.securance.secuhub.web.common.WebConfig]가
 * `/api/gate-control` 하위, `/gates/details/{id}/mode`, `/gates/details/{id}/motor`,
 * `/gates/reset/execute`, `/schedule/apply`, `/schedule/reset`, `/schedule/timezones`(+`/sync`)
 * 경로에 이 인터셉터 하나만 등록해 일괄 적용한다 — 게이트에 실제 제어 명령을 큐에 적재하는 상태
 * 변경 엔드포인트는 전부 포함해야 한다(새 제어 엔드포인트를 추가하면 경로 패턴도 함께 추가할 것).
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

        // [2026-08-14 사용자 요청] 게이트 제어 요청의 권한(재인증 비밀번호) 검증보다 먼저 최초
        // 로그인 여부를 확인한다. Spring Security 필터 체인이 이 경로들을 이미
        // hasAnyRole(CONTROL, ADMIN)으로 보호하므로 실제로는 미인증 상태로 여기 도달할 수 없지만,
        // 방어적으로 명시해 둔다 — 인증이 없거나(anonymousAuthenticationFilter가 채운
        // AnonymousAuthenticationToken 포함) isAuthenticated가 false인 경우 비밀번호 검증 자체를
        // 시도하지 않고 즉시 거부한다.
        val authentication = SecurityContextHolder.getContext().authentication
        if (authentication == null || !authentication.isAuthenticated || authentication is AnonymousAuthenticationToken) {
            reject(request, response)
            return false
        }

        val password = request.getParameter("reauthPassword")
        if (reauthService.verify(authentication.name, password)) return true

        reject(request, response)
        return false
    }

    private fun reject(request: HttpServletRequest, response: HttpServletResponse) {
        if (request.requestURI.startsWith("/api/")) {
            respondJson(response)
        } else {
            redirectBack(request, response)
        }
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
     *
     * 코드 리뷰 지적(2026-08-14): `Referer` 헤더는 클라이언트가 완전히 제어할 수 있는 값이라
     * 검증 없이 `sendRedirect`에 넣으면 오픈 리다이렉트가 된다([MultipartExceptionHandler]의
     * `safeRedirectPath`와 동일한 이유·방식으로 스킴/호스트를 버리고 같은 서버 내 경로만 쓴다).
     */
    private fun redirectBack(request: HttpServletRequest, response: HttpServletResponse) {
        response.sendRedirect("${safeRedirectPath(request.getHeader("Referer"))}?reauthError=1")
    }

    /** [MultipartExceptionHandler.safeRedirectPath]와 동일한 정책 — 이 클래스 KDoc 참고. */
    private fun safeRedirectPath(referer: String?): String {
        if (referer.isNullOrBlank()) return "/dashboard"
        val path = runCatching { URI(referer).rawPath }.getOrNull()
        if (path.isNullOrBlank() || !path.startsWith("/")) return "/dashboard"
        return path
    }

    private companion object {
        val STATE_CHANGING_METHODS = setOf("POST", "PUT", "DELETE")
    }
}
