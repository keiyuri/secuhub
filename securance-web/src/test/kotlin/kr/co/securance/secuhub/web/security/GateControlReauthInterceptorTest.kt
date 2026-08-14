package kr.co.securance.secuhub.web.security

import tools.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import java.io.PrintWriter
import java.io.StringWriter

/**
 * [GateControlReauthInterceptor] 검증 — 2026-08-13 Opus 전체 리뷰 지적(웹 컨트롤러/인터셉터
 * 계층에 HTTP 레벨 테스트가 전무했다). 실제 상태를 바꾸는 게이트 제어 요청마다 재인증을
 * 강제하는 마지막 방어선이라 preHandle의 각 분기를 직접 검증한다. 전체 MockMvc 컨텍스트가
 * 아니라 [HandlerInterceptor.preHandle]만 단위로 떼어 검증하는 것으로 충분하다 — HTTP 서블릿
 * 객체는 Mockito로 대역한다.
 */
class GateControlReauthInterceptorTest {

    private val reauthService = mock(GateControlReauthService::class.java)
    private val objectMapper = ObjectMapper()

    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    private fun interceptor(reauthRequired: Boolean) =
        GateControlReauthInterceptor(
            SecuritySettingsProperties(gateControlReauthRequired = reauthRequired),
            reauthService,
            objectMapper,
        )

    private fun authenticateAs(username: String) {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(username, "N/A", emptyList())
    }

    @Test
    fun `재인증 설정이 꺼져 있으면 검증 없이 통과시킨다`() {
        val request = mock(HttpServletRequest::class.java)
        `when`(request.method).thenReturn("POST")
        val response = mock(HttpServletResponse::class.java)

        val passed = interceptor(reauthRequired = false).preHandle(request, response, Any())

        assertTrue(passed)
        verify(reauthService, never()).verify(anyString(), anyString())
    }

    @Test
    fun `GET 요청은 상태를 바꾸지 않으므로 재인증 없이 통과시킨다`() {
        val request = mock(HttpServletRequest::class.java)
        `when`(request.method).thenReturn("GET")
        val response = mock(HttpServletResponse::class.java)

        val passed = interceptor(reauthRequired = true).preHandle(request, response, Any())

        assertTrue(passed)
    }

    @Test
    fun `비밀번호가 일치하면 통과시킨다`() {
        val request = mock(HttpServletRequest::class.java)
        `when`(request.method).thenReturn("POST")
        `when`(request.getParameter("reauthPassword")).thenReturn("correct-pw")
        val response = mock(HttpServletResponse::class.java)
        authenticateAs("admin")
        `when`(reauthService.verify("admin", "correct-pw")).thenReturn(true)

        val passed = interceptor(reauthRequired = true).preHandle(request, response, Any())

        assertTrue(passed)
    }

    @Test
    fun `비밀번호가 틀리고 api 경로면 401 JSON을 응답하고 통과시키지 않는다`() {
        val request = mock(HttpServletRequest::class.java)
        `when`(request.method).thenReturn("POST")
        `when`(request.getParameter("reauthPassword")).thenReturn("wrong-pw")
        `when`(request.requestURI).thenReturn("/api/gate-control/command")
        val response = mock(HttpServletResponse::class.java)
        val writer = StringWriter()
        `when`(response.writer).thenReturn(PrintWriter(writer))
        authenticateAs("admin")
        `when`(reauthService.verify("admin", "wrong-pw")).thenReturn(false)

        val passed = interceptor(reauthRequired = true).preHandle(request, response, Any())

        assertFalse(passed)
        verify(response).status = HttpServletResponse.SC_UNAUTHORIZED
        assertTrue(writer.toString().contains("\"reauthRequired\":true"))
    }

    @Test
    fun `비밀번호가 틀리고 화면 경로면 Referer 경로로 리다이렉트하며 reauthError를 붙인다`() {
        val request = mock(HttpServletRequest::class.java)
        `when`(request.method).thenReturn("POST")
        `when`(request.getParameter("reauthPassword")).thenReturn("wrong-pw")
        `when`(request.requestURI).thenReturn("/gates/details/1/mode")
        `when`(request.getHeader("Referer")).thenReturn("https://host/gates/details/1/mode?foo=bar")
        val response = mock(HttpServletResponse::class.java)
        authenticateAs("admin")
        `when`(reauthService.verify("admin", "wrong-pw")).thenReturn(false)

        val passed = interceptor(reauthRequired = true).preHandle(request, response, Any())

        assertFalse(passed)
        // 코드 리뷰 지적(2026-08-14): Referer는 클라이언트가 완전히 제어하는 값이라 스킴/호스트를
        // 그대로 신뢰해 리다이렉트하면 오픈 리다이렉트가 된다 — 경로만 남기고 붙여야 한다.
        verify(response).sendRedirect("/gates/details/1/mode?reauthError=1")
    }

