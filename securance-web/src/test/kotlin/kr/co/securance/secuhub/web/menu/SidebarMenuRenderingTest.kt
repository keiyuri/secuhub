package kr.co.securance.secuhub.web.menu

import kr.co.securance.secuhub.web.gate.GateLocationController
import kr.co.securance.secuhub.web.gate.GateLocationService
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

// AccessReportControllerTest와 동일한 사정(securance-web에는 @SpringBootApplication이 없음) —
// 그 파일의 TestReportWebApp과 같은 최소 설정을 이 패키지에도 하나 둔다.
@SpringBootConfiguration
@EnableAutoConfiguration
private class TestMenuWebApp

/**
 * [sidebar.html] 재귀 프래그먼트 gathering 버그(2026-08-19, 작업일지 0048) 회귀 테스트.
 *
 * `MenuNode.Group`의 하위 항목을 `th:insert`로 자기 자신을 재귀 호출해 렌더링하던 이전 구현은
 * 실제로 하위 목록이 완전히 비어 렌더링됐다(사이드바 "게이트 관리" 클릭 시 토글은 되는데 하위
 * 7개 항목이 하나도 안 보이는 문제 — 사용자가 실제 화면의 DOM을 확인해 발견). 이 테스트는
 * `AccessReportControllerTest`가 일부러 우회했던 바로 그 경로("사이드바 재귀 렌더링 자체")를
 * 직접 검증한다 — `MenuProvider`를 `Group(children=[Item, Item])`으로 목킹해, 응답 HTML에
 * 하위 항목의 `data-popup` 링크가 실제로 나오는지 확인한다.
 *
 * `GateLocationController`(`/gates/locations`)를 대표 화면으로 쓴 이유는 의존성이
 * `GateLocationService` + `MenuProvider`뿐이라 슬라이스 테스트 배선이 가장 단순하기 때문이다.
 */
@WebMvcTest(GateLocationController::class)
@Import(GateLocationController::class)
class SidebarMenuRenderingTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var locationService: GateLocationService

    @MockitoBean
    private lateinit var menuProvider: MenuProvider

    @Test
    @WithMockUser
    fun `Group 메뉴의 하위 Item이 팝업 링크로 렌더링된다`() {
        `when`(menuProvider.menu()).thenReturn(
            listOf(
                MenuNode.Group(
                    "게이트 관리",
                    icon = "bi-door-open",
                    children = listOf(
                        MenuNode.Item("위치", "/gates/locations", popup = true),
                        MenuNode.Item("게이트그룹", "/gates/groups", popup = true),
                    ),
                ),
            ),
        )
        `when`(locationService.findAll()).thenReturn(emptyList())

        val html = mockMvc.get("/gates/locations") { with(csrf()) }
            .andExpect { status { isOk() } }
            .andReturn().response.contentAsString

        // 재귀 프래그먼트 gathering 버그가 재발하면 <ul class="nav nav-treeview"> 안이 비어
        // 이 두 링크가 사라진다 — 그때는 이 assert가 실패해 회귀를 잡아낸다.
        assert(html.contains("""href="/gates/locations"""") && html.contains("""data-popup="true" data-popup-title="위치"""")) {
            "게이트 관리 하위 '위치' 항목이 렌더링되지 않았다(재귀 프래그먼트 gathering 회귀 의심)"
        }
        assert(html.contains("""href="/gates/groups"""") && html.contains("""data-popup="true" data-popup-title="게이트그룹"""")) {
            "게이트 관리 하위 '게이트그룹' 항목이 렌더링되지 않았다(재귀 프래그먼트 gathering 회귀 의심)"
        }
    }

    @Test
    @WithMockUser
    fun `Group 하위에 Item이 아닌 노드가 섞여 있어도 렌더링이 깨지지 않는다`() {
        // MenuNode.Group.children은 List<MenuNode>라 컴파일러 수준에서는 Header/중첩 Group도
        // 허용된다 — sidebar.html의 instanceof 가드(child instanceof T(...Item))가 이런 값을
        // 조용히 건너뛰는지, 즉 존재하지 않는 프로퍼티(child.href 등) 접근으로 렌더링 예외를
        // 던지지 않는지 확인한다.
        `when`(menuProvider.menu()).thenReturn(
            listOf(
                MenuNode.Group(
                    "게이트 관리",
                    children = listOf(
                        MenuNode.Header("구분선"),
                        MenuNode.Item("위치", "/gates/locations", popup = true),
                    ),
                ),
            ),
        )
        `when`(locationService.findAll()).thenReturn(emptyList())

        mockMvc.get("/gates/locations") { with(csrf()) }.andExpect { status { isOk() } }
    }
}
