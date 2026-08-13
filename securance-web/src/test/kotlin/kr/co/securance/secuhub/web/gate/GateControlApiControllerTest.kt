package kr.co.securance.secuhub.web.gate

import kr.co.securance.secuhub.protocol.SpeedGateControlCommand
import kr.co.securance.secuhub.server.control.GateControlRequest
import kr.co.securance.secuhub.server.control.GateControlResult
import kr.co.securance.secuhub.server.control.GateControlService as ServerGateControlService
import kr.co.securance.secuhub.server.control.GateFaultResolutionService
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

// AccessReportControllerTest와 동일한 사정(securance-web에는 @SpringBootApplication이 없다) —
// @WebMvcTest가 기준으로 삼을 @SpringBootConfiguration을 이 패키지에 최소로 하나 둔다.
// AuthenticationPrincipalArgumentResolver를 직접 등록한다 — 이 리졸버가 없으면 @AuthenticationPrincipal
// 파라미터를 스프링이 @ModelAttribute로 오인해 "No primary or single unique constructor found for
// interface UserDetails"로 실패한다(실측). 등록을 담당하는 WebMvcSecurityConfiguration은
// spring-security-config 내부에서 package-private이라 직접 Import할 수 없어, 리졸버만 수동으로 단다.
@SpringBootConfiguration
@EnableAutoConfiguration
private class TestGateControlWebApp : WebMvcConfigurer {
    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers.add(AuthenticationPrincipalArgumentResolver())
    }
}

/**
 * [GateControlApiController] HTTP 레벨 검증 — 2026-08-13 Opus 전체 리뷰 지적(제어 명령 API처럼
 * 결과에 따라 HTTP 상태를 4가지로 분기하는 컨트롤러에 MockMvc 테스트가 없었다). [GateControlResult]
 * 각 값이 실제로 의도한 HTTP 상태로 매핑되는지, 그리고 리셋 엔드포인트의 "명령이 실제로
 * SENT되었을 때만 장애를 해제한다"는 계약을 검증한다.
 */
@WebMvcTest(GateControlApiController::class)
@Import(GateControlApiController::class)
class GateControlApiControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var gateControlService: ServerGateControlService

    @MockitoBean
    private lateinit var faultResolutionService: GateFaultResolutionService

    @Test
    @WithMockUser
    fun `SENT 결과는 200으로 응답한다`() {
        `when`(gateControlService.send(anyRequest())).thenReturn(GateControlResult.SENT)

        mockMvc.post("/api/gate-control/command") {
            param("dtlIp", "192.168.0.10")
            param("dtlLaneNo", "1")
            param("command", "OPEN")
            with(csrf())
        }.andExpect {
            status { isOk() }
            jsonPath("$.result") { value("SENT") }
        }
    }

    @Test
    @WithMockUser
    fun `QUEUED 결과는 202로 응답한다`() {
        `when`(gateControlService.send(anyRequest())).thenReturn(GateControlResult.QUEUED)

        mockMvc.post("/api/gate-control/command") {
            param("dtlIp", "192.168.0.10")
            param("dtlLaneNo", "1")
            param("command", "OPEN")
            with(csrf())
        }.andExpect {
            status { isAccepted() }
            jsonPath("$.result") { value("QUEUED") }
        }
    }

    @Test
    @WithMockUser
    fun `NOT_CONNECTED 결과는 503으로 응답한다`() {
        `when`(gateControlService.send(anyRequest())).thenReturn(GateControlResult.NOT_CONNECTED)

        mockMvc.post("/api/gate-control/command") {
            param("dtlIp", "192.168.0.10")
            param("dtlLaneNo", "1")
            param("command", "OPEN")
            with(csrf())
        }.andExpect {
            status { isServiceUnavailable() }
        }
    }

    @Test
    @WithMockUser
    fun `REJECTED 결과는 429로 응답한다`() {
        `when`(gateControlService.send(anyRequest())).thenReturn(GateControlResult.REJECTED)

        mockMvc.post("/api/gate-control/command") {
            param("dtlIp", "192.168.0.10")
            param("dtlLaneNo", "1")
            param("command", "OPEN")
            with(csrf())
        }.andExpect {
            status { isTooManyRequests() }
        }
    }

    @Test
    @WithMockUser
    fun `리셋이 아닌 명령으로 reset 엔드포인트를 호출하면 400과 REJECTED를 반환하고 장애 해제를 시도하지 않는다`() {
        mockMvc.post("/api/gate-control/reset") {
            param("dtlIp", "192.168.0.10")
            param("dtlLaneNo", "1")
            param("command", "OPEN")
            with(csrf())
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.result") { value("REJECTED") }
        }

        Mockito.verifyNoInteractions(faultResolutionService)
    }

    @Test
    @WithMockUser
    fun `리셋 명령이 SENT되면 장애 해제를 시도하고 해제 건수를 응답에 담는다`() {
        `when`(gateControlService.send(anyRequest())).thenReturn(GateControlResult.SENT)
        `when`(
            faultResolutionService.resolveByResetCommand(
                dtlIp = "192.168.0.10",
                dtlLaneNo = 1,
                command = SpeedGateControlCommand.RESET_SYSTEM,
                resolvedBy = "user",
            ),
        ).thenReturn(3)

        mockMvc.post("/api/gate-control/reset") {
            param("dtlIp", "192.168.0.10")
            param("dtlLaneNo", "1")
            param("command", "RESET_SYSTEM")
            with(csrf())
            with(user("user"))
        }.andExpect {
            status { isOk() }
            jsonPath("$.resolvedFaults") { value(3) }
        }
    }

    @Test
    @WithMockUser
    fun `리셋 명령이 QUEUED에 그치면(SENT 아님) 장애 해제를 시도하지 않는다`() {
        `when`(gateControlService.send(anyRequest())).thenReturn(GateControlResult.QUEUED)

        mockMvc.post("/api/gate-control/reset") {
            param("dtlIp", "192.168.0.10")
            param("dtlLaneNo", "1")
            param("command", "RESET_SYSTEM")
            with(csrf())
        }.andExpect {
            status { isAccepted() }
        }

        Mockito.verifyNoInteractions(faultResolutionService)
    }

    @Test
    @WithMockUser
    fun `장애 해제 도중 예외가 나도 리셋 응답 자체는 성공(SENT)으로 유지한다`() {
        `when`(gateControlService.send(anyRequest())).thenReturn(GateControlResult.SENT)
        `when`(
            faultResolutionService.resolveByResetCommand(
                anyKtString(), anyInt(), anyKtCommand(), anyKtString(),
            ),
        ).thenThrow(RuntimeException("DB 장애"))

        mockMvc.post("/api/gate-control/reset") {
            param("dtlIp", "192.168.0.10")
            param("dtlLaneNo", "1")
            param("command", "RESET_SYSTEM")
            with(csrf())
        }.andExpect {
            status { isOk() }
            jsonPath("$.result") { value("SENT") }
            jsonPath("$.resolvedFaults") { value(0) }
        }
    }

    private fun anyRequest(): GateControlRequest = anyOf()
}

private fun <T> anyOf(): T {
    Mockito.any<T>()
    @Suppress("UNCHECKED_CAST")
    return null as T
}
private fun anyKtString(): String = anyOf()
private fun anyKtCommand(): SpeedGateControlCommand = anyOf()
