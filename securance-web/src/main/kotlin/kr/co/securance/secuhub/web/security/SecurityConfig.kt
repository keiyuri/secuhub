package kr.co.securance.secuhub.web.security

import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

/**
 * 폼 로그인 기반 인증(계획서 5.4절). 세션은 서버 기본(in-memory)으로 시작하고,
 * `securance.cache.redis.enabled=true`가 되면 Spring Session Redis로 교체해
 * 다중 인스턴스 배포에 대비할 수 있다(계획서 6절, 후속 작업).
 *
 * [devAutoLoginFilterProvider]는 [DevAutoLoginFilter]가 빈으로 존재할 때만(=
 * `securance.security.dev-auto-login-enabled=true`) 값을 가진다 — 전환 작업 검증 동안
 * 로그인 없이 ROLE_ADMIN으로 통과시키기 위한 임시 조치다. 기본값(false)에서는 폼 로그인만 동작한다.
 */
@Configuration
class SecurityConfig(
    private val devAutoLoginFilterProvider: ObjectProvider<DevAutoLoginFilter>,
) {

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
        // 개발 전용 자동 로그인 필터가 활성화돼 있으면 폼 로그인 필터보다 먼저 태워
        // 인증을 선점하게 한다 — 플래그가 꺼져 있으면(기본값) 이 블록은 아무 일도 하지 않는다.
        devAutoLoginFilterProvider.ifAvailable { filter ->
            http.addFilterBefore(filter, UsernamePasswordAuthenticationFilter::class.java)
        }
        return http.build()
    }
}
