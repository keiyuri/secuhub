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
 * 최초 로그인(`SecurityUserDetailsService` + `LoginAttemptService`)과 달리 여기서는 계정을
 * 잠그지 않는다 — 이미 세션 인증을 통과한 사용자의 2차 확인이라 계정 자체를 잠그면(로그인 잠금과
 * 별개로) 반복 오타 한 번으로 이미 로그인된 세션의 제어 기능 전체가 막혀 운영 대응에 지장이 크다.
 *
 * 대신 [GateControlReauthAttemptService]로 **짧은 쿨다운(1분)만** 적용한다(코드 리뷰 지적,
 * 2026-08-28) — 세션 탈취 공격자가 `reauthPassword`를 무제한 온라인 브루트포스로 시도할 수 있던
 * 문제를 막으면서도, 정상 운영자의 오타는 [LoginAttemptService]의 15분 잠금보다 훨씬 가벼운
 * 지연으로만 이어지게 한다. 잠금 확인과 시도 예약은 [GateControlReauthAttemptService.tryAcquire]
 * 하나로 원자적으로 처리한다 — 병렬 요청이 잠금 임계치를 우회하지 못하게 하기 위함이다(Codex 리뷰
 * 지적, 2026-08-28. 해당 클래스 KDoc 참고). 성공 시에는 그 성공을 발생시킨 호출의 티켓을 그대로
 * [GateControlReauthAttemptService.recordSuccess]에 넘긴다 — 사용자명만으로 성공을 신고하면 동시에
 * 진행 중인 다른(공격) 요청의 실패 이력까지 함께 사면돼 버리기 때문이다(Codex 적대적 리뷰 지적,
 * 2026-08-28. 해당 클래스 KDoc 참고).
 */
@Service
class GateControlReauthService(
    private val userDetailsService: UserDetailsService,
    private val passwordEncoder: PasswordEncoder,
    private val attemptService: GateControlReauthAttemptService,
) {
    private val logger = LoggerFactory.getLogger(GateControlReauthService::class.java)

    /** @return 비밀번호가 현재 사용자(username)의 것과 일치하면 true. 쿨다운 중이면 검증 없이 false. */
    fun verify(username: String, rawPassword: String?): Boolean {
        if (rawPassword.isNullOrBlank()) return false
        // tryAcquire가 "허용 여부 확인"과 "실패로 카운트 예약"을 한 번에 원자적으로 처리한다 —
        // 검증(비밀번호 해시 비교) 시작 전에 잠금 여부가 이미 확정되므로, 동시 요청이 각자
        // isLocked()를 통과한 뒤 병렬로 비밀번호를 시도하는 경로 자체가 없다.
        val ticket = attemptService.tryAcquire(username)
        if (ticket == null) {
            logger.warn("재인증 쿨다운 중입니다(반복 실패): {}", username)
            return false
        }
        val matched = try {
            val userDetails = userDetailsService.loadUserByUsername(username)
            passwordEncoder.matches(rawPassword, userDetails.password)
        } catch (ex: UsernameNotFoundException) {
            // 세션은 이미 인증돼 있는데 그 사이 계정이 삭제/비활성화된 극단적 경우 — 실패로 처리.
            logger.warn("재인증 대상 사용자를 찾을 수 없습니다: {}", username)
            false
        }
        // 실패는 tryAcquire가 이미 낙관적으로 카운트해 뒀으므로 별도 처리가 필요 없다 — 성공한
        // 경우에만 이 호출 자신의 티켓으로 그 예약을 되돌린다. 사용자명만으로 성공을 신고하면
        // 동시에 진행 중인 다른(공격) 요청의 실패 이력까지 함께 사면돼 버리므로 반드시 티켓을
        // 넘긴다(Codex 적대적 리뷰 지적, 2026-08-28).
        if (matched) attemptService.recordSuccess(ticket)
        return matched
    }
}
