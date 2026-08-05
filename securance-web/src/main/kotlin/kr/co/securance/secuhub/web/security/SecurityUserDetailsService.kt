package kr.co.securance.secuhub.web.security

import kr.co.securance.secuhub.domain.repository.AppUserRepository
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.stereotype.Service

/**
 * `tb_users`(계획서 5.4절)를 Spring Security 인증 소스로 연결한다.
 * `auth_view/auth_ctrl/auth_admin`(Y/N)을 각각 ROLE_VIEW/ROLE_CONTROL/ROLE_ADMIN으로 매핑한다.
 */
@Service
class SecurityUserDetailsService(
    private val appUserRepository: AppUserRepository,
) : UserDetailsService {

    override fun loadUserByUsername(username: String): UserDetails {
        val user = appUserRepository.findById(username)
            .orElseThrow { UsernameNotFoundException("사용자를 찾을 수 없습니다: $username") }

        val authorities = buildList {
            if (user.authView) add(SimpleGrantedAuthority("ROLE_VIEW"))
            if (user.authControl) add(SimpleGrantedAuthority("ROLE_CONTROL"))
            if (user.authAdmin) add(SimpleGrantedAuthority("ROLE_ADMIN"))
        }

        return User.builder()
            .username(user.userId)
            .password(user.passwordHash)
            .authorities(authorities)
            .disabled(!user.useYn)
            .build()
    }
}
