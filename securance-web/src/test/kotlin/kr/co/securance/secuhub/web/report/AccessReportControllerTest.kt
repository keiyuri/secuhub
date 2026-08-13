package kr.co.securance.secuhub.web.report

import kr.co.securance.secuhub.web.common.ExcelExportService
import kr.co.securance.secuhub.web.gate.GateGroupService
import kr.co.securance.secuhub.web.gate.GateLocationService
import kr.co.securance.secuhub.web.menu.MenuProvider
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito
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
import java.time.LocalDate

// securance-web 모듈에는 @SpringBootApplication 메인 클래스가 없다(SecurityConfigTest와 동일한
// 사정 — 그건 securance-app에 있다). @WebMvcTest는 테스트 클래스 패키지 기준으로 위로 올라가며
// @SpringBootConfiguration을 찾으므로, 이 패키지(report) 안에 최소 설정을 하나 둔다.
@SpringBootConfiguration
@EnableAutoConfiguration
private class TestReportWebApp

/**
 * [AccessReportController] HTTP 레벨 검증(2026-08-13 코드 리뷰 지적 — `securance-web` 컨트롤러
 * 계층에 MockMvc 테스트가 전무했다). 서비스 단위 테스트로는 URL 매핑 실수나 인가 규칙과 실제
 * 라우팅의 불일치를 잡을 수 없어, 대표 사례로 이 컨트롤러에 HTTP 레벨 커버리지를 추가한다.
 *
 * 시큐리티 필터(로그인/역할 검증) 자체는 [SecurityConfigTest] 등에서 이미 충분히 커버되므로
 * 여기서는 실제 `SecurityConfig`를 갖다 쓰지 않는다. 대신 `dashboard-layout` 템플릿이 항상
 * `_csrf.token`을 참조하므로(thymeleaf-extras-springsecurity6), 실제 `CsrfFilter` 없이도
 * spring-security-test의 `csrf()` 요청 후처리기로 `_csrf` 요청 속성만 채워 렌더링이 깨지지
 * 않게 한다.
 */
// TestReportWebApp이 @SpringBootApplication이 아니라 @EnableAutoConfiguration뿐이라
// @AutoConfigurationPackage가 없다 — 그래서 @WebMvcTest(AccessReportController::class)만으로는
// 컨트롤러를 스캔할 기준 패키지가 없어 빈이 전혀 등록되지 않았다(실제로 겪은 실패: 요청이
// AccessReportController가 아니라 정적 리소스 핸들러로 떨어져 404가 났다). 컨트롤러 빈을
// 명시적으로 @Import해 스캔에 의존하지 않게 한다.
@WebMvcTest(AccessReportController::class)
@Import(AccessReportController::class)
class AccessReportControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var accessReportService: AccessReportService

    @MockitoBean
    private lateinit var locationService: GateLocationService

    @MockitoBean
    private lateinit var groupService: GateGroupService

    @MockitoBean
    private lateinit var excelExportService: ExcelExportService

    // 사이드바(fragments/sidebar.html)는 MenuNode 판별 유니온을 SpringEL instanceof(T(...))로
    // 재귀 렌더링하는데, 이 컨트롤러 슬라이스 테스트에서는 그 재귀 렌더링 자체가 검증 대상이
    // 아니다 — 빈 메뉴로 대체해 이 테스트의 관심사(라우팅/모델/기간 상한)만 남긴다.
    @MockitoBean
    private lateinit var menuProvider: MenuProvider

    private val emptyResult = AccessReportResult(rows = emptyList(), totalIn = 0, totalOut = 0, totalDoor = 0)

    @Test
    @WithMockUser
    fun `위치 그룹 파라미터 없이 조회하면 화면만 렌더링되고 서비스는 호출되지 않는다`() {
        `when`(menuProvider.menu()).thenReturn(emptyList())
        `when`(locationService.findAll()).thenReturn(emptyList())
        `when`(groupService.findByLocation(null)).thenReturn(emptyList())

        mockMvc.get("/reports/access") { with(csrf()) }.andExpect {
            status { isOk() }
            model { attributeDoesNotExist("result") }
        }
    }

    @Test
    @WithMockUser
    fun `위치와 그룹을 지정하면 조회 결과를 모델에 담는다`() {
        `when`(menuProvider.menu()).thenReturn(emptyList())
        `when`(locationService.findAll()).thenReturn(emptyList())
        `when`(groupService.findByLocation(1L)).thenReturn(emptyList())
        `when`(accessReportService.search(anyLong(), anyLong(), anyLocalDate(), anyLocalDate()))
            .thenReturn(emptyResult)

        mockMvc.get("/reports/access") {
            param("locId", "1")
            param("grpId", "2")
            with(csrf())
        }.andExpect {
            status { isOk() }
            model { attributeExists("result") }
        }
    }

    @Test
    @WithMockUser
    fun `조회 기간이 3개월을 넘으면 상한으로 잘리고 안내 메시지가 뜬다`() {
        `when`(menuProvider.menu()).thenReturn(emptyList())
        `when`(locationService.findAll()).thenReturn(emptyList())
        `when`(groupService.findByLocation(1L)).thenReturn(emptyList())
        `when`(accessReportService.search(anyLong(), anyLong(), anyLocalDate(), anyLocalDate()))
            .thenReturn(emptyResult)

        val from = LocalDate.now().minusYears(1).toString()
        val result = mockMvc.get("/reports/access") {
            param("locId", "1")
            param("grpId", "2")
            param("fromDate", from)
            with(csrf())
        }.andExpect {
            status { isOk() }
            model { attributeExists("error") }
        }.andReturn()

        val boundedFrom = result.modelAndView?.model?.get("fromDate") as LocalDate
        // AccessReportController.resolveRange는 3개월 상한을 넘는 fromDate를 to.minusMonths(3)으로
        // 잘라내야 한다 — 원래 요청한 1년 전 날짜가 그대로 쓰이면 안 된다.
        assert(boundedFrom.isAfter(LocalDate.parse(from))) { "3개월 상한이 적용되지 않았다: $boundedFrom" }
    }
}

// Kotlin에서 Mockito의 Java any(Class)는 null을 반환하는데, Kotlin이 그 반환값을 비-nullable
// LocalDate로 취급해 즉시 null 체크 예외("any(...) must not be null")를 던진다 — 이 프로젝트는
// mockito-kotlin을 쓰지 않으므로, unchecked cast로 그 체크를 우회하는 표준 우회법을 쓴다.
// 반환 타입을 제네릭 T로 둬야 한다 — 구체 타입(LocalDate)에 대한 "as" 캐스트는 바이트코드에
// 실제 null 체크(CHECKCAST)를 남기지만, 소거되는 제네릭 T에 대한 캐스트는 Object 기준이라
// null이 그대로 통과한다.
private fun <T> anyOf(): T {
    Mockito.any<T>()
    @Suppress("UNCHECKED_CAST")
    return null as T
}
private fun anyLocalDate(): LocalDate = anyOf()
