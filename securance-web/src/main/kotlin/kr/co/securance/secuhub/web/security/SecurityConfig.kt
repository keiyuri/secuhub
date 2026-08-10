package kr.co.securance.secuhub.web.security

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain

/**
 * 폼 로그인 기반 인증(계획서 5.4절). 세션은 서버 기본(in-memory)으로 시작하고,
 * `securance.cache.redis.enabled=true`가 되면 Spring Session Redis로 교체해
 * 다중 인스턴스 배포에 대비할 수 있다(계획서 6절, 후속 작업).
 */
@Configuration
class SecurityConfig {

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http {
            authorizeHttpRequests {
                authorize("/login", permitAll)
                authorize("/vendor/**", permitAll)
                authorize("/js/**", permitAll)
                authorize("/css/**", permitAll)
                // 게이트 제어/리셋은 tb_users.auth_ctrl(ROLE_CONTROL) 또는 auth_admin(ROLE_ADMIN)
                // 권한을 가진 사용자만 호출할 수 있다(계획서 5.4/5.5절).
                authorize("/api/gate-control/**", hasAnyRole("CONTROL", "ADMIN"))
                authorize(anyRequest, authenticated)
            }
            formLogin {
                loginPage = "/login"
                defaultSuccessUrl("/dashboard", true)
            }
            logout {
                logoutUrl = "/logout"
                logoutSuccessUrl = "/login?logout"
            }
            sessionManagement {
                sessionCreationPolicy = SessionCreationPolicy.IF_REQUIRED
            }
        }
        return http.build()
    }
}
