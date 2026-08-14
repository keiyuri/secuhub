package kr.co.securance.secuhub.web.security

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.session.SessionRegistry
import org.springframework.security.core.session.SessionRegistryImpl
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
// SecuritySettingsProperties는 여기서 활성화한다 — SecurityConfigTest처럼 SecurityConfig만 @Import해
// 최소 컨텍스트를 구성하는 테스트도 securityFilterChain(..., securitySettings)의 의존성을 그대로
// 만족시킬 수 있어야 하기 때문이다(WebConfig에도 걸어두면 컨텍스트가 겹칠 때 중복 등록 오류가 남).
@Configuration
@EnableConfigurationProperties(SecuritySettingsProperties::class)
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

    /**
     * sessionConcurrency에 명시적으로 연결해 애플리케이션 컨텍스트의 빈으로 노출한다 — DSL에
     * 넘기지 않으면 `SessionManagementConfigurer`가 내부적으로 자기만의 `SessionRegistryImpl`을
     * 만들어버려, [ConcurrentLoginAuditListener]가 주입받는 레지스트리와 실제로 세션을 등록/조회하는
     * 레지스트리가 서로 다른 인스턴스가 된다(= 감사 로그가 항상 "기존 세션 없음"으로만 보임).
     */
    @Bean
    fun sessionRegistry(): SessionRegistry = SessionRegistryImpl()

    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        securitySettings: SecuritySettingsProperties,
        // DSL 람다 안의 SessionConcurrencyDsl.sessionRegistry 프로퍼티와 이름이 겹치면 셰도잉으로
        // 자기 자신에게 대입하는 실수를 하기 쉬워, 파라미터명을 의도적으로 다르게 둔다.
        sharedSessionRegistry: SessionRegistry,
    ): SecurityFilterChain {
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
                // 헬스체크(2026-08-13 코드 리뷰 지적 — Actuator 도입)는 외부 모니터링/로드밸런서가
                // 인증 없이 주기적으로 찔러야 하므로 별도로 허용한다. 그 외 액추에이터 엔드포인트
                // (/actuator/metrics 등)는 내부 상태(스레드/DB 풀 등)를 노출하므로 ADMIN만 허용한다.
                authorize("/actuator/health", permitAll)
                authorize("/actuator/health/**", permitAll)
                authorize("/actuator/**", hasRole("ADMIN"))
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
                //
                // [2026-08-13 코드 리뷰 수정] 위 의도(실제 게이트 제어 명령 보호)와 달리 "/gates/**"
                // POST/PUT/DELETE 전체를 ROLE_CONTROL로 열어두면, 같은 경로 트리 아래 있는
                // GateGroupController/GateLocationController/GateDetailController/LocationMapController의
                // 마스터 데이터 CRUD(위치/그룹/게이트 등록·수정·삭제, 배치도 업로드)까지 ROLE_CONTROL
                // 계정에게 새어나간다 — "운영(제어)"만 부여된 계정이 실제로는 ADMIN급 등록/삭제 권한을
                // 갖게 되는 셈이다. 실제 제어 엔드포인트(모드변경/모터설정/리셋 실행)만 좁게 먼저
                // 매칭시키고, 나머지 "/gates/**" 상태변경 요청(=마스터 데이터 CRUD)은 ROLE_ADMIN으로
                // 좁힌다. Spring Security는 먼저 매칭되는 규칙을 적용하므로 순서가 중요하다.
                authorize(HttpMethod.POST, "/gates/details/*/mode", hasAnyRole("CONTROL", "ADMIN"))
                authorize(HttpMethod.POST, "/gates/details/*/motor", hasAnyRole("CONTROL", "ADMIN"))
                authorize(HttpMethod.POST, "/gates/reset/execute", hasAnyRole("CONTROL", "ADMIN"))
                authorize(HttpMethod.POST, "/gates/**", hasRole("ADMIN"))
                authorize(HttpMethod.PUT, "/gates/**", hasRole("ADMIN"))
                authorize(HttpMethod.DELETE, "/gates/**", hasRole("ADMIN"))
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
                // 2026-08-12 사용자 확인: 웹 접근 자체의 로그인 요구 여부를 설정으로 켜고 끌 수
                // 있어야 한다(securance.security.web-login-required). false여도 위에서 이미 명시한
                // 역할 기반 규칙(admin/control/게이트 제어 API 등)은 이 값과 무관하게 그대로 유지된다
                // — 이 토글은 오직 "그 외 나머지 화면"의 최소 인증 요구(ROLE_VIEW)만 켜고 끈다.
                if (securitySettings.webLoginRequired) {
                    authorize(anyRequest, hasRole("VIEW"))
                } else {
                    authorize(anyRequest, permitAll)
                }
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
                // 탈취되면 정상 사용자 몰래 계속 살아있을 수 있다(세션 고정 공격 자체는 Spring
                // Security 기본 전략인 changeSessionId()로 이미 방어된다 — 별도 설정 불필요).
                //
                // [2026-08-14 사용자 요청 → Codex 적대적 리뷰로 재조정] 한때 새 로그인 자체를 막는
                // maxSessionsPreventsLogin = true를 시도했으나, 세션이 탈취되었거나 브라우저 비정상
                // 종료로 세션이 방치된 경우 정상 사용자가 올바른 비밀번호를 갖고 있어도 세션 만료
                // 전까지 완전히 잠기는 부작용이 지적되었다(High). 그래서 새 로그인이 기존 세션을
                // 밀어내는 기본 동작(maxSessionsPreventsLogin = false)으로 되돌린다.
                //
                // [2026-08-14 재검토] 밀려나는(만료되는) 세션이 "다음 요청"을 보낼 때만 감사 로그를
                // 남기는 방식([EvictedSessionRedirectStrategy]만으로는 충분치 않았다 — 로그에
                // 공격자가 아니라 피해자의 IP만 남고, 피해자가 재요청을 안 보내면 로그 자체가 남지
                // 않는다. 그래서 새로 로그인하는 시점에 기존 세션 존재 여부를 확인해 공격자 쪽 IP로
                // 경고를 남기는 [ConcurrentLoginAuditListener]를 별도로 둔다(sessionRegistry 공유 필요
                // — 아래 참고). [EvictedSessionRedirectStrategy]는 밀려난 사용자에게 "다른 곳에서
                // 로그인했다"는 화면 안내(운영 UX)만 담당하도록 역할을 좁혔다.
                sessionConcurrency {
                    maximumSessions = 1
                    maxSessionsPreventsLogin = false
                    sessionRegistry = sharedSessionRegistry
                    expiredSessionStrategy = EvictedSessionRedirectStrategy()
                }
            }
        }
        return http.build()
    }
}
