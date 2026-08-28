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
    private val attemptService = GateControlReauthAttemptService()
    private val service = GateControlReauthService(userDetailsService, passwordEncoder, attemptService)

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

    @Test
    fun `반복 실패 후에는 비밀번호가 맞아도 쿨다운으로 거부된다`() {
        // 회귀 방지 테스트(코드 리뷰 지적, 2026-08-28): 세션 탈취 공격자가 reauthPassword를 무제한
        // 온라인 브루트포스로 시도할 수 있던 문제 — MAX_ATTEMPTS번 실패하면 올바른 비밀번호를
        // 넣어도(공격자가 마침내 알아냈더라도) 쿨다운 동안은 UserDetailsService 조회조차 하지 않고
        // 즉시 거부해야 한다.
        stubUser("tester", "hashed")
        `when`(passwordEncoder.matches("wrong-pw", "hashed")).thenReturn(false)
        `when`(passwordEncoder.matches("raw-pw", "hashed")).thenReturn(true)

        repeat(GateControlReauthAttemptService.MAX_ATTEMPTS) {
            assertFalse(service.verify("tester", "wrong-pw"))
        }

        assertFalse(service.verify("tester", "raw-pw"))
    }

    @Test
    fun `실패 없이 곧바로 성공하면 카운트가 초기화된다`() {
        stubUser("tester", "hashed")
        `when`(passwordEncoder.matches("raw-pw", "hashed")).thenReturn(true)

        assertTrue(service.verify("tester", "raw-pw"))

        // 이전 실패가 전혀 없었던 성공(예약 1건 → 성공으로 정확히 0)이므로 완전히 초기화된다 —
        // 곧바로 다시 MAX_ATTEMPTS번 시도할 수 있어야 한다.
        assertTrue(service.verify("tester", "raw-pw"))
    }

    @Test
    fun `임계치에 걸리는 성공 이후에도 곧바로 재시도할 수는 없다(Codex 재지적 회귀)`() {
        // Codex 재지적 회귀 테스트: 이전 구현은 "잠금을 발동시킨 바로 그 시도가 성공하면" 잠금까지
        // 포함해 전체를 초기화했다 — 이 조건은 병렬 공격에서 가장 나중에 예약된 요청이 먼저
        // 성공하는 경우와 구분할 수 없어(GateControlReauthAttemptServiceTest의 "P1 회귀" 참고),
        // 공격자가 매 배치의 마지막 한 건만 정답을 흘려 넣어 잠금 자체를 무력화할 수 있었다. 이제는
        // 앞선 실패가 하나라도 남아 있으면(이 시나리오는 MAX_ATTEMPTS-1건) 성공해도 잠금이 유지된다.
        stubUser("tester", "hashed")
        `when`(passwordEncoder.matches("wrong-pw", "hashed")).thenReturn(false)
        `when`(passwordEncoder.matches("raw-pw", "hashed")).thenReturn(true)

        repeat(GateControlReauthAttemptService.MAX_ATTEMPTS - 1) {
            assertFalse(service.verify("tester", "wrong-pw"))
        }
        // 잠금 임계치를 채우는 이 시도 자체는 비밀번호가 맞아 true를 반환하지만(비밀번호 검증은
        // 여전히 정상 수행됨), 그 직전까지 쌓인 MAX_ATTEMPTS-1건의 실패는 사면되지 않는다.
        assertTrue(service.verify("tester", "raw-pw"))

        // 방금 성공했더라도 쿨다운(1분)이 끝나지 않았으므로 바로 다음 시도는 비밀번호와 무관하게
        // 거부돼야 한다.
        assertFalse(service.verify("tester", "raw-pw"))
    }
}