    @Test
    fun `Referer가 다른 호스트를 가리키면 오픈 리다이렉트를 막고 경로만 남긴다`() {
        val request = mock(HttpServletRequest::class.java)
        `when`(request.method).thenReturn("POST")
        `when`(request.getParameter("reauthPassword")).thenReturn("wrong-pw")
        `when`(request.requestURI).thenReturn("/gates/details/1/mode")
        `when`(request.getHeader("Referer")).thenReturn("https://evil.example.com/phishing")
        val response = mock(HttpServletResponse::class.java)
        authenticateAs("admin")
        `when`(reauthService.verify("admin", "wrong-pw")).thenReturn(false)

        val passed = interceptor(reauthRequired = true).preHandle(request, response, Any())

        assertFalse(passed)
        verify(response).sendRedirect("/phishing?reauthError=1")
    }

    @Test
    fun `Referer가 없으면 대시보드로 리다이렉트한다`() {
        val request = mock(HttpServletRequest::class.java)
        `when`(request.method).thenReturn("POST")
        `when`(request.getParameter("reauthPassword")).thenReturn(null)
        `when`(request.requestURI).thenReturn("/gates/details/1/mode")
        `when`(request.getHeader("Referer")).thenReturn(null)
        val response = mock(HttpServletResponse::class.java)
        authenticateAs("admin")

        val passed = interceptor(reauthRequired = true).preHandle(request, response, Any())

        assertFalse(passed)
        verify(response).sendRedirect("/dashboard?reauthError=1")
    }

    @Test
    fun `비밀번호가 비어있으면(mock 미스텁 기본값 false) 실패로 처리한다`() {
        // PUT/DELETE도 STATE_CHANGING_METHODS에 포함되므로 검증 대상이어야 한다.
        val request = mock(HttpServletRequest::class.java)
        `when`(request.method).thenReturn("PUT")
        `when`(request.getParameter("reauthPassword")).thenReturn(null)
        `when`(request.requestURI).thenReturn("/api/gate-control/reset")
        val response = mock(HttpServletResponse::class.java)
        val writer = StringWriter()
        `when`(response.writer).thenReturn(PrintWriter(writer))
        authenticateAs("admin")

        val passed = interceptor(reauthRequired = true).preHandle(request, response, Any())

        assertFalse(passed)
        assertEquals(true, writer.toString().contains("REJECTED"))
    }

    @Test
    fun `SecurityContext에 인증 정보가 없으면 비밀번호 검증 없이 즉시 거부한다`() {
        // SecurityContextHolder는 @AfterEach에서 매번 clearContext()되므로 authenticateAs()를
        // 호출하지 않으면 authentication == null인 상태를 그대로 재현한다.
        val request = mock(HttpServletRequest::class.java)
        `when`(request.method).thenReturn("POST")
        `when`(request.requestURI).thenReturn("/api/gate-control/command")
        val response = mock(HttpServletResponse::class.java)
        val writer = StringWriter()
        `when`(response.writer).thenReturn(PrintWriter(writer))

        val passed = interceptor(reauthRequired = true).preHandle(request, response, Any())

        assertFalse(passed)
        verify(reauthService, never()).verify(anyString(), anyString())
        verify(response).status = HttpServletResponse.SC_UNAUTHORIZED
    }

    @Test
    fun `인증되지 않은(authenticated=false) 토큰이면 비밀번호 검증 없이 즉시 거부한다`() {
        val request = mock(HttpServletRequest::class.java)
        `when`(request.method).thenReturn("POST")
        `when`(request.requestURI).thenReturn("/api/gate-control/command")
        val response = mock(HttpServletResponse::class.java)
        val writer = StringWriter()
        `when`(response.writer).thenReturn(PrintWriter(writer))
        // 2-인자 생성자는 Spring Security 관례상 "아직 인증되지 않음"(authenticated=false) 상태다.
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken("admin", "N/A")

        val passed = interceptor(reauthRequired = true).preHandle(request, response, Any())

        assertFalse(passed)
        verify(reauthService, never()).verify(anyString(), anyString())
    }

    @Test
    fun `익명 사용자(AnonymousAuthenticationToken)면 비밀번호 검증 없이 즉시 거부한다`() {
        // AnonymousAuthenticationToken은 isAuthenticated=true로 생성되므로, isAuthenticated 체크
        // 만으로는 걸러지지 않는다 — 타입 체크가 반드시 필요하다는 것을 검증하는 회귀 방지 테스트.
        val request = mock(HttpServletRequest::class.java)
        `when`(request.method).thenReturn("POST")
        `when`(request.requestURI).thenReturn("/api/gate-control/command")
        val response = mock(HttpServletResponse::class.java)
        val writer = StringWriter()
        `when`(response.writer).thenReturn(PrintWriter(writer))
        SecurityContextHolder.getContext().authentication =
            AnonymousAuthenticationToken("key", "anonymousUser", listOf(SimpleGrantedAuthority("ROLE_ANONYMOUS")))

        val passed = interceptor(reauthRequired = true).preHandle(request, response, Any())

        assertFalse(passed)
        verify(reauthService, never()).verify(anyString(), anyString())
    }
}
