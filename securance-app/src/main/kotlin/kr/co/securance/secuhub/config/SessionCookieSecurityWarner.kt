package kr.co.securance.secuhub.config

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * 세션 쿠키 `secure` 속성 누락 경고(코드 리뷰 지적, 2026-08-28).
 *
 * `application.yml`의 `server.servlet.session.cookie` 주석은 "TLS를 앞단에 두면 secure=true를
 * 반드시 켤 것"이라고 안내하지만, 그걸 강제하거나 기동 시점에 검증하는 코드는 없었다 —
 * [RabbitCredentialGuard]류의 "기동 시점 강제 검증" 패턴이 이 설정에는 빠져 있었다.
 *
 * `secure=true`를 여기서 강제(기동 실패)할 수는 없다 — 이 프로젝트는 여전히 TLS 종료 지점 없이
 * 평문 HTTP로 배포되는 경우가 있고, 그 경우 강제하면 브라우저가 쿠키를 보내지 않아 로그인 자체가
 * 깨진다(리버스 프록시가 TLS를 종료하는지 애플리케이션은 알 수 없다). 대신 로그인 기능이 켜진
 * 채(`securance.security.web-login-required=true`, 기본값) `local` 프로필이 아닌 환경에서
 * `secure=false`로 기동되면 경고 로그를 남겨, 운영자가 리버스 프록시 뒤에 TLS를 두고도 이 설정을
 * 깜빡 켜지 않은 경우를 조용히 지나치지 않게 한다.
 */
@Component
@Profile("!local")
class SessionCookieSecurityWarner(
    @Value("\${server.servlet.session.cookie.secure:false}") private val cookieSecure: Boolean,
    @Value("\${securance.security.web-login-required:true}") private val webLoginRequired: Boolean,
) {
    private val logger = LoggerFactory.getLogger(SessionCookieSecurityWarner::class.java)

    @PostConstruct
    fun warnIfInsecure() {
        if (webLoginRequired && !cookieSecure) {
            logger.warn(
                "server.servlet.session.cookie.secure가 꺼져 있습니다. 이 인스턴스가 TLS(HTTPS) 뒤에서 " +
                    "서비스된다면 세션 쿠키가 평문으로 전송되어 세션 탈취(스니핑) 위험이 있습니다. " +
                    "리버스 프록시로 TLS를 종료하고 있다면 server.servlet.session.cookie.secure=true를 켜세요. " +
                    "평문 HTTP로만 서비스 중이라면 이 경고는 무시해도 됩니다.",
            )
        }
    }
}
