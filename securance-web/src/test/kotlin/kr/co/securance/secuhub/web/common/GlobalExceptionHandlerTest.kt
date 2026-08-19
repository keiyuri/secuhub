package kr.co.securance.secuhub.web.common

import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.http.HttpMethod
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.servlet.resource.NoResourceFoundException
import kotlin.test.assertFailsWith

/**
 * [GlobalExceptionHandler] 검증(2026-08-13 코드 리뷰 지적) — 미처리 예외를 로깅한 뒤에도 반드시
 * 원래 예외를 그대로 다시 던져, 기존 에러 디스패치 흐름(→ error.html)이 이 핸들러 도입 전과
 * 동일하게 유지되는지 확인한다. 화면/상태 코드를 바꾸는 것이 목적이 아니므로 "그대로 재전파"가
 * 핵심 회귀 방지 포인트다.
 *
 * MockMvc로 실제 디스패치를 태우면 표준 스프링 리졸버(DefaultHandlerExceptionResolver 등)가
 * 먼저 개입해 이 핸들러가 실제로 호출됐는지를 가리기 어렵다 — 핸들러 메서드를 직접 호출하는
 * 순수 단위 테스트로 검증한다.
 */
class GlobalExceptionHandlerTest {
    private val handler = GlobalExceptionHandler()
    private val request: HttpServletRequest = mock(HttpServletRequest::class.java).also {
        `when`(it.method).thenReturn("GET")
        `when`(it.requestURI).thenReturn("/probe/boom")
    }

    @Test
    fun `미처리 예외는 그대로 다시 던져진다`() {
        val ex = IllegalStateException("의도된 테스트 예외")

        val thrown = assertFailsWith<IllegalStateException> { handler.handleUnexpected(request, ex) }

        // 로깅을 위해 감싸거나 다른 예외로 바꾸지 않고, 동일 인스턴스를 그대로 재전파해야 한다 —
        // 그래야 스프링의 기존 에러 디스패치(상태 코드 결정 등)가 이 핸들러 도입 전과 동일하게 동작한다.
        assert(thrown === ex)
    }

    @Test
    fun `MaxUploadSizeExceededException도 로깅 없이 그대로 다시 던져진다`() {
        // MultipartExceptionHandler가 더 구체적으로 처리해야 할 케이스라, GlobalExceptionHandler는
        // 이걸 에러로 취급하지 않고 즉시 다시 던지기만 한다(이중 로깅 방지).
        val ex = MaxUploadSizeExceededException(1024)

        val thrown = assertFailsWith<MaxUploadSizeExceededException> { handler.handleUnexpected(request, ex) }

        assert(thrown === ex)
    }

    @Test
    fun `NoResourceFoundException(favicon_ico 등)도 로깅 없이 그대로 다시 던져진다`() {
        // 브라우저가 자동 요청하는 favicon.ico 등 존재하지 않는 정적 리소스 요청은 장애가 아니므로
        // ERROR 로그(스택트레이스 포함)를 남기지 않고 즉시 다시 던지기만 한다.
        val ex = NoResourceFoundException(HttpMethod.GET, "/favicon.ico", "favicon.ico")

        val thrown = assertFailsWith<NoResourceFoundException> { handler.handleUnexpected(request, ex) }

        assert(thrown === ex)
    }
}
