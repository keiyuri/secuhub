package kr.co.securance.secuhub.config

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [SessionCookieSecurityWarner] 단위 테스트 — Spring 컨텍스트 없이 로그 경고 로직이 예외 없이
 * 동작하는지, `@Profile`이 로컬을 제외한 모든 환경에 적용되는지 확인한다(2026-08-28).
 *
 * Opus 재검증 지적(2026-08-28): 최초 버전은 `warnIfInsecure()`를 호출만 하고 아무것도 단언하지
 * 않아, 구현을 통째로 지워도 통과하는 무단언(smoke-only) 테스트였다. Logback [ListAppender]로
 * 실제 로그 이벤트를 캡처해 "경고가 남아야 할 때/남지 말아야 할 때"를 직접 검증하도록 고쳤다.
 */
class SessionCookieSecurityWarnerTest {

    private val logger = LoggerFactory.getLogger(SessionCookieSecurityWarner::class.java) as Logger
    private val appender = ListAppender<ILoggingEvent>()

    @BeforeEach
    fun attachAppender() {
        appender.start()
        logger.addAppender(appender)
    }

    @AfterEach
    fun detachAppender() {
        logger.detachAppender(appender)
        appender.stop()
    }

    @Test
    fun `secure=false, webLoginRequired=true면 WARN 로그를 정확히 한 건 남긴다`() {
        SessionCookieSecurityWarner(cookieSecure = false, webLoginRequired = true).warnIfInsecure()

        assertEquals(1, appender.list.size)
        assertEquals(Level.WARN, appender.list[0].level)
        assertTrue(appender.list[0].formattedMessage.contains("cookie.secure"))
    }

    @Test
    fun `secure=true면 경고를 남기지 않는다`() {
        SessionCookieSecurityWarner(cookieSecure = true, webLoginRequired = true).warnIfInsecure()

        assertTrue(appender.list.isEmpty())
    }

    @Test
    fun `webLoginRequired=false면 secure=false여도 경고하지 않는다`() {
        SessionCookieSecurityWarner(cookieSecure = false, webLoginRequired = false).warnIfInsecure()

        assertTrue(appender.list.isEmpty())
    }

    @Test
    fun `프로필은 local을 제외한 모든 프로필에 적용된다`() {
        val profile = SessionCookieSecurityWarner::class.java.getAnnotation(Profile::class.java)
        kotlin.test.assertTrue(profile != null)
        kotlin.test.assertContains(profile.value.toList(), "!local")
    }
}
