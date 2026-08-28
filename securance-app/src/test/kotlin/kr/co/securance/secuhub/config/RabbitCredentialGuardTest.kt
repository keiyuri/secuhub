package kr.co.securance.secuhub.config

import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Profile
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [RabbitCredentialGuard] 단위 테스트 — Spring 컨텍스트 없이 `verify()`의 순수 검증 로직과
 * `@Profile` 어노테이션 값을 직접 확인한다(2026-08-28 코드 리뷰 지적 대응).
 */
class RabbitCredentialGuardTest {

    @Test
    fun `enabled=true이고 자격증명이 둘 다 guest가 아니면 통과한다`() {
        RabbitCredentialGuard(enabled = true, username = "real-user", password = "real-pass").verify()
    }

    @Test
    fun `enabled=false면 자격증명이 guest여도 통과한다`() {
        RabbitCredentialGuard(enabled = false, username = "guest", password = "guest").verify()
    }

    @Test
    fun `enabled=true인데 username만 guest면 기동을 막는다`() {
        val ex = assertFailsWith<IllegalStateException> {
            RabbitCredentialGuard(enabled = true, username = "guest", password = "real-pass").verify()
        }
        assertContains(ex.message.orEmpty(), "SECURANCE_RABBITMQ_USER")
    }

    @Test
    fun `enabled=true인데 password만 guest면 기동을 막는다`() {
        assertFailsWith<IllegalStateException> {
            RabbitCredentialGuard(enabled = true, username = "real-user", password = "guest").verify()
        }
    }

    @Test
    fun `프로필은 local을 제외한 모든 프로필에 적용된다(prod 리터럴 고정 금지)`() {
        // 코드 리뷰 지적: @Profile("prod")로 고정하면 프로필 플래그를 빠뜨린 배포(기본/무프로필)에서
        // 가드가 아예 동작하지 않는다 — "!local"로 바뀌었는지를 회귀 테스트로 고정한다.
        val profile = RabbitCredentialGuard::class.java.getAnnotation(Profile::class.java)
        assertTrue(profile != null, "@Profile 어노테이션이 있어야 한다")
        assertContains(profile.value.toList(), "!local")
    }
}
