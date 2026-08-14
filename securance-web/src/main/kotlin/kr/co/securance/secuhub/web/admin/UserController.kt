package kr.co.securance.secuhub.web.admin

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import kr.co.securance.secuhub.domain.entity.AppUser
import kr.co.securance.secuhub.domain.repository.AppUserRepository
import kr.co.securance.secuhub.web.menu.MenuProvider
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Controller
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.ui.Model
import org.springframework.validation.BindingResult
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.servlet.mvc.support.RedirectAttributes

/**
 * #11 SR_F_ViewUser — 사용자 계정 CRUD.
 * 레거시 `sAuthCtrl`/`sAuthAdmin` 전역 변수 대신 `AppUser.authView/authControl/authAdmin` +
 * Spring Security `@PreAuthorize`/URL 규칙(`SecurityConfig`)으로 권한을 관리한다
 * (계획서 `SR_Speed_Client_전환_계획.md` 5절 공통 패턴).
 */
@Service
class UserManagementService(
    private val userRepository: AppUserRepository,
    private val passwordEncoder: PasswordEncoder,
) {
    fun findAll(): List<AppUser> = userRepository.findAll(org.springframework.data.domain.Sort.by("userId"))

    fun findByIdOrNull(userId: String): AppUser? = userRepository.findById(userId).orElse(null)

    @Transactional
    fun create(form: UserForm) {
        require(!userRepository.existsById(form.userId)) { "이미 존재하는 아이디입니다: ${form.userId}" }
        userRepository.save(
            AppUser(
                userId = form.userId,
                passwordHash = requireNotNull(passwordEncoder.encode(form.password)),
                userName = form.userName,
                useYn = form.useYn,
                authView = form.authView,
                authControl = form.authControl,
                authAdmin = form.authAdmin,
            ),
        )
    }

    @Transactional
    fun update(userId: String, form: UserForm) {
        val user = userRepository.findById(userId).orElseThrow {
            NoSuchElementException("사용자를 찾을 수 없습니다: $userId")
        }
        user.userName = form.userName
        user.useYn = form.useYn
        user.authView = form.authView
        user.authControl = form.authControl
        user.authAdmin = form.authAdmin
        // 비밀번호는 입력값이 있을 때만 갱신 — 수정 화면에서 매번 새 비밀번호를 요구하면
        // 관리자가 다른 필드만 고치려 해도 매번 비밀번호를 재입력해야 하는 불편이 생긴다.
        if (form.password.isNotBlank()) {
            user.passwordHash = requireNotNull(passwordEncoder.encode(form.password))
        }
    }

    @Transactional
    fun delete(userId: String, currentUserId: String?) {
        require(userId != currentUserId) { "로그인 중인 계정은 삭제할 수 없습니다." }
        userRepository.deleteById(userId)
    }
}

data class UserForm(
    @field:NotBlank(message = "아이디는 필수입니다")
    @field:Size(max = 20, message = "아이디는 20자 이하여야 합니다")
    var userId: String = "",
    /**
     * 등록 시 필수, 수정 시 비워두면 기존 비밀번호 유지(빈 문자열은 통과시켜야 하므로
     * [NotBlank]를 걸 수 없다 — 신규 등록 시 빈 값 거부는 [UserController.create]에서 별도 처리).
     *
     * 길이(8~64자) + 최소 복잡도(영문자 1개 이상, 숫자 1개 이상)를 검증한다(코드 리뷰 지적,
     * 2026-08-14: 길이만 검증하면 "aaaaaaaa"처럼 사전 공격에 취약한 비밀번호도 그대로 통과했다).
     */
    @field:Pattern(
        regexp = "^$|^(?=.*[A-Za-z])(?=.*\\d).{8,64}$",
        message = "비밀번호는 영문자와 숫자를 포함해 8자 이상 64자 이하여야 합니다",
    )
    var password: String = "",
    @field:NotBlank(message = "이름은 필수입니다")
    var userName: String = "",
    var useYn: Boolean = true,
    var authView: Boolean = true,
    var authControl: Boolean = false,
    var authAdmin: Boolean = false,
)

@Controller
@RequestMapping("/admin/users")
class UserController(
    private val userManagementService: UserManagementService,
    private val menuProvider: MenuProvider,
) {
    @GetMapping
    fun list(model: Model): String {
        populateCommon(model)
        model.addAttribute("form", UserForm())
        return "admin/users"
    }

    @GetMapping("/{userId}/edit")
    fun edit(@PathVariable userId: String, model: Model): String {
        val user = userManagementService.findByIdOrNull(userId) ?: return "redirect:/admin/users"
        populateCommon(model)
        model.addAttribute("editingId", userId)
        model.addAttribute(
            "form",
            UserForm(
                userId = user.userId,
                userName = user.userName,
                useYn = user.useYn,
                authView = user.authView,
                authControl = user.authControl,
                authAdmin = user.authAdmin,
            ),
        )
        return "admin/users"
    }

    @PostMapping
    fun create(
        @Validated @ModelAttribute("form") form: UserForm,
        binding: BindingResult,
        model: Model,
        redirectAttributes: RedirectAttributes,
    ): String {
        if (form.password.isBlank()) {
            binding.rejectValue("password", "required", "신규 등록 시 비밀번호는 필수입니다")
        }
        if (binding.hasErrors()) {
            populateCommon(model)
            return "admin/users"
        }
        userManagementService.create(form)
        redirectAttributes.addFlashAttribute("message", "사용자가 등록되었습니다.")
        return "redirect:/admin/users"
    }

    @PostMapping("/{userId}")
    fun update(
        @PathVariable userId: String,
        @Validated @ModelAttribute("form") form: UserForm,
        binding: BindingResult,
        model: Model,
        redirectAttributes: RedirectAttributes,
    ): String {
        if (binding.hasErrors()) {
            populateCommon(model)
            model.addAttribute("editingId", userId)
            return "admin/users"
        }
        userManagementService.update(userId, form)
        redirectAttributes.addFlashAttribute("message", "사용자 정보가 수정되었습니다.")
        return "redirect:/admin/users"
    }

    @PostMapping("/{userId}/delete")
    fun delete(@PathVariable userId: String, redirectAttributes: RedirectAttributes): String {
        val currentUserId = SecurityContextHolder.getContext().authentication?.name
        runCatching { userManagementService.delete(userId, currentUserId) }
            .onSuccess { redirectAttributes.addFlashAttribute("message", "사용자가 삭제되었습니다.") }
            .onFailure { redirectAttributes.addFlashAttribute("error", it.message) }
        return "redirect:/admin/users"
    }

    private fun populateCommon(model: Model) {
        model.addAttribute("menu", menuProvider.menu())
        model.addAttribute("pageTitle", "사용자 관리")
        model.addAttribute("users", userManagementService.findAll())
    }
}
