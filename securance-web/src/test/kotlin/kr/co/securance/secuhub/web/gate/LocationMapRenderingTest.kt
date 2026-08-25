package kr.co.securance.secuhub.web.gate

import kr.co.securance.secuhub.domain.entity.GateGroup
import kr.co.securance.secuhub.domain.entity.GateLocation
import kr.co.securance.secuhub.web.menu.MenuProvider
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

/**
 * `/gates/locations/{id}/map` 실제 Thymeleaf 렌더링 검증(2026-08-25 Opus 재검토 지적).
 *
 * 삼항식 파싱 오류 자체는 [02b2925]에서 고쳤지만, 그 수정은 `grp.grpX != null`만 가드로 삼아
 * `location.locMapWidth`/`locMapHeight`(둘 다 nullable `Int?`)가 null인 레거시 데이터에서는
 * 여전히 SpEL이 null 산술(`grp.grpX * 100.0 / location.locMapWidth`)로 500을 던졌다 — 실제
 * MockMvc로 템플릿을 렌더링해 이 경로를 재현·고정한다.
 */
@WebMvcTest(LocationMapController::class)
@Import(LocationMapController::class)
class LocationMapRenderingTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var menuProvider: MenuProvider

    @MockitoBean
    private lateinit var locationService: GateLocationService

    @MockitoBean
    private lateinit var groupService: GateGroupService

    @Test
    @WithMockUser
    fun `배치도 크기가 없어도 그룹 좌표가 있는 위치 배치도는 500 없이 렌더링된다`() {
        // loc_map은 있지만 loc_map_w/h가 채워지지 않은 레거시 이관 데이터 재현.
        val location = GateLocation(locId = 1L, locName = "테스트위치", locMap = "map.png", locMapWidth = null, locMapHeight = null)
        val group = GateGroup(grpId = 1L, location = location, grpName = "테스트그룹", gateTypeCode = 1, grpX = 100, grpY = 50)

        `when`(menuProvider.menu()).thenReturn(emptyList())
        `when`(locationService.findByIdOrNull(1L)).thenReturn(location)
        `when`(groupService.findAllActiveByLocation(1L)).thenReturn(listOf(group))

        mockMvc.get("/gates/locations/1/map") { with(csrf()) }.andExpect {
            status { isOk() }
        }
    }

    @Test
    @WithMockUser
    fun `배치도 크기와 그룹 좌표가 모두 있으면 500 없이 렌더링된다`() {
        val location = GateLocation(locId = 1L, locName = "테스트위치", locMap = "map.png", locMapWidth = 800, locMapHeight = 600)
        val group = GateGroup(grpId = 1L, location = location, grpName = "테스트그룹", gateTypeCode = 1, grpX = 100, grpY = 50)

        `when`(menuProvider.menu()).thenReturn(emptyList())
        `when`(locationService.findByIdOrNull(1L)).thenReturn(location)
        `when`(groupService.findAllActiveByLocation(1L)).thenReturn(listOf(group))

        mockMvc.get("/gates/locations/1/map") { with(csrf()) }.andExpect {
            status { isOk() }
        }
    }
}
