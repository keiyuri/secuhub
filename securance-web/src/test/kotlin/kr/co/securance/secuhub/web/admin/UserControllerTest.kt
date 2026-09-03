package kr.co.securance.secuhub.web.admin

import kr.co.securance.secuhub.domain.entity.AppUser
import kr.co.securance.secuhub.web.menu.MenuProvider
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.Mockito
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

// AccessReportControllerTest와 동일한 사정(securance-web에는 @SpringBootApplication이 없다) —
// @WebMvcTest가 기준으로 삼을 @SpringBootConfiguration을 이 패키지(admin)에 최소로 하나 둔다.
@SpringBootConfiguration
@EnableAutoConfiguration
private class TestAdminWebApp

/**
 * [UserController] HTTP 레벨 검증(2026-08-25 소스 전수 검토 지적 — 내부 서비스
 * [UserManagementService]는 `UserManagementServiceTest`로 커버되지만, 검증 실패/중복 아이디/
 * 자기 자신 삭제 방지 같은 분기를 잇는 이 컨트롤러 자체는 어떤 테스트에서도 인스턴스화된 적이
 * 없었다).
 */
@WebMvcTest(UserController::class)
@Import(UserController::class)
class UserControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var userManagementService: UserManagementService

    @MockitoBean
    private lateinit var menuProvider: MenuProvider

    // admin/users.html이 @passwordEncoder.upgradeEncoding(...)을 직접 빈 참조로 호출한다
    // (2026-09-03 병합 후 재발견 — @WebMvcTest 슬라이스에는 실제 PasswordEncoder 빈이 없어
    // NoSuchBeanDefinitionException으로 렌더링이 깨졌었다). upgradeEncoding은 스텁하지 않아도
    // Mockito 기본값 false로 충분하다(평문/BCrypt 배지 분기 자체는 UserManagementServiceTest가
    // 이미 커버).
    @MockitoBean(name = "passwordEncoder")
    private lateinit var passwordEncoder: PasswordEncoder

    private fun sampleUser(userId: String) = AppUser(
        userId = userId,
        passwordHash = "hash",
        userName = "테스트",
    )

    @Test
    @WithMockUser
    fun `showInactive 파라미터를 서비스 조회에 그대로 전달한다`() {
        `when`(menuProvider.menu()).thenReturn(emptyList())
        `when`(userManagementService.findAll(true)).thenReturn(listOf(sampleUser("admin")))

        val result = mockMvc.get("/admin/users") {
            param("showInactive", "true")
            with(csrf())
        }.andExpect {
            status { isOk() }
            model { attribute("showInactive", true) }
        }.andReturn()

        // AppUser는 data class가 아니라 참조 동등성만 비교되므로, 모델에 실제로 담긴 리스트의
        // userId만 비교해 findAll(true) 호출 결과가 그대로 전달됐는지 확인한다.
        @Suppress("UNCHECKED_CAST")
        val users = result.modelAndView?.model?.get("users") as List<AppUser>
        assert(users.map { it.userId } == listOf("admin")) { "findAll(true) 결과가 모델에 그대로 전달되지 않았다: $users" }
    }

    @Test
    @WithMockUser
    fun `신규 등록 시 비밀번호를 비우면 바인딩 오류로 화면에 머문다`() {
        `when`(menuProvider.menu()).thenReturn(emptyList())
        `when`(userManagementService.findAll(anyBoolean())).thenReturn(emptyList())

        mockMvc.post("/admin/users") {
            param("userId", "newbie")
            param("password", "")
            param("userName", "신규")
            with(csrf())
        }.andExpect {
            status { isOk() } // 리다이렉트가 아니라 등록 화면을 다시 렌더링해야 한다.
            view { name("admin/users") }
        }
    }

    @Test
    @WithMockUser
    fun `아이디가 중복되면 필드 오류로 변환되어 화면에 머물고 500으로 새지 않는다`() {
        `when`(menuProvider.menu()).thenReturn(emptyList())
        `when`(userManagementService.findAll(anyBoolean())).thenReturn(emptyList())
        Mockito.doThrow(IllegalArgumentException("이미 존재하는 아이디입니다: dup"))
            .`when`(userManagementService).create(anyForm())

        mockMvc.post("/admin/users") {
            param("userId", "dup")
            param("password", "pw123456")
            param("userName", "중복")
            with(csrf())
        }.andExpect {
            status { isOk() }
            view { name("admin/users") }
        }
    }

    @Test
    @WithMockUser
    fun `등록에 성공하면 목록으로 리다이렉트되고 성공 메시지가 남는다`() {
        mockMvc.post("/admin/users") {
            param("userId", "newbie")
            param("password", "pw123456")
            param("userName", "신규")
            with(csrf())
        }.andExpect {
            status { is3xxRedirection() }
            redirectedUrl("/admin/users?showInactive=false")
            flash { attributeExists("message") }
        }
    }

    @Test
    @WithMockUser(username = "self")
    fun `로그인 중인 계정을 삭제하려 하면 서비스가 거부하고 오류 메시지가 남는다`() {
        Mockito.doThrow(IllegalArgumentException("로그인 중인 계정은 삭제할 수 없습니다."))
            .`when`(userManagementService).delete("self", "self")

        val result = mockMvc.post("/admin/users/self/delete") {
            with(csrf())
        }.andExpect {
            status { is3xxRedirection() }
            redirectedUrl("/admin/users?showInactive=false")
            flash { attributeExists("error") }
        }.andReturn()

        assert(!result.flashMap.containsKey("message")) { "삭제 거부 케이스인데 성공 메시지가 함께 남았다: ${result.flashMap}" }
    }
}

// Kotlin에서 Mockito의 Java any(Class)는 null을 반환하는데, Kotlin이 그 반환값을 비-nullable
// UserForm으로 취급해 즉시 null 체크 예외를 던진다 — AccessReportControllerTest.anyOf<T>()와
// 동일한 표준 우회법을 그대로 따른다. 반환 타입을 구체 타입(UserForm)으로 바로 캐스트하면
// (제네릭이 아니라 실제 CHECKCAST가 남아) 캐스트 자체가 실패해 남은 매처가 Mockito의 스레드로컬
// 스택에 미해소 상태로 남고, 그 오염이 이후 실행되는 다른 테스트의 스터빙까지 깨뜨린다(실측 —
// 최초 구현이 정확히 이 버그였다) — 제네릭 T를 거쳐야 소거로 인해 CHECKCAST 없이 통과한다.
private fun <T> anyRef(): T {
    Mockito.any<T>()
    @Suppress("UNCHECKED_CAST")
    return null as T
}
private fun anyForm(): UserForm = anyRef()
