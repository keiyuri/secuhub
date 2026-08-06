package kr.co.securance.secuhub.web.realtime

import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.WebSocketSession
import kotlin.test.Test

class DashboardWebSocketHandlerTest {

    private fun session(id: String, isOpen: Boolean = true) = mock(WebSocketSession::class.java).also {
        `when`(it.id).thenReturn(id)
        `when`(it.isOpen).thenReturn(isOpen)
    }

    @Test
    fun `열린 세션에는 메시지를 보내고 세션 목록에서 제거하지 않는다`() {
        val handler = DashboardWebSocketHandler()
        val open = session("open-1")
        handler.afterConnectionEstablished(open)

        handler.broadcast("{\"type\":\"SUMMARY\"}")

        verify(open).sendMessage(org.mockito.ArgumentMatchers.any())
    }

    @Test
    fun `이미 닫힌 세션은 전송 없이 제거되고, 두 번째 broadcast에서 다시 시도되지 않는다`() {
        val handler = DashboardWebSocketHandler()
        val closed = session("closed-1", isOpen = false)
        handler.afterConnectionEstablished(closed)

        handler.broadcast("first")
        handler.broadcast("second")

        verify(closed, never()).sendMessage(org.mockito.ArgumentMatchers.any())
    }

    @Test
    fun `sendMessage가 예외를 던지는 세션은 제거되고 이후 broadcast에 영향을 주지 않는다`() {
        // KDoc 주석("전송 실패 시 그 세션은 제거한다")에 대한 회귀 방지 테스트.
        val handler = DashboardWebSocketHandler()
        val failing = session("failing-1")
        `when`(failing.sendMessage(org.mockito.ArgumentMatchers.any())).thenThrow(RuntimeException("연결 끊김"))
        val healthy = session("healthy-1")

        handler.afterConnectionEstablished(failing)
        handler.afterConnectionEstablished(healthy)

        handler.broadcast("payload")

        verify(healthy).sendMessage(org.mockito.ArgumentMatchers.any())
        // 두 번째 broadcast에서는 failing 세션이 이미 제거되어 healthy에게만 한 번 더 전송된다.
        handler.broadcast("payload2")
        verify(failing, org.mockito.Mockito.times(1)).sendMessage(org.mockito.ArgumentMatchers.any())
    }

    @Test
    fun `afterConnectionClosed로 제거된 세션은 broadcast 대상에서 빠진다`() {
        val handler = DashboardWebSocketHandler()
        val session = session("gone-1")
        handler.afterConnectionEstablished(session)
        handler.afterConnectionClosed(session, CloseStatus.NORMAL)

        handler.broadcast("payload")

        verify(session, never()).sendMessage(org.mockito.ArgumentMatchers.any())
    }
}
