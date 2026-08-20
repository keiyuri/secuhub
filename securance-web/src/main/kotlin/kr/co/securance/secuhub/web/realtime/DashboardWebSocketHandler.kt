package kr.co.securance.secuhub.web.realtime

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator
import org.springframework.web.socket.handler.TextWebSocketHandler
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 3 실시간 대시보드(#18/#1/#17, 계획서 4절 결정: "WebSocket + 폴링")의 push 채널.
 *
 * STOMP/SockJS 없이 순정 [org.springframework.web.socket.WebSocketHandler]만 쓴다 — 클라이언트도
 * 브라우저 내장 `WebSocket` API로 붙기 때문에 `static/vendor/`에 별도 JS 라이브러리를 받아올
 * 필요가 없다. 실제 변경 감지는 이 핸들러가 아니라 [DashboardPushService]의 DB 폴링이 담당하고,
 * 이 핸들러는 "현재 연결된 세션 전체에 브로드캐스트"만 책임진다.
 *
 * ## 세션 동시 쓰기 안전성 (코드 리뷰 지적 R-4, 2026-08-20)
 * [WebSocketSession.sendMessage]는 스레드 안전하지 않다 — 지금은 `@Scheduled` 기본 태스크
 * 스케줄러 풀 크기가 1이라 [DashboardPushService.pushSummary]/[DashboardPushService.pushNewAlerts]가
 * 우연히 직렬화되어 동작할 뿐이었다. 누군가 `spring.task.scheduling.pool.size`를 올리거나
 * 스케줄러를 하나 더 추가하는 순간 같은 세션에 두 스레드가 동시에 쓰기를 시도해
 * `IllegalStateException`이 날 수 있었다. 세션을 등록할 때
 * [ConcurrentWebSocketSessionDecorator]로 감싸 큐잉을 통해 동시 호출을 안전하게 직렬화한다
 * (전송이 `sendTimeLimit`을 넘기거나 큐가 `bufferSizeLimit`을 넘으면 세션을 강제 종료한다 — 느린
 * 클라이언트 하나가 브로드캐스트 스레드를 무한정 붙잡는 것도 함께 막는다).
 */
@Component
class DashboardWebSocketHandler : TextWebSocketHandler() {
    private val log = LoggerFactory.getLogger(javaClass)
    private val sessions = ConcurrentHashMap.newKeySet<WebSocketSession>()

    override fun afterConnectionEstablished(session: WebSocketSession) {
        sessions.add(ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, BUFFER_SIZE_LIMIT_BYTES))
        log.debug("대시보드 WebSocket 연결: {} (현재 {}개)", session.id, sessions.size)
    }

    override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
        // afterConnectionClosed는 원본 session을 넘겨주지만 sessions에는 데코레이터가 들어 있어
        // equals/hashCode가 다르면 remove가 실패한다 — ConcurrentWebSocketSessionDecorator는
        // 명시적으로 원본 세션에 위임하지 않으므로 id로 찾아 제거한다.
        sessions.removeIf { it.id == session.id }
    }

    /** 현재 연결된 세션이 하나도 없는지 — [DashboardPushService]가 구독자 없을 때 DB 폴링을 건너뛰는 데 쓴다. */
    fun hasSessions(): Boolean = sessions.isNotEmpty()

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

    private companion object {
        /** 느린 클라이언트에 대한 전송 시간 상한(ms) — 초과하면 세션을 강제 종료한다. */
        const val SEND_TIME_LIMIT_MS = 10_000
        const val BUFFER_SIZE_LIMIT_BYTES = 512 * 1024
    }
}
