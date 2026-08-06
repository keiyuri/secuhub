package kr.co.securance.secuhub.web.realtime

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.TextWebSocketHandler
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 3 실시간 대시보드(#18/#1/#17, 계획서 4절 결정: "WebSocket + 폴링")의 push 채널.
 *
 * STOMP/SockJS 없이 순정 [org.springframework.web.socket.WebSocketHandler]만 쓴다 — 클라이언트도
 * 브라우저 내장 `WebSocket` API로 붙기 때문에 `static/vendor/`에 별도 JS 라이브러리를 받아올
 * 필요가 없다. 실제 변경 감지는 이 핸들러가 아니라 [DashboardPushService]의 DB 폴링이 담당하고,
 * 이 핸들러는 "현재 연결된 세션 전체에 브로드캐스트"만 책임진다.
 */
@Component
class DashboardWebSocketHandler : TextWebSocketHandler() {
    private val log = LoggerFactory.getLogger(javaClass)
    private val sessions = ConcurrentHashMap.newKeySet<WebSocketSession>()

    override fun afterConnectionEstablished(session: WebSocketSession) {
        sessions.add(session)
        log.debug("대시보드 WebSocket 연결: {} (현재 {}개)", session.id, sessions.size)
    }

    override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
        sessions.remove(session)
    }

    /** 세션이 끊겼는데도 남아있던 경우를 대비해, 전송 실패 시 그 세션은 제거한다. */
    fun broadcast(json: String) {
        val message = TextMessage(json)
        sessions.removeIf { session ->
            if (!session.isOpen) return@removeIf true
            runCatching { session.sendMessage(message) }
                .onFailure { log.warn("WebSocket 전송 실패, 세션 제거: {}", session.id, it) }
                .isFailure
        }
    }
}
