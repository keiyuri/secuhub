package kr.co.securance.secuhub.web.security

import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.security.authentication.event.AuthenticationSuccessEvent
import org.springframework.security.core.session.SessionRegistry
import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

/**
 * [SecurityConfig]의 동시 세션 제한(maximumSessions=1)에 걸릴 로그인을 **새로 로그인하는 시점**에
 * 감지해 감사 로그를 남긴다.
 *
 * [2026-08-14 재검토] 밀려나는(만료되는) 세션 쪽에서 로그를 남기는 방식([EvictedSessionRedirectStrategy]
 * 참고)은 (1) 공격자가 아니라 피해자의 IP만 남고, (2) 피해자가 그 탭으로 다시 돌아오지 않으면 로그
 * 자체가 남지 않는 근본적 한계가 있었다. 이 리스너는 `AuthenticationSuccessEvent`(비밀번호 검증은
 * 끝났지만 `ConcurrentSessionControlAuthenticationStrategy`가 기존 세션을 실제로 밀어내기 *이전*에
 * 발행됨) 시점에 [SessionRegistry]를 조회한다 — 이 시점에 조회되는 세션은 전부 "이번 로그인 이전부터"
 * 존재하던 것이므로, 하나라도 있으면 이번 로그인이 곧 기존 세션을 밀어낸다는 뜻이다. 그 순간의
 * 요청 IP(=지금 로그인을 시도하는 쪽의 IP)로 경고를 남기므로, 피해자의 후속 행동과 무관하게
 * 항상 기록된다.
 *
 * `SessionRegistry` 조회는 `principal.equals()`에 의존한다 — [SecurityUserDetailsService]가
 * 반환하는 `org.springframework.security.core.userdetails.User`는 username 기준으로 equals/hashCode를
 * 구현하므로, 로그인마다 새로 생성되는 인스턴스라도 동일 계정이면 정상적으로 같은 키로 조회된다.
 */
@Component
class ConcurrentLoginAuditListener(
    private val sessionRegistry: SessionRegistry,
) {
    private val logger = LoggerFactory.getLogger(ConcurrentLoginAuditListener::class.java)

    @EventListener
    fun onSuccess(event: AuthenticationSuccessEvent) {
        val principal = event.authentication.principal ?: return
        val existingSessions = sessionRegistry.getAllSessions(principal, false)
        if (existingSessions.isEmpty()) return

        val remoteAddr = (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)
            ?.request
            ?.remoteAddr
            ?: "unknown"
        logger.warn(
            "동시 로그인 감지 — 계정: {}, 기존 활성 세션 {}개를 대체하는 새 로그인 요청 IP: {}",
            event.authentication.name,
            existingSessions.size,
            remoteAddr,
        )
    }
}
