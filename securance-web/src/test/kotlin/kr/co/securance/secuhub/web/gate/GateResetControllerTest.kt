package kr.co.securance.secuhub.web.gate

import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

/**
 * [GateResetController] HTTP 레벨 검증(2026-08-25 소스 전수 검토 지적 — 내부 서비스
 * [GateResetGridService]는 `GateResetGridServiceTest`로 커버되지만, 라우팅/리다이렉트/플래시
 * 메시지 분기를 잇는 이 컨트롤러 자체는 어떤 테스트에서도 인스턴스화된 적이 없었다).
 *
 * 같은 `web.gate` 패키지에 이미 `GateControlApiControllerTest`가 `@SpringBootConfiguration`
 * 최소 설정(`TestGateControlWebApp`)을 두고 있다 — `@WebMvcTest`는 패키지 기준으로 위로 올라가며
 * 찾으므로 여기서 별도로 선언하지 않는다(`GateFieldsErrorsRenderingTest`와 동일한 이유,
 * 중복 선언 시 "Found multiple @SpringBootConfiguration" 충돌).
 */
@WebMvcTest(GateResetController::class)
@Import(GateResetController::class)
class GateResetControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var gateResetGridService: GateResetGridService

    @MockitoBean
    private lateinit var locationService: GateLocationService

    @MockitoBean
    private lateinit var groupService: GateGroupService

    @MockitoBean
    private lateinit var gateControlService: GateControlService

    @MockitoBean
    private lateinit var menuProvider: kr.co.securance.secuhub.web.menu.MenuProvider

    @Test
    @WithMockUser
    fun `그룹을 지정해 조회하면 그리드 행을 모델에 담는다`() {
        `when`(menuProvider.menu()).thenReturn(emptyList())
        `when`(locationService.findAllActive()).thenReturn(emptyList())
        `when`(groupService.findAllActiveByLocation(1L)).thenReturn(emptyList())
        `when`(gateResetGridService.rowsFor(2L)).thenReturn(
            listOf(GateResetRow(dtlId = 1L, dtlLaneNo = 1, dtlIp = "192.168.0.10", dtlName = "1번", online = true)),
        )

        mockMvc.get("/gates/reset") {
            param("locId", "1")
            param("grpId", "2")
            with(csrf())
        }.andExpect {
            status { isOk() }
            model { attribute("rows", listOf(GateResetRow(1L, 1, "192.168.0.10", "1번", true))) }
        }
    }

    @Test
    @WithMockUser
    fun `선택한 게이트가 없으면 리셋을 실행하지 않고 안내 메시지만 남긴다`() {
        mockMvc.post("/gates/reset/execute") {
            param("locId", "1")
            param("grpId", "2")
            with(csrf())
        }.andExpect {
            status { is3xxRedirection() }
            redirectedUrl("/gates/reset?locId=1&grpId=2")
            flash { attributeExists("error") }
        }
    }

    @Test
    @WithMockUser
    fun `선택한 그룹에 속하지 않는 게이트는 거부되고 나머지만 리셋된다`() {
        // 요청은 dtlId 1,2를 담았지만 실제로 grpId에 속한 것은 1뿐인 상황(교차 검증 우회 시도).
        `when`(gateResetGridService.filterByGroupMembership(eq(2L), anyList())).thenReturn(listOf(1L))
        `when`(gateControlService.sendReset(eq(1L), anyString())).thenReturn(true)

        val result = mockMvc.post("/gates/reset/execute") {
            param("locId", "1")
            param("grpId", "2")
            param("dtlIds", "1", "2")
            with(csrf())
        }.andExpect {
            status { is3xxRedirection() }
            flash { attributeExists("error") }
        }.andReturn()

        val message = result.flashMap["error"] as String
        assert(message.contains("거부")) { "거부된 dtlId 안내가 메시지에 없다: $message" }
    }

    @Test
    @WithMockUser
    fun `리셋 명령 전송에 실패하면 실패 건수를 메시지에 남긴다`() {
        `when`(gateResetGridService.filterByGroupMembership(eq(2L), anyList())).thenReturn(listOf(1L))
        `when`(gateControlService.sendReset(eq(1L), anyString())).thenReturn(false)

        val result = mockMvc.post("/gates/reset/execute") {
            param("locId", "1")
            param("grpId", "2")
            param("dtlIds", "1")
            with(csrf())
        }.andExpect {
            status { is3xxRedirection() }
            flash { attributeExists("error") }
        }.andReturn()

        val message = result.flashMap["error"] as String
        assert(message.contains("실패")) { "실패 건수 안내가 메시지에 없다: $message" }
    }

    @Test
    @WithMockUser
    fun `전량 성공하면 성공 메시지를 남긴다`() {
        `when`(gateResetGridService.filterByGroupMembership(eq(2L), anyList())).thenReturn(listOf(1L, 2L))
        `when`(gateControlService.sendReset(anyLong(), anyString())).thenReturn(true)

        val result = mockMvc.post("/gates/reset/execute") {
            param("locId", "1")
            param("grpId", "2")
            param("dtlIds", "1", "2")
            with(csrf())
        }.andExpect {
            status { is3xxRedirection() }
            flash { attributeExists("message") }
        }.andReturn()

        assert(!result.flashMap.containsKey("error")) { "성공 케이스인데 error 플래시가 함께 남았다: ${result.flashMap}" }
    }
}
