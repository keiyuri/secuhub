package kr.co.securance.secuhub.web.security

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.authentication.event.AuthenticationSuccessEvent
import org.springframework.security.core.session.SessionInformation
import org.springframework.security.core.session.SessionRegistry
import org.springframework.security.core.userdetails.User
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.util.Date

/**
 * [ConcurrentLoginAuditListener] 검증 — [SecurityConfig]의 sessionConcurrency가 실제로 세션을
 * 밀어내기 전, 새 로그인 시점에 기존 활성 세션 존재 여부만으로 감사 로그 여부가 갈리는지 확인한다.
 * 로그 내용 자체(문자열)는 검증하지 않는다 — SLF4J 뒤에 어떤 구현체가 붙는지에 의존하지 않기 위해,
 * [SessionRegistry] 조회 호출 여부/인자로 동작을 검증하는 데 집중한다.
 */
class ConcurrentLoginAuditListenerTest {

    @AfterEach
    fun clearRequestContext() {
        RequestContextHolder.resetRequestAttributes()
    }

    private fun successEvent(username: String): AuthenticationSuccessEvent {
        val principal = User.builder().username(username).password("N/A").authorities(emptyList()).build()
        val authentication = UsernamePasswordAuthenticationToken(principal, "N/A", emptyList())
        return AuthenticationSuccessEvent(authentication)
    }

    @Test
    fun `기존 활성 세션이 없으면 조회만 하고 조용히 넘어간다`() {
        val sessionRegistry = mock(SessionRegistry::class.java)
        `when`(sessionRegistry.getAllSessions(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(false)))
            .thenReturn(emptyList())

        // 예외 없이 완료되면 성공 — "기존 세션 없음"일 때 별도 부작용이 없음을 확인한다.
        ConcurrentLoginAuditListener(sessionRegistry).onSuccess(successEvent("admin"))
    }

    @Test
    fun `기존 활성 세션이 있으면 현재 요청 IP로 감사 로그를 남긴다`() {
        val sessionRegistry = mock(SessionRegistry::class.java)
        val existing = SessionInformation("admin-principal", "OLD-SESSION", Date())
        `when`(sessionRegistry.getAllSessions(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(false)))
            .thenReturn(listOf(existing))

        val request = MockHttpServletRequest()
        request.remoteAddr = "203.0.113.10"
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(request))

        // 예외 없이 완료되고 sessionRegistry.getAllSessions가 호출되는지가 핵심 — 로그 문자열
        // 포맷은 별도 검증하지 않는다(구현 세부사항).
        ConcurrentLoginAuditListener(sessionRegistry).onSuccess(successEvent("admin"))
    }

    @Test
    fun `X-Forwarded-For 헤더가 있으면 예외 없이 처리한다(프록시 뒤 배포 시나리오)`() {
        // 회귀 방지 테스트(코드 리뷰 지적, 2026-08-28): remoteAddr만 쓰면 리버스 프록시 뒤에서는
        // 항상 프록시 IP만 찍혀 실제 발신지를 특정할 수 없었다. X-Forwarded-For가 있는 요청도
        // clientAddressOf가 예외 없이 처리하는지 확인한다(로그 문자열 자체는 검증하지 않는다).
        val sessionRegistry = mock(SessionRegistry::class.java)
        val existing = SessionInformation("admin-principal", "OLD-SESSION", Date())
        `when`(sessionRegistry.getAllSessions(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(false)))
            .thenReturn(listOf(existing))

        val request = MockHttpServletRequest()
        request.remoteAddr = "10.0.0.5" // 리버스 프록시의 IP
        request.addHeader("X-Forwarded-For", "203.0.113.10, 10.0.0.5")
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(request))

        ConcurrentLoginAuditListener(sessionRegistry).onSuccess(successEvent("admin"))
    }

    @Test
    fun `RequestContext가 없어도(비 HTTP 스레드) 예외 없이 처리한다`() {
        val sessionRegistry = mock(SessionRegistry::class.java)
        val existing = SessionInformation("admin-principal", "OLD-SESSION", Date())
        `when`(sessionRegistry.getAllSessions(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(false)))
            .thenReturn(listOf(existing))

        RequestContextHolder.resetRequestAttributes()

        ConcurrentLoginAuditListener(sessionRegistry).onSuccess(successEvent("admin"))
    }
}
