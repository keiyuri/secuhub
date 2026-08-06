package kr.co.securance.secuhub.web.realtime

import org.springframework.context.annotation.Configuration
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry

/** `/ws/dashboard` 엔드포인트 등록. 로그인 세션 쿠키를 그대로 타므로 `SecurityConfig`의
 * `anyRequest -> hasRole("VIEW")` 규칙이 이 경로에도 적용된다(별도 인가 예외 없음). */
@Configuration
@EnableWebSocket
class WebSocketConfig(
    private val dashboardWebSocketHandler: DashboardWebSocketHandler,
) : WebSocketConfigurer {
    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        registry.addHandler(dashboardWebSocketHandler, "/ws/dashboard")
    }
}
