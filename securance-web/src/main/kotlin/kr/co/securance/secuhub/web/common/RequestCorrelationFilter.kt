package kr.co.securance.secuhub.web.common

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/**
 * 요청 단위 상관관계 ID(2026-08-13 코드 리뷰 지적) — 이전까지 로그에서 같은 HTTP 요청에 속한
 * 여러 줄(컨트롤러 진입, 서비스 예외, 필터 체인 등)을 묶어낼 방법이 MDC가 아니라 각 로그 문에
 * 수동으로 넘긴 파라미터(dtlIp 등)뿐이었다 — 컨트롤러/서비스 전반에 일관되게 붙어있지 않았다.
 *
 * 요청 진입 시 `X-Request-Id` 헤더가 있으면 그대로 쓰고(프록시/로드밸런서가 이미 부여한 경우
 * 그 값을 유지해 앞단과 상관관계를 이어간다), 없으면 새로 생성한다. 응답 헤더로도 돌려줘
 * 클라이언트/프록시 로그와도 대조할 수 있게 한다. `logging.pattern.*`(application.yml)이
 * `%X{requestId}`로 이 값을 로그 라인에 찍는다.
 *
 * 헤더 값은 클라이언트가 임의로 채울 수 있으므로(로그 인젝션 방지) 영숫자/하이픈만 허용하고
 * 길이를 제한한다 — 형식이 맞지 않으면 신뢰하지 않고 새로 생성한다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestCorrelationFilter : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val incoming = request.getHeader(REQUEST_ID_HEADER)
        val requestId = if (incoming != null && VALID_ID.matches(incoming)) incoming else UUID.randomUUID().toString()

        MDC.put(MDC_KEY, requestId)
        response.setHeader(REQUEST_ID_HEADER, requestId)
        try {
            filterChain.doFilter(request, response)
        } finally {
            // 스레드 풀 재사용 시 다음 요청으로 값이 새지 않도록 반드시 정리한다.
            MDC.remove(MDC_KEY)
        }
    }

    private companion object {
        const val REQUEST_ID_HEADER = "X-Request-Id"
        const val MDC_KEY = "requestId"
        val VALID_ID = Regex("[a-zA-Z0-9-]{1,64}")
    }
}
