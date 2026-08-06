package kr.co.securance.secuhub.web.security

import kr.co.securance.secuhub.domain.repository.AppUserRepository
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsPasswordService
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * `tb_users`(계획서 5.4절)를 Spring Security 인증 소스로 연결한다.
 * `auth_view/auth_ctrl/auth_admin`(Y/N)을 각각 ROLE_VIEW/ROLE_CONTROL/ROLE_ADMIN으로 매핑한다.
 *
 * [UserDetailsPasswordService]도 함께 구현한다: [LegacyAwarePasswordEncoder]가 레거시 평문
 * 비밀번호로 로그인 성공을 감지하면(`upgradeEncoding=true`), Spring Security의 인증 provider가
 * [updatePassword]를 호출해 BCrypt 해시로 즉시 승격/저장한다 — 별도 마이그레이션 배치가 필요 없다.
 *
 * [LoginAttemptService]로 브루트포스 방어도 함께 적용한다: 반복 실패로 잠긴 계정은
 * `accountLocked=true`로 반환되어 `AccountStatusUserDetailsChecker`가 `LockedException`을 던진다.
 */
@Service
class SecurityUserDetailsService(
    private val appUserRepository: AppUserRepository,
    private val loginAttemptService: LoginAttemptService,
) : UserDetailsService, UserDetailsPasswordService {

    override fun loadUserByUsername(username: String): UserDetails {
        val user = appUserRepository.findById(username)
            .orElseThrow { UsernameNotFoundException("사용자를 찾을 수 없습니다: $username") }

        return toUserDetails(user.userId, user.passwordHash, user)
    }

    // 조회(findById)와 저장(save)이 하나의 트랜잭션으로 묶이지 않으면(적대적 리뷰 지적) 그 사이에
    // 다른 요청이 같은 행을 갱신할 경우 조회-저장 방식이라 서로를 덮어쓸 수 있다. 레거시 평문→BCrypt
    // 승격은 원래 멱등적(같은 결과로 수렴)이라 위험은 낮지만, 원자성을 보장하는 편이 안전하다.
    @Transactional
    override fun updatePassword(user: UserDetails, newPassword: String?): UserDetails {
        requireNotNull(newPassword) { "newPassword는 null일 수 없습니다." }
        val entity = appUserRepository.findById(user.username)
            .orElseThrow { UsernameNotFoundException("사용자를 찾을 수 없습니다: ${user.username}") }
        entity.passwordHash = newPassword
        appUserRepository.save(entity)
        return toUserDetails(entity.userId, newPassword, entity)
    }

    private fun toUserDetails(
        userId: String,
        passwordHash: String,
        user: kr.co.securance.secuhub.domain.entity.AppUser,
    ): UserDetails {
        val authorities = buildList {
            if (user.authView) add(SimpleGrantedAuthority("ROLE_VIEW"))
            if (user.authControl) add(SimpleGrantedAuthority("ROLE_CONTROL"))
            if (user.authAdmin) add(SimpleGrantedAuthority("ROLE_ADMIN"))
        }

        return User.builder()
            .username(userId)
            .password(passwordHash)
            .authorities(authorities)
            .disabled(!user.useYn)
            .accountLocked(loginAttemptService.isLocked(userId))
            .build()
    }
}
