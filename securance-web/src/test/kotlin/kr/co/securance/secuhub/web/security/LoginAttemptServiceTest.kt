package kr.co.securance.secuhub.web.security

import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent
import org.springframework.security.authentication.event.AuthenticationSuccessEvent
import org.springframework.security.core.Authentication
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoginAttemptServiceTest {

    private fun failureEvent(username: String) =
        AuthenticationFailureBadCredentialsEvent(
            UsernamePasswordAuthenticationToken.unauthenticated(username, "wrong"),
            BadCredentialsException("bad credentials"),
        )

    private fun successEvent(username: String): AuthenticationSuccessEvent {
        val authentication = mock(Authentication::class.java)
        `when`(authentication.name).thenReturn(username)
        return AuthenticationSuccessEvent(authentication)
    }

    @Test
    fun `MAX_ATTEMPTS 미만 실패는 잠기지 않는다`() {
        val service = LoginAttemptService()

        repeat(LoginAttemptService.MAX_ATTEMPTS - 1) { service.onFailure(failureEvent("alice")) }

        assertFalse(service.isLocked("alice"))
    }

    @Test
    fun `MAX_ATTEMPTS번 실패하면 계정이 잠긴다`() {
        val service = LoginAttemptService()

        repeat(LoginAttemptService.MAX_ATTEMPTS) { service.onFailure(failureEvent("bob")) }

        assertTrue(service.isLocked("bob"))
    }

    @Test
    fun `로그인 성공 시 실패 카운트가 초기화된다`() {
        val service = LoginAttemptService()

        repeat(LoginAttemptService.MAX_ATTEMPTS - 1) { service.onFailure(failureEvent("carol")) }
        service.onSuccess(successEvent("carol"))
        service.onFailure(failureEvent("carol"))

        assertFalse(service.isLocked("carol"), "성공 이후 카운트가 리셋되지 않으면 1번의 추가 실패로 잠겨서는 안 된다")
    }

    @Test
    fun `잠금 시간이 지나면 자동으로 해제된다`() {
        val fixed = Instant.parse("2026-01-01T00:00:00Z")
        val mutableClock = MutableClock(fixed)
        val service = LoginAttemptService(mutableClock)

        repeat(LoginAttemptService.MAX_ATTEMPTS) { service.onFailure(failureEvent("dave")) }
        assertTrue(service.isLocked("dave"))

        mutableClock.instant = fixed.plus(LoginAttemptService.LOCK_DURATION).plusSeconds(1)

        assertFalse(service.isLocked("dave"))
    }

    @Test
    fun `가짜 아이디로 추적 맵을 가득 채워도 실제 계정의 실패는 계속 집계된다`() {
        // 적대적 리뷰에서 지적된 우회 시나리오 재현: 공격자가 존재하지 않는 아이디 MAX_TRACKED_USERNAMES개를
        // 한 번씩 실패시켜 맵을 가득 채운 뒤에도, 그 이후 등장하는 실제 계정("victim")의 실패는
        // 여전히 카운트되어 MAX_ATTEMPTS번째에 잠겨야 한다(예전에는 상한 도달 후 신규 아이디를
        // 통째로 무시해 이 시점부터 브루트포스 방어가 완전히 무력화됐다).
        val service = LoginAttemptService()

        repeat(LoginAttemptService.MAX_TRACKED_USERNAMES) { i -> service.onFailure(failureEvent("attacker-noise-$i")) }

        repeat(LoginAttemptService.MAX_ATTEMPTS) { service.onFailure(failureEvent("victim")) }

        assertTrue(service.isLocked("victim"), "맵 포화 공격 이후에도 실제 계정은 정상적으로 잠겨야 한다")
    }

    /** 잠금 해제 시각 경과를 시뮬레이션하기 위한 가변 [Clock]. */
    private class MutableClock(var instant: Instant) : Clock() {
        override fun instant(): Instant = instant
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?): Clock = this
    }
}
