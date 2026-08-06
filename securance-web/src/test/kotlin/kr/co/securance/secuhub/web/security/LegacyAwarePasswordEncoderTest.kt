package kr.co.securance.secuhub.web.security

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LegacyAwarePasswordEncoderTest {

    private val encoder = LegacyAwarePasswordEncoder()

    @Test
    fun `BCrypt 해시는 정상적으로 검증된다`() {
        val hash = BCryptPasswordEncoder().encode("secret1234")

        assertTrue(encoder.matches("secret1234", hash))
        assertFalse(encoder.matches("wrong-password", hash))
    }

    @Test
    fun `레거시 평문 저장값도 로그인은 성공한다`() {
        // 회귀 방지 테스트: 순수 BCryptPasswordEncoder였다면 저장값이 해시 포맷이 아니므로
        // 예외 없이 항상 false(로그인 불가)를 반환했을 것이다.
        assertTrue(encoder.matches("plainSecret1", "plainSecret1"))
        assertFalse(encoder.matches("wrong-password", "plainSecret1"))
    }

    @Test
    fun `BCrypt 해시는 재해시가 필요없다고 판단한다`() {
        val hash = BCryptPasswordEncoder().encode("secret1234")

        assertFalse(encoder.upgradeEncoding(hash))
    }

    @Test
    fun `레거시 평문값은 재해시가 필요하다고 판단한다`() {
        // upgradeEncoding=true가 되어야 SecurityUserDetailsService.updatePassword가
        // 로그인 성공 직후 BCrypt로 자동 승격/저장한다.
        assertTrue(encoder.upgradeEncoding("plainSecret1"))
    }

    @Test
    fun `encode는 항상 BCrypt 해시를 생성한다`() {
        val hash = encoder.encode("secret1234")

        assertFalse(encoder.upgradeEncoding(hash), "encode() 결과는 다시 upgrade 대상이 되면 안 된다")
        assertTrue(encoder.matches("secret1234", hash))
    }
}
