package kr.co.securance.secuhub.web.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * ⚠ 개발 전용 임시 조치 — 전환 작업 검증 중에는 매번 로그인하지 않도록, 요청마다
 * `tb_users` 조회 없이 즉시 ROLE_ADMIN으로 인증 처리한다.
 *
 * `securance.security.dev-auto-login-enabled=true`일 때만 [SecurityConfig]가 이 필터를
 * 체인에 끼워 넣는다(기본값 false, `application-prod.yml`에서도 명시적으로 false로 재확인).
 * **전환/검증 작업이 끝나면 이 설정 키와 이 파일을 함께 제거할 것** — 이 필터가 켜져 있는 동안은
 * 어떤 자격증명으로도 실제 로그인 검증이 되지 않으므로 운영 환경에 절대 켜면 안 된다.
 *
 * DB 자격증명이나 비밀번호를 대신 채워 넣는 방식이 아니라 [SecurityContextHolder]에 직접
 * 인증 완료 토큰을 심는 방식이다 — `tb_users`에 admin 계정이 없어도(로컬 DB 미시딩 상태) 동작한다.
 */
@Component
@ConditionalOnProperty(name = ["securance.security.dev-auto-login-enabled"], havingValue = "true")
class DevAutoLoginFilter : OncePerRequestFilter() {

    // OncePerRequestFilter(→ GenericFilterBean)가 이미 protected `logger` 필드(Commons Logging)를
    // 가지고 있어 이름이 겹친다 — 명시적으로 다른 이름을 쓴다.
    private val log = LoggerFactory.getLogger(DevAutoLoginFilter::class.java)

    init {
        log.warn(
            "\n" +
                "════════════════════════════════════════════════════════════════════════\n" +
                "  [DEV-AUTO-LOGIN] 로그인 없이 ROLE_ADMIN으로 자동 인증하는 임시 필터가 켜져 있습니다.\n" +
                "  전환 작업 검증이 끝나면 securance.security.dev-auto-login-enabled=false로\n" +
                "  되돌리거나 이 필터를 삭제하세요. 운영 배포 전 반드시 확인할 것.\n" +
                "════════════════════════════════════════════════════════════════════════",
        )
    }

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        // 이미 인증돼 있으면(정상 로그인 세션 등) 건드리지 않는다.
        val existing = SecurityContextHolder.getContext().authentication
        if (existing == null || !existing.isAuthenticated) {
            val authorities = listOf(
                SimpleGrantedAuthority("ROLE_VIEW"),
                SimpleGrantedAuthority("ROLE_CONTROL"),
                SimpleGrantedAuthority("ROLE_ADMIN"),
            )
            SecurityContextHolder.getContext().authentication =
                UsernamePasswordAuthenticationToken("dev-auto-admin", null, authorities)
        }
        filterChain.doFilter(request, response)
    }
}
