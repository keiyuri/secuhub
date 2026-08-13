package kr.co.securance.secuhub.web.security

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.WebApplicationContext

// 실제 화면 컨트롤러(Thymeleaf 렌더링)에 얽매이지 않고 SecurityConfig의 경로별 인가 규칙만
// 검증하기 위한 최소 픽스처 컨트롤러. login, dashboard, admin 하위 경로, control 하위 경로,
// vendor/js/css 정적 리소스 경로는 SecurityConfig가 실제로 매칭하는 패턴과 동일하게 맞춘다.
// (주의: Kotlin block comment는 중첩을 지원해 "/*"로 시작하는 시퀀스가 나오면 새 중첩 주석을
//  여는 것으로 처리된다 — KDoc(/** */) 안에 경로 glob 패턴을 문자 그대로 적으면 컴파일 에러가
//  나므로 line comment로 대체했다.)
@RestController
class SecurityTestFixtureController {
    @GetMapping("/login") fun login() = "login-ok"
    @GetMapping("/dashboard") fun dashboard() = "dashboard-ok"
    @GetMapping("/admin/probe") fun admin() = "admin-ok"
    @GetMapping("/control/probe") fun control() = "control-ok"
    @GetMapping("/vendor/probe") fun vendor() = "vendor-ok"
    @GetMapping("/js/probe") fun js() = "js-ok"
    @GetMapping("/css/probe") fun css() = "css-ok"

    // Major #10(2026-08-13 코드 리뷰) 회귀 방지용 — 실제 GateControlController(/gates/details/{id}/mode)
    // 와 마스터 데이터 컨트롤러(GateGroupController, /gates/groups)의 경로 형태를 그대로 흉내낸다.
    @PostMapping("/gates/details/{dtlId}/mode") fun gateMode(): String = "gate-mode-ok"
    @PostMapping("/gates/groups") fun gateGroupCreate(): String = "gate-group-ok"
}

// securance-web 모듈에는 @SpringBootApplication 메인 클래스가 없다(그건 securance-app에 있다).
// 패키지 스캔에 의존하는 대신, 이 테스트에 필요한 빈만 명시적으로 @Import해서 실제
// SecurityConfig(운영 인가 규칙)와 위 픽스처 컨트롤러만으로 구성된 최소 컨텍스트를 만든다.
@SpringBootConfiguration
// securance-domain(JPA)이 클래스패스에 있으므로 기본 자동설정을 그대로 두면 실제 DataSource를
// 찾으려 한다 — 이 테스트는 인가 규칙만 검증하므로 DB/JPA 자동설정은 제외한다.
@EnableAutoConfiguration(exclude = [DataSourceAutoConfiguration::class, HibernateJpaAutoConfiguration::class])
@EnableWebSecurity
@Import(SecurityConfig::class, SecurityTestFixtureController::class)
private class TestSecurityApp

