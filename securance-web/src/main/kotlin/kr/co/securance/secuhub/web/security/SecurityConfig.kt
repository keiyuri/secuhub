package kr.co.securance.secuhub.web.security

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.session.HttpSessionEventPublisher

/**
 * 폼 로그인 기반 인증(계획서 5.4절). 세션은 서버 기본(in-memory)으로 시작하고,
 * `securance.cache.redis.enabled=true`가 되면 Spring Session Redis로 교체해
 * 다중 인스턴스 배포에 대비할 수 있다(계획서 6절, 후속 작업).
 *
 * 전환 작업 검증용 로그인 우회 필터(`DevAutoLoginFilter`, `securance.security.dev-auto-login-enabled`)는
 * 2026-08-12(B9)에 제거했다 — 기본값·prod 모두 false로 이미 비활성 상태였고, 파일 KDoc이 지시한
 * "전환/검증 종료 후 제거"를 이행한 것이다. 필요해지면 git 이력(`DevAutoLoginFilter.kt` 삭제
 * 커밋 이전)에서 복원할 수 있다.
 */
@Configuration
class SecurityConfig {

    // 레거시 평문 비밀번호(AppUser 주석 참고)와 BCrypt 해시가 tb_users.passwd에 섞여 있을 수 있어,
    // 단순 BCryptPasswordEncoder를 쓰면 레거시 계정이 영구히 로그인 불가가 된다.
    // LegacyAwarePasswordEncoder + SecurityUserDetailsService(UserDetailsPasswordService)가
    // 로그인 성공 시 자동으로 BCrypt로 승격시킨다.
    @Bean
    fun passwordEncoder(): PasswordEncoder = LegacyAwarePasswordEncoder()

    /**
     * `sessionConcurrency`(동시 세션 제한)가 만료된 세션을 제때 인식하려면 세션 소멸 이벤트가
     * `SessionRegistry`에 통지되어야 한다 — 이 리스너가 없으면 브라우저에서 로그아웃 없이 세션이
     * 타임아웃으로만 사라진 경우 레지스트리에 죽은 세션이 계속 "사용 중"으로 남아, 결국 정상
     * 사용자의 재로그인까지 막을 수 있다.
     */
    @Bean
    fun httpSessionEventPublisher(): HttpSessionEventPublisher = HttpSessionEventPublisher()

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http {
            authorizeHttpRequests {
                authorize("/login", permitAll)
                authorize("/vendor/**", permitAll)
                authorize("/js/**", permitAll)
                authorize("/css/**", permitAll)
                // Opus 전체 리뷰 지적: 전역 에러 페이지(templates/error.html) 신설과 함께 추가.
                // AccessDeniedHandler/에러 디스패처가 예외 발생 시 "/error"로 내부 포워드하는데,
                // 이 규칙이 없으면 그 포워드된 요청도 anyRequest 규칙(ROLE_VIEW 이상 필요)에 걸려
                // 403 처리 중에 또 인가 예외가 발생하거나 비로그인 사용자는 /login으로 리다이렉트되어
                // 정작 만들어둔 에러 페이지가 절대 보이지 않는다.
                authorize("/error", permitAll)
                // 사용자별 auth_view/auth_ctrl/auth_admin(Y/N)이 ROLE_VIEW/ROLE_CONTROL/ROLE_ADMIN으로
                // 매핑된다(SecurityUserDetailsService). 인증만으로는 부족하고, 경로별 권한 등급을
                // 명시해야 한다 — 그렇지 않으면 ROLE_VIEW만 가진 사용자도 관리자/제어 화면에
                // 접근할 수 있다. `/admin/**`, `/control/**` 컨트롤러는 아직 스캐폴드 단계라
                // 매칭되는 경로가 없지만, 추가될 때 이 규칙이 먼저 적용되도록 미리 선언해둔다.
                authorize("/admin/**", hasRole("ADMIN"))
                authorize("/control/**", hasRole("CONTROL"))
                // Opus 전체 리뷰 지적: GateControlController/GateResetController/ScheduleController의
                // 실제 게이트 제어(모터 설정, 리셋 실행, 스케줄 적용) 엔드포인트가 /control/**이 아니라
                // /gates/**, /schedule/** 아래(뷰 페이지와 같은 경로 트리)에 있어, 위 두 규칙으로는
                // 전혀 보호되지 않고 ROLE_VIEW만 있어도 제어 명령을 실행할 수 있었다. 상태를 변경하는
                // HTTP 메서드(POST/PUT/DELETE)만 별도로 ROLE_CONTROL 이상을 요구하도록 경로보다 먼저
                // 매칭시킨다 — GET(화면 조회)은 기존과 동일하게 ROLE_VIEW로 충분하다.
                authorize(HttpMethod.POST, "/gates/**", hasAnyRole("CONTROL", "ADMIN"))
                authorize(HttpMethod.PUT, "/gates/**", hasAnyRole("CONTROL", "ADMIN"))
                authorize(HttpMethod.DELETE, "/gates/**", hasAnyRole("CONTROL", "ADMIN"))
                authorize(HttpMethod.POST, "/schedule/**", hasAnyRole("CONTROL", "ADMIN"))
                authorize(HttpMethod.PUT, "/schedule/**", hasAnyRole("CONTROL", "ADMIN"))
                authorize(HttpMethod.DELETE, "/schedule/**", hasAnyRole("CONTROL", "ADMIN"))
                // 적대적 리뷰 지적: anyRequest -> authenticated였다 — auth_view/ctrl/admin이 전부
                // 'N'인(즉 어떤 권한도 없는) 계정도 use_yn='Y'이기만 하면 인증만으로 대시보드를 포함한
                // 나머지 모든 경로에 접근할 수 있었다(3종 권한을 매핑해놓고 실제로는 admin/control
                // 경로에만 강제하고 있어 사실상 나머지 화면에는 인가가 없는 셈이었다). ROLE_VIEW를
                // 최소 요구 권한으로 승격한다.
                // 게이트 제어/리셋 API는 tb_users.auth_ctrl(ROLE_CONTROL) 또는 auth_admin(ROLE_ADMIN)
                // 권한을 가진 사용자만 호출할 수 있다(계획서 5.4/5.5절).
                authorize("/api/gate-control/**", hasAnyRole("CONTROL", "ADMIN"))
                authorize(anyRequest, hasRole("VIEW"))
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
                // 동시 세션 제한(적대적 리뷰 지적) — 게이트 제어 권한을 가진 관리 콘솔이라, 세션이
                // 탈취되면 정상 사용자 몰래 계속 살아있을 수 있다. 새 로그인이 기존 세션을 밀어내게
                // 한다(세션 고정 공격 자체는 Spring Security 기본 전략인 changeSessionId()로 이미
                // 방어된다 — 별도 설정 불필요).
                sessionConcurrency {
                    maximumSessions = 1
                    maxSessionsPreventsLogin = false
                }
            }
        }
        return http.build()
    }
}
