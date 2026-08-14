package kr.co.securance.secuhub.web.security

import org.slf4j.LoggerFactory
import org.springframework.security.web.session.SessionInformationExpiredEvent
import org.springframework.security.web.session.SessionInformationExpiredStrategy
import org.springframework.security.web.session.SimpleRedirectSessionInformationExpiredStrategy

/**
 * [SecurityConfig]의 동시 세션 제한(maximumSessions=1, maxSessionsPreventsLogin=false)에 의해
 * 기존 세션이 새 로그인으로 밀려난 뒤, 그 브라우저 탭이 **다음 요청을 보낼 때** 호출된다.
 *
 * 역할은 딱 하나 — 밀려난 사용자를 "다른 곳에서 로그인해 세션이 끊겼다"는 안내와 함께 로그인
 * 화면으로 돌려보내는 것(운영 UX)이다. 침입 탐지(감사 로그)는 이 클래스의 책임이 아니다:
 * [2026-08-14 재검토] 이 지점에서 로그를 남기면 (1) 기록되는 IP가 공격자가 아니라 피해자의 IP이고,
 * (2) 피해자가 이 탭으로 돌아오지 않으면 로그 자체가 영원히 남지 않는다 — 탐지 목적으로는
 * 신뢰할 수 없다. 실제 감사 로그는 새로 로그인하는 시점에 동작하는
 * [ConcurrentLoginAuditListener]가 담당한다.
 */
class EvictedSessionRedirectStrategy(
    redirectUrl: String = "/login?expired",
) : SessionInformationExpiredStrategy {

    private val logger = LoggerFactory.getLogger(EvictedSessionRedirectStrategy::class.java)
    private val delegate = SimpleRedirectSessionInformationExpiredStrategy(redirectUrl)

    override fun onExpiredSessionDetected(event: SessionInformationExpiredEvent) {
        // 보안 감사용이 아닌 운영 참고용 로그 — 이 사용자가 왜 갑자기 로그인 화면으로 튕겼는지
        // 문의가 들어왔을 때 원인을 빠르게 확인하기 위함이다.
        logger.info("세션 {}이(가) 동시 세션 제한으로 만료되어 로그인 화면으로 리다이렉트합니다.", event.sessionInformation.sessionId)
        delegate.onExpiredSessionDetected(event)
    }
}
