package kr.co.securance.secuhub.web.security

import kr.co.securance.secuhub.domain.entity.AppUser
import kr.co.securance.secuhub.domain.repository.AppUserRepository
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.security.core.userdetails.User
import java.util.Optional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecurityUserDetailsServiceTest {

    private fun fakeUser(userId: String = "tester", passwordHash: String = "hash", useYn: Boolean = true) =
        AppUser(
            userId = userId,
            passwordHash = passwordHash,
            userName = "테스터",
            useYn = useYn,
            authView = true,
            authControl = false,
            authAdmin = false,
        )

    @Test
    fun `loadUserByUsername은 저장된 해시와 권한을 그대로 UserDetails에 반영한다`() {
        val repository = mock(AppUserRepository::class.java)
        `when`(repository.findById("tester")).thenReturn(Optional.of(fakeUser(passwordHash = "stored-hash")))

        val service = SecurityUserDetailsService(repository, LoginAttemptService())
        val userDetails = service.loadUserByUsername("tester")

        assertEquals("stored-hash", userDetails.password)
        assertTrue(userDetails.isEnabled)
        assertTrue(userDetails.authorities.any { it.authority == "ROLE_VIEW" })
    }

    @Test
    fun `useYn이 N이면 계정이 비활성화된다`() {
        val repository = mock(AppUserRepository::class.java)
        `when`(repository.findById("tester")).thenReturn(Optional.of(fakeUser(useYn = false)))

        val service = SecurityUserDetailsService(repository, LoginAttemptService())

        assertFalse(service.loadUserByUsername("tester").isEnabled)
    }

    @Test
    fun `updatePassword는 새 해시를 저장하고 갱신된 UserDetails를 반환한다`() {
        // 회귀 방지 테스트: LegacyAwarePasswordEncoder가 레거시 평문 로그인 성공 후
        // upgradeEncoding=true를 반환하면, Spring Security가 이 메서드를 호출해 BCrypt로
        // 재해시된 값을 즉시 tb_users에 반영해야 한다(별도 마이그레이션 배치 불필요).
        val entity = fakeUser(passwordHash = "legacy-plain")
        val repository = mock(AppUserRepository::class.java)
        `when`(repository.findById("tester")).thenReturn(Optional.of(entity))

        val service = SecurityUserDetailsService(repository, LoginAttemptService())
        val existing = User.builder().username("tester").password("legacy-plain").authorities("ROLE_VIEW").build()

        val updated = service.updatePassword(existing, "\$2a\$10\$newBcryptHash")

        assertEquals("\$2a\$10\$newBcryptHash", updated.password)
        assertEquals("\$2a\$10\$newBcryptHash", entity.passwordHash, "엔티티 자체의 passwordHash도 갱신되어야 한다")
        verify(repository).save(entity)
    }
}
