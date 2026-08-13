package kr.co.securance.secuhub.web.common

import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RestController
private class MdcProbeController {
    // 필터가 컨트롤러 실행 시점에 MDC를 이미 채워뒀는지 응답 바디로 그대로 노출해 검증한다.
    @GetMapping("/probe/mdc") fun probe(): String = MDC.get("requestId") ?: "none"
}

/**
 * [RequestCorrelationFilter] 검증(2026-08-13 코드 리뷰 지적 — 요청 상관관계 ID 도입).
 */
class RequestCorrelationFilterTest {
    private val mockMvc: MockMvc = run {
        val builder = MockMvcBuilders.standaloneSetup(MdcProbeController())
        builder.addFilter<StandaloneMockMvcBuilder>(RequestCorrelationFilter())
        builder.build()
    }

    @Test
    fun `요청마다 X-Request-Id 응답 헤더가 생성된다`() {
        val result = mockMvc.get("/probe/mdc").andReturn()
        val requestId = result.response.getHeader("X-Request-Id")

        assertNotNull(requestId)
        assertTrue(requestId.isNotBlank())
    }

    @Test
    fun `클라이언트가 유효한 X-Request-Id를 보내면 그대로 유지된다`() {
        val result = mockMvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/probe/mdc")
                .header("X-Request-Id", "trace-abc-123"),
        ).andReturn()

        assertEquals("trace-abc-123", result.response.getHeader("X-Request-Id"))
    }

    @Test
    fun `형식이 이상한 X-Request-Id는 무시하고 새로 생성한다`() {
        // 로그 인젝션 방지(개행/제어문자 등) — 영숫자/하이픈 외의 값은 신뢰하지 않는다.
        val result = mockMvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/probe/mdc")
                .header("X-Request-Id", "bad\nvalue"),
        ).andReturn()

        val requestId = result.response.getHeader("X-Request-Id")
        assertNotNull(requestId)
        assertTrue(requestId != "bad\nvalue")
    }

    @Test
    fun `컨트롤러 실행 중에는 MDC에 requestId가 채워져 있고 요청이 끝나면 정리된다`() {
        val result = mockMvc.get("/probe/mdc").andReturn()
        val requestId = result.response.getHeader("X-Request-Id")

        assertEquals(requestId, result.response.contentAsString)
        // 스레드가 재사용되는 프로덕션 환경을 흉내내어, 요청 처리가 끝난 뒤 같은 스레드에 값이 남지 않는지 확인.
        assertNull(MDC.get("requestId"))
    }
}