@SpringBootTest(classes = [TestSecurityApp::class], webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class SecurityConfigTest {

    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        // @AutoConfigureMockMvc의 자동 감지 대신, spring-security-test의 springSecurity() 구성기를
        // MockMvc에 명시적으로 적용한다 — 이렇게 해야 @WithMockUser로 심어둔 인증 정보가 실제
        // SecurityFilterChain(SecurityConfig가 정의한 필터 체인)에서 인식된다.
        val builder = MockMvcBuilders.webAppContextSetup(webApplicationContext)
        builder.apply<DefaultMockMvcBuilder>(springSecurity())
        mockMvc = builder.build()
    }

    @Test
    fun `login 페이지는 인증 없이 접근할 수 있다`() {
        mockMvc.get("/login").andExpect { status { isOk() } }
    }

    @Test
    fun `정적 리소스 경로는 인증 없이 접근할 수 있다`() {
        mockMvc.get("/vendor/probe").andExpect { status { isOk() } }
        mockMvc.get("/js/probe").andExpect { status { isOk() } }
        mockMvc.get("/css/probe").andExpect { status { isOk() } }
    }

    @Test
    fun `인증 없이 대시보드에 접근하면 로그인 페이지로 리다이렉트된다`() {
        mockMvc.get("/dashboard").andExpect {
            status { is3xxRedirection() }
        }
    }

    @Test
    @WithMockUser(roles = ["VIEW"])
    fun `ROLE_VIEW가 있으면 대시보드에 접근할 수 있다`() {
        mockMvc.get("/dashboard").andExpect { status { isOk() } }
    }

    @Test
    @WithMockUser(roles = [])
    fun `인증만 되고 ROLE_VIEW가 없으면 대시보드에서 거부된다`() {
        // 회귀 방지 테스트(적대적 리뷰 지적): 예전에는 anyRequest -> authenticated였다 — auth_view/
        // ctrl/admin이 전부 'N'인(어떤 권한도 없는) 계정도 인증만 되면 대시보드를 포함한 모든 경로에
        // 접근할 수 있었다. 이제는 최소한 ROLE_VIEW가 있어야 한다.
        mockMvc.get("/dashboard").andExpect { status { isForbidden() } }
    }

    @Test
    @WithMockUser(roles = ["VIEW"])
    fun `ROLE_ADMIN이 없는 사용자는 admin 경로에서 거부된다`() {
        // 회귀 방지 테스트: 예전에는 인증만 되면 어떤 역할이든 모든 경로에 접근할 수 있었다
        // (SecurityConfig에 경로별 hasRole 규칙이 없었음).
        mockMvc.get("/admin/probe").andExpect { status { isForbidden() } }
    }

    @Test
    @WithMockUser(roles = ["ADMIN"])
    fun `ROLE_ADMIN 사용자는 admin 경로에 접근할 수 있다`() {
        mockMvc.get("/admin/probe").andExpect { status { isOk() } }
    }

    @Test
    @WithMockUser(roles = ["VIEW"])
    fun `ROLE_CONTROL이 없는 사용자는 control 경로에서 거부된다`() {
        mockMvc.get("/control/probe").andExpect { status { isForbidden() } }
    }

    @Test
    @WithMockUser(roles = ["CONTROL"])
    fun `ROLE_CONTROL 사용자는 control 경로에 접근할 수 있다`() {
        mockMvc.get("/control/probe").andExpect { status { isOk() } }
    }

    @Test
    @WithMockUser(roles = ["ADMIN"])
    fun `ROLE_ADMIN만으로는 control 경로에 접근할 수 없다`() {
        // 두 역할은 독립적이다 — admin 권한이 자동으로 control 권한을 포함하지 않는다.
        mockMvc.get("/control/probe").andExpect { status { isForbidden() } }
    }

    @Test
    @WithMockUser(roles = ["CONTROL"])
    fun `ROLE_CONTROL 사용자는 실제 게이트 제어 엔드포인트를 호출할 수 있다`() {
        mockMvc.post("/gates/details/1/mode") { with(csrf()) }.andExpect { status { isOk() } }
    }

    @Test
    @WithMockUser(roles = ["CONTROL"])
    fun `ROLE_CONTROL 사용자는 마스터 데이터(게이트 그룹) CRUD에는 접근할 수 없다`() {
        // 회귀 방지 테스트(2026-08-13 코드 리뷰 Major #10): "/gates/**" POST 전체가 ROLE_CONTROL로
        // 열려 있던 시절에는 제어 권한만 가진 계정도 마스터 데이터(위치/그룹/게이트 등록)를
        // 만들거나 지울 수 있었다. 이제는 마스터 데이터 CRUD에 ROLE_ADMIN이 필요하다.
        mockMvc.post("/gates/groups") { with(csrf()) }.andExpect { status { isForbidden() } }
    }

    @Test
    @WithMockUser(roles = ["ADMIN"])
    fun `ROLE_ADMIN 사용자는 마스터 데이터(게이트 그룹) CRUD에 접근할 수 있다`() {
        mockMvc.post("/gates/groups") { with(csrf()) }.andExpect { status { isOk() } }
    }
}
