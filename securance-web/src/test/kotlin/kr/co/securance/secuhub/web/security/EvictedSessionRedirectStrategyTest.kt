package kr.co.securance.secuhub.web.security

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.security.core.session.SessionInformation
import org.springframework.security.core.userdetails.User
import org.springframework.security.web.session.SessionInformationExpiredEvent

/**
 * [EvictedSessionRedirectStrategy] 검증 — 밀려난 세션을 안내 문구와 함께 로그인 화면으로
 * 리다이렉트하는 위임(delegate) 동작만 확인한다(감사 로그는 [ConcurrentLoginAuditListener]의
 * 책임이라 이 클래스에는 그 책임을 검증하는 테스트가 없다).
 */
class EvictedSessionRedirectStrategyTest {

    @Test
    fun `principal이 UserDetails이면 로그인 화면으로 expired 파라미터와 함께 리다이렉트한다`() {
        val principal = User.builder().username("admin").password("N/A").authorities(emptyList()).build()
        val sessionInformation = SessionInformation(principal, "SESSION-1", java.util.Date())
        val request = mockRequest()
        val response = mockResponse()
        val event = SessionInformationExpiredEvent(sessionInformation, request, response)

        EvictedSessionRedirectStrategy().onExpiredSessionDetected(event)

        verify(response).sendRedirect("/login?expired")
    }

    @Test
    fun `principal이 UserDetails가 아니어도(문자열 등) 예외 없이 리다이렉트한다`() {
        val sessionInformation = SessionInformation("raw-principal", "SESSION-2", java.util.Date())
        val request = mockRequest()
        val response = mockResponse()
        val event = SessionInformationExpiredEvent(sessionInformation, request, response)

        EvictedSessionRedirectStrategy().onExpiredSessionDetected(event)

        verify(response).sendRedirect("/login?expired")
    }

    // DefaultRedirectStrategy(SimpleRedirectSessionInformationExpiredStrategy 내부에서 위임)가
    // request.getContextPath() + url을 response.encodeRedirectURL()에 넘긴 결과를 그대로
    // sendRedirect에 전달한다 — 목(mock)이 두 메서드 모두 스텁되지 않으면 "null/login?expired" ->
    // encodeRedirectURL이 null을 반환해 sendRedirect(null)이 호출되는 것으로 관찰된다.
    private fun mockRequest(): HttpServletRequest {
        val request = mock(HttpServletRequest::class.java)
        `when`(request.contextPath).thenReturn("")
        return request
    }

    private fun mockResponse(): HttpServletResponse {
        val response = mock(HttpServletResponse::class.java)
        `when`(response.encodeRedirectURL(org.mockito.ArgumentMatchers.anyString()))
            .thenAnswer { it.getArgument<String>(0) }
        return response
    }
}
