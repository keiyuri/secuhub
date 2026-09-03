package kr.co.securance.secuhub.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Profile

/**
 * [RabbitCredentialGuard] 검증(2026-09-03 코드 리뷰 지적).
 *
 * `@Profile("prod")`로 한정하면 스테이징 등 이름이 다른 운영 유사 프로필이나, 프로필을 아예
 * 지정하지 않은 기본 기동 상태에서는 가드가 전혀 동작하지 않아 guest/guest 그대로 기동이
 * 허용된다 — `"local"`(로컬 개발 편의) 프로필에서만 제외하도록 고쳤는지 회귀 검증한다.
 */
class RabbitCredentialGuardTest {

    @Test
    fun `local 프로필에서만 제외하고 그 외에는 항상 검사한다`() {
        val profile = RabbitCredentialGuard::class.java.getAnnotation(Profile::class.java)

        assertThat(profile.value).containsExactly("!local")
    }

    @Test
    fun `연동이 꺼져 있으면 자격증명이 guest여도 기동을 막지 않는다`() {
        RabbitCredentialGuard(enabled = false, username = "guest", password = "guest").verify()
    }

    @Test
    fun `연동이 켜져 있고 둘 다 커스텀 자격증명이면 기동을 막지 않는다`() {
        RabbitCredentialGuard(enabled = true, username = "app-user", password = "s3cret").verify()
    }

    @Test
    fun `연동이 켜져 있는데 둘 다 guest면 기동을 막는다`() {
        assertThatThrownBy {
            RabbitCredentialGuard(enabled = true, username = "guest", password = "guest").verify()
        }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `연동이 켜져 있는데 하나만 guest여도 기동을 막는다`() {
        assertThatThrownBy {
            RabbitCredentialGuard(enabled = true, username = "guest", password = "s3cret").verify()
        }.isInstanceOf(IllegalStateException::class.java)

        assertThatThrownBy {
            RabbitCredentialGuard(enabled = true, username = "app-user", password = "guest").verify()
        }.isInstanceOf(IllegalStateException::class.java)
    }
}
