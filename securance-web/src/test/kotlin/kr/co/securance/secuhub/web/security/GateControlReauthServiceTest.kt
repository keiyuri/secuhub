package kr.co.securance.secuhub.web.security

import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.crypto.password.PasswordEncoder
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [GateControlReauthService] 검증 — `securance.security.gate-control-reauth-required=true`일 때
 * [GateControlReauthInterceptor]가 위임하는 비밀번호 검증 로직. 잘못된/빈 비밀번호로 게이트 제어가
 * 통과되면 재인증 기능 자체가 무력화되므로 실패 경로를 먼저 검증한다.
 */
class GateControlReauthServiceTest {

    private val userDetailsService = mock(UserDetailsService::class.java)
    private val passwordEncoder = mock(PasswordEncoder::class.java)
    private val service = GateControlReauthService(userDetailsService, passwordEncoder)

    private fun stubUser(username: String, passwordHash: String) {
        `when`(userDetailsService.loadUserByUsername(username)).thenReturn(
            User.builder().username(username).password(passwordHash).authorities("ROLE_VIEW").build(),
        )
    }

    @Test
    fun `비밀번호가 일치하면 true를 반환한다`() {
        stubUser("tester", "hashed")
        `when`(passwordEncoder.matches("raw-pw", "hashed")).thenReturn(true)

        assertTrue(service.verify("tester", "raw-pw"))
    }

    @Test
    fun `비밀번호가 일치하지 않으면 false를 반환한다`() {
        stubUser("tester", "hashed")
        `when`(passwordEncoder.matches("wrong-pw", "hashed")).thenReturn(false)

        assertFalse(service.verify("tester", "wrong-pw"))
    }

    @Test
    fun `빈 비밀번호는 UserDetailsService를 조회하지 않고 즉시 실패한다`() {
        assertFalse(service.verify("tester", ""))
        assertFalse(service.verify("tester", null))
    }

    @Test
    fun `세션 인증 이후 계정이 사라졌으면 실패로 처리한다`() {
        `when`(userDetailsService.loadUserByUsername("ghost")).thenThrow(UsernameNotFoundException("no such user"))

        assertFalse(service.verify("ghost", "any-pw"))
    }
}
