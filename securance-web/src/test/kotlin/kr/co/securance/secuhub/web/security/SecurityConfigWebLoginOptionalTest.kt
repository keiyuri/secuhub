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
import org.springframework.test.context.TestPropertySource
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

// SecurityConfigTest와 동일한 최소 컨텍스트 구성 방식(같은 SecurityTestFixtureController 재사용).
@SpringBootConfiguration
@EnableAutoConfiguration(exclude = [DataSourceAutoConfiguration::class, HibernateJpaAutoConfiguration::class])
@EnableWebSecurity
@Import(SecurityConfig::class, SecurityTestFixtureController::class)
private class TestSecurityAppWebLoginOptional

/**
 * `securance.security.web-login-required=false`일 때 [SecurityConfig]의 `anyRequest` 규칙이
 * `permitAll`로 바뀌는지, 그리고 admin/control 등 이미 명시된 역할 기반 규칙은 이 설정과 무관하게
 * 그대로 유지되는지 검증한다(2026-08-12 사용자 확인 항목).
 */
@SpringBootTest(classes = [TestSecurityAppWebLoginOptional::class], webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@TestPropertySource(properties = ["securance.security.web-login-required=false"])
class SecurityConfigWebLoginOptionalTest {

    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        val builder = MockMvcBuilders.webAppContextSetup(webApplicationContext)
        builder.apply<DefaultMockMvcBuilder>(springSecurity())
        mockMvc = builder.build()
    }

    @Test
    fun `web-login-required가 false면 로그인 없이 대시보드에 접근할 수 있다`() {
        mockMvc.get("/dashboard").andExpect { status { isOk() } }
    }

    @Test
    fun `web-login-required가 false여도 admin 경로는 여전히 로그인 없이 거부된다`() {
        // anyRequest 규칙만 permitAll로 바뀔 뿐, /admin 하위의 hasRole(ADMIN) 규칙은 그대로다.
        mockMvc.get("/admin/probe").andExpect { status { is3xxRedirection() } }
    }
}
