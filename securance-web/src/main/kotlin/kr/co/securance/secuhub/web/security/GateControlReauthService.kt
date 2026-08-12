package kr.co.securance.secuhub.web.security

import org.slf4j.LoggerFactory
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service

/**
 * `securance.security.gate-control-reauth-required=true`일 때 [GateControlReauthInterceptor]가
 * 호출하는 비밀번호 재확인 로직.
 *
 * 최초 로그인(`SecurityUserDetailsService` + `LoginAttemptService`)과 달리 여기서는 브루트포스
 * 잠금을 걸지 않는다 — 이미 세션 인증을 통과한 사용자의 2차 확인이라 계정 탈취 시나리오와
 * 무관하고, 잠금까지 적용하면 반복 오타 한 번으로 이미 로그인된 세션의 제어 기능 전체가
 * 잠겨버려(로그인 잠금과 별개로) 운영 중 게이트 대응이 막히는 부작용이 더 크다고 판단했다.
 */
@Service
class GateControlReauthService(
    private val userDetailsService: UserDetailsService,
    private val passwordEncoder: PasswordEncoder,
) {
    private val logger = LoggerFactory.getLogger(GateControlReauthService::class.java)

    /** @return 비밀번호가 현재 사용자(username)의 것과 일치하면 true. */
    fun verify(username: String, rawPassword: String?): Boolean {
        if (rawPassword.isNullOrBlank()) return false
        return try {
            val userDetails = userDetailsService.loadUserByUsername(username)
            passwordEncoder.matches(rawPassword, userDetails.password)
        } catch (ex: UsernameNotFoundException) {
            // 세션은 이미 인증돼 있는데 그 사이 계정이 삭제/비활성화된 극단적 경우 — 실패로 처리.
            logger.warn("재인증 대상 사용자를 찾을 수 없습니다: {}", username)
            false
        }
    }
}
