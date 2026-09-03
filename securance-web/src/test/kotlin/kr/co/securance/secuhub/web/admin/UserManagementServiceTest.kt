package kr.co.securance.secuhub.web.admin

import kr.co.securance.secuhub.domain.entity.AppUser
import kr.co.securance.secuhub.domain.repository.AppUserRepository
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.security.crypto.password.PasswordEncoder
import java.util.Optional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * [UserManagementService] 검증 — 전체 프로젝트 재감사(2026-08-10)에서 지적된 항목 중
 * "물리 제어/계정 관리 경로에 테스트가 전혀 없다"는 문제를 해소하기 위한 최소 커버리지.
 * 특히 [UserManagementService.delete]의 "로그인 중인 계정 삭제 금지" 가드는 실수로 깨지면
 * 관리자가 스스로를 잠글 수 있는 항목이라 우선 검증한다.
 */
class UserManagementServiceTest {

    private fun user(
        userId: String = "tester",
        passwordHash: String = "hashed",
        userName: String = "테스터",
        useYn: Boolean = true,
        authView: Boolean = true,
        authControl: Boolean = false,
        authAdmin: Boolean = false,
    ) = AppUser(
        userId = userId,
        passwordHash = passwordHash,
        userName = userName,
        useYn = useYn,
        authView = authView,
        authControl = authControl,
        authAdmin = authAdmin,
    )

    @Test
    fun `현재 로그인 중인 계정과 동일한 userId는 삭제를 거부한다`() {
        val userRepository = mock(AppUserRepository::class.java)
        val passwordEncoder = mock(PasswordEncoder::class.java)
        val service = UserManagementService(userRepository, passwordEncoder)

        val ex = assertThrows<IllegalArgumentException> {
            service.delete(userId = "admin", currentUserId = "admin")
        }

        assertEquals("로그인 중인 계정은 삭제할 수 없습니다.", ex.message)
        verify(userRepository, never()).deleteById(org.mockito.ArgumentMatchers.anyString())
    }

    @Test
    fun `다른 계정으로 로그인 중이면 삭제를 허용한다`() {
        val userRepository = mock(AppUserRepository::class.java)
        val passwordEncoder = mock(PasswordEncoder::class.java)
        val service = UserManagementService(userRepository, passwordEncoder)

        service.delete(userId = "target", currentUserId = "admin")

        verify(userRepository).deleteById("target")
    }

    @Test
    fun `currentUserId가 null(비인증 컨텍스트)이어도 삭제를 허용한다`() {
        // SecurityContextHolder.getContext().authentication이 없는 경로(배치/테스트 등) 방어.
        val userRepository = mock(AppUserRepository::class.java)
        val passwordEncoder = mock(PasswordEncoder::class.java)
        val service = UserManagementService(userRepository, passwordEncoder)

        service.delete(userId = "target", currentUserId = null)

        verify(userRepository).deleteById("target")
    }

    @Test
    fun `이미 존재하는 아이디로 생성 시도하면 거부한다`() {
        val userRepository = mock(AppUserRepository::class.java)
        val passwordEncoder = mock(PasswordEncoder::class.java)
        val service = UserManagementService(userRepository, passwordEncoder)
        `when`(userRepository.existsById("dup")).thenReturn(true)

        val ex = assertThrows<IllegalArgumentException> {
            service.create(UserForm(userId = "dup", password = "pw", userName = "중복"))
        }

        assertEquals("이미 존재하는 아이디입니다: dup", ex.message)
        verify(userRepository, never()).save(org.mockito.ArgumentMatchers.any())
    }

    @Test
    fun `생성 시 비밀번호는 평문이 아니라 인코딩된 값으로 저장된다`() {
        val userRepository = mock(AppUserRepository::class.java)
        val passwordEncoder = mock(PasswordEncoder::class.java)
        val service = UserManagementService(userRepository, passwordEncoder)
        `when`(userRepository.existsById("newbie")).thenReturn(false)
        `when`(passwordEncoder.encode("plain-pw")).thenReturn("encoded-pw")

        service.create(UserForm(userId = "newbie", password = "plain-pw", userName = "신규"))

        val captor = ArgumentCaptor.forClass(AppUser::class.java)
        verify(userRepository).save(captor.capture())
        assertEquals("encoded-pw", captor.value.passwordHash)
        assertFalse(captor.value.passwordHash == "plain-pw")
    }

    @Test
    fun `수정 시 비밀번호를 비워두면 기존 해시가 유지된다`() {
        val userRepository = mock(AppUserRepository::class.java)
        val passwordEncoder = mock(PasswordEncoder::class.java)
        val service = UserManagementService(userRepository, passwordEncoder)
        val existing = user(userId = "keep", passwordHash = "original-hash")
        `when`(userRepository.findById("keep")).thenReturn(Optional.of(existing))

        service.update("keep", UserForm(userId = "keep", password = "", userName = "이름변경"))

        assertEquals("original-hash", existing.passwordHash)
        assertEquals("이름변경", existing.userName)
        verify(passwordEncoder, never()).encode(org.mockito.ArgumentMatchers.anyString())
    }

    @Test
    fun `수정 시 비밀번호를 입력하면 새로 인코딩되어 갱신된다`() {
        val userRepository = mock(AppUserRepository::class.java)
        val passwordEncoder = mock(PasswordEncoder::class.java)
        val service = UserManagementService(userRepository, passwordEncoder)
        val existing = user(userId = "keep", passwordHash = "original-hash")
        `when`(userRepository.findById("keep")).thenReturn(Optional.of(existing))
        `when`(passwordEncoder.encode("new-pw")).thenReturn("new-hash")

        service.update("keep", UserForm(userId = "keep", password = "new-pw", userName = "이름변경"))

        assertEquals("new-hash", existing.passwordHash)
    }

    @Test
    fun `존재하지 않는 userId 수정 시도는 예외를 던진다`() {
        val userRepository = mock(AppUserRepository::class.java)
        val passwordEncoder = mock(PasswordEncoder::class.java)
        val service = UserManagementService(userRepository, passwordEncoder)
        `when`(userRepository.findById("ghost")).thenReturn(Optional.empty())

        assertThrows<NoSuchElementException> {
            service.update("ghost", UserForm(userId = "ghost", password = "", userName = "유령"))
        }
    }

    @Test
    fun `아직 BCrypt로 승격되지 않은(레거시 평문) 계정만 개수에 포함한다`() {
        // upgradeEncoding은 LegacyAwarePasswordEncoder가 "평문이라 다음 로그인 시 재해시가
        // 필요하다"고 알릴 때 true를 반환한다(2026-09-03 코드 리뷰 지적: 휴면 계정은 평문으로
        // 무기한 남을 수 있어 관리 화면에 개수를 노출한다).
        val userRepository = mock(AppUserRepository::class.java)
        val passwordEncoder = mock(PasswordEncoder::class.java)
        val service = UserManagementService(userRepository, passwordEncoder)
        val legacy = user(userId = "legacy", passwordHash = "plain-text")
        val upgraded = user(userId = "upgraded", passwordHash = "\$2a\$10\$hashed")
        `when`(userRepository.findAll()).thenReturn(listOf(legacy, upgraded))
        `when`(passwordEncoder.upgradeEncoding("plain-text")).thenReturn(true)
        `when`(passwordEncoder.upgradeEncoding("\$2a\$10\$hashed")).thenReturn(false)

        assertEquals(1L, service.legacyPlaintextCount())
    }
}
