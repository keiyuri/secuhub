package kr.co.securance.secuhub.web.gate

import kr.co.securance.secuhub.domain.entity.GateDetail
import kr.co.securance.secuhub.domain.entity.GateGroup
import kr.co.securance.secuhub.domain.entity.GateLocation
import kr.co.securance.secuhub.web.menu.MenuProvider
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

// 같은 패키지의 GateControlApiControllerTest가 이미 @SpringBootConfiguration 최소 설정
// (TestGateControlWebApp)을 두고 있다 — @WebMvcTest는 패키지 기준으로 위로 올라가며 찾으므로
// 여기서 하나 더 선언하면 "Found multiple @SpringBootConfiguration" 충돌이 난다(실측). 그걸
// 그대로 재사용한다.

/**
 * Codex adversarial-review 지적(2026-08-21) 검증용 — locations.html/groups.html/details.html의
 * 모달 자동 오픈 `<script th:if="... or #fields.hasErrors('form.*')">`가 `th:object="${form}"`
 * 범위 밖(폼 태그의 형제)에 있어, editingId가 없는 정상 GET에서도 BindingResult 부재로
 * TemplateProcessingException(HTTP 500)이 날 수 있다는 주장. 실제 MockMvc로 템플릿을 렌더링해
 * 검증한다 — 세 화면 모두 정상 GET에서 200이면 이 주장은 근거가 없다(Spring BindStatus가
 * BindingResult 부재 시 평범한 모델 객체로 폴백하기 때문).
 */
@WebMvcTest(GateLocationController::class, GateGroupController::class, GateDetailController::class)
@Import(GateLocationController::class, GateGroupController::class, GateDetailController::class)
class GateFieldsErrorsRenderingTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var menuProvider: MenuProvider

    @MockitoBean
    private lateinit var locationService: GateLocationService

    @MockitoBean
    private lateinit var groupService: GateGroupService

    @MockitoBean
    private lateinit var detailService: GateDetailService

    @MockitoBean
    private lateinit var gateTypeCodeService: GateTypeCodeService

    @Test
    @WithMockUser
    fun `위치 목록 정상 GET은 500 없이 렌더링된다`() {
        `when`(menuProvider.menu()).thenReturn(emptyList())
        `when`(locationService.findAllForManagement(false)).thenReturn(emptyList())

        mockMvc.get("/gates/locations") { with(csrf()) }.andExpect {
            status { isOk() }
        }
    }

    @Test
    @WithMockUser
    fun `게이트그룹 목록 정상 GET은 500 없이 렌더링된다`() {
        `when`(menuProvider.menu()).thenReturn(emptyList())
        `when`(locationService.findAllActive()).thenReturn(emptyList())
        `when`(groupService.findAllForManagement(null, false)).thenReturn(emptyList())

        mockMvc.get("/gates/groups") { with(csrf()) }.andExpect {
            status { isOk() }
        }
    }

    @Test
    @WithMockUser
    fun `레인 목록은 grpId 없이도 500 없이 렌더링된다`() {
        `when`(menuProvider.menu()).thenReturn(emptyList())
        `when`(locationService.findAllActive()).thenReturn(emptyList())
        `when`(groupService.findAllActiveByLocation(null)).thenReturn(emptyList())
        `when`(gateTypeCodeService.gateTypes()).thenReturn(emptyList())
        `when`(gateTypeCodeService.gateTypeNames()).thenReturn(emptyMap())
        `when`(detailService.findAllForManagement(null, false)).thenReturn(emptyList())

        mockMvc.get("/gates/details") { with(csrf()) }.andExpect {
            status { isOk() }
        }
    }

    @Test
    @WithMockUser
    fun `레인 목록은 grpId가 선택된 상태에서도 500 없이 렌더링된다`() {
        `when`(menuProvider.menu()).thenReturn(emptyList())
        `when`(locationService.findAllActive()).thenReturn(emptyList())
        `when`(groupService.findAllActiveByLocation(null)).thenReturn(emptyList())
        `when`(gateTypeCodeService.gateTypes()).thenReturn(emptyList())
        `when`(gateTypeCodeService.gateTypeNames()).thenReturn(emptyMap())
        `when`(detailService.findAllForManagement(1L, false)).thenReturn(emptyList())

        mockMvc.get("/gates/details") {
            param("grpId", "1")
            with(csrf())
        }.andExpect {
            status { isOk() }
        }
    }

    // 아래 3개는 fragments/modal-auto-open.html 추출(2026-08-21) 검증용 — 지금까지의 테스트는
    // editingId가 없는 "정상 GET"(모달 자동 오픈 조건이 false)만 다뤘다. /edit GET으로 진입해
    // editingId가 채워진 상태(조건 true)로 렌더링될 때, 프래그먼트가 화면별로 올바른 modalId를
    // 스크립트에 실제로 주입하는지를 응답 본문 문자열로 확인한다.

    @Test
    @WithMockUser
    fun `위치 수정 진입 시 locationFormModal을 여는 자동 오픈 스크립트가 렌더링된다`() {
        `when`(menuProvider.menu()).thenReturn(emptyList())
        `when`(locationService.findAllForManagement(false)).thenReturn(emptyList())
        `when`(locationService.findByIdOrNull(1L)).thenReturn(GateLocation(locId = 1L, locName = "테스트위치"))

        mockMvc.get("/gates/locations/1/edit") { with(csrf()) }.andExpect {
            status { isOk() }
            content { string(containsString("getElementById(\"locationFormModal\")")) }
        }
    }

    @Test
    @WithMockUser
    fun `게이트그룹 수정 진입 시 groupFormModal을 여는 자동 오픈 스크립트가 렌더링된다`() {
        `when`(menuProvider.menu()).thenReturn(emptyList())
        val location = GateLocation(locId = 1L, locName = "테스트위치")
        `when`(locationService.findAllActive()).thenReturn(emptyList())
        `when`(groupService.findAllForManagement(1L, false)).thenReturn(emptyList())
        `when`(groupService.findByIdOrNull(1L)).thenReturn(
            GateGroup(grpId = 1L, location = location, grpName = "테스트그룹"),
        )

        mockMvc.get("/gates/groups/1/edit") { with(csrf()) }.andExpect {
            status { isOk() }
            content { string(containsString("getElementById(\"groupFormModal\")")) }
        }
    }

    @Test
    @WithMockUser
    fun `레인 수정 진입 시 detailFormModal을 여는 자동 오픈 스크립트가 렌더링된다`() {
        `when`(menuProvider.menu()).thenReturn(emptyList())
        `when`(gateTypeCodeService.gateTypes()).thenReturn(emptyList())
        `when`(gateTypeCodeService.gateTypeNames()).thenReturn(emptyMap())
        `when`(locationService.findAllActive()).thenReturn(emptyList())
        `when`(groupService.findAllActiveByLocation(null)).thenReturn(emptyList())
        `when`(detailService.findAllForManagement(1L, false)).thenReturn(emptyList())
        val location = GateLocation(locId = 1L, locName = "테스트위치")
        val group = GateGroup(grpId = 1L, location = location, grpName = "테스트그룹")
        `when`(detailService.findByIdOrNull(1L)).thenReturn(
            GateDetail(dtlId = 1L, location = location, group = group, dtlIp = "192.168.0.1", dtlLaneNo = 0, dtlType = 1),
        )

        mockMvc.get("/gates/details/1/edit") { with(csrf()) }.andExpect {
            status { isOk() }
            content { string(containsString("getElementById(\"detailFormModal\")")) }
        }
    }
}
