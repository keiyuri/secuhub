package kr.co.securance.secuhub.web.admin

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import kr.co.securance.secuhub.domain.entity.AppUser
import kr.co.securance.secuhub.domain.repository.AppUserRepository
import kr.co.securance.secuhub.web.menu.MenuProvider
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
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
import org.springframework.web.bind.annotation.RequestParam
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
    /**
     * 사용자 관리(CRUD) 목록 — 기본은 '사용=Y'인 계정만 노출하고, [showInactive]가 true일 때만
     * 비활성 계정까지 전부 보여준다(2026-08-20 지시: 관리 화면도 기본은 사용=Y만, 비활성 항목은
     * 별도 보기 기능으로). 비활성 계정을 재활성화하려면 먼저 이 토글로 찾아야 한다.
     */
    fun findAll(showInactive: Boolean = false): List<AppUser> =
        if (showInactive) {
            userRepository.findAll(org.springframework.data.domain.Sort.by("userId"))
        } else {
            userRepository.findByUseYnTrueOrderByUserId()
        }

    fun findByIdOrNull(userId: String): AppUser? = userRepository.findById(userId).orElse(null)

    /**
     * 레거시 평문 비밀번호가 아직 BCrypt로 승격되지 않은 계정 수(2026-09-03 코드 리뷰 지적).
     * [passwordEncoder].upgradeEncoding은 [LegacyAwarePasswordEncoder]가 저장된 값이 BCrypt
     * 해시 형태가 아닐 때 true를 반환한다 — 즉 로그인 성공 시에만 자동 승격되므로, 오래 로그인하지
     * 않는(휴면) 계정은 평문 상태로 무기한 남을 수 있다. 관리 화면에 노출해 운영자가 인지하고
     * 필요 시 비밀번호를 직접 재설정하도록 안내한다(강제 재설정 배치는 별도 결정 필요라 범위 밖).
     */
    fun legacyPlaintextCount(): Long = userRepository.findAll().count { passwordEncoder.upgradeEncoding(it.passwordHash) }.toLong()

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
    // DB `tb_users.user_nm`이 varchar(20)이라(2026-09-09 실측), 화면 검증에서 미리 막지 않으면
    // DB INSERT/UPDATE 단계에서만 길이 초과 예외가 나 사용자에게 원인이 불분명하게 전달된다.
    @field:Size(max = 20, message = "이름은 20자 이하여야 합니다")
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
    private val logger = LoggerFactory.getLogger(UserController::class.java)

    @GetMapping
    fun list(@RequestParam(required = false, defaultValue = "false") showInactive: Boolean, model: Model): String {
        populateCommon(model, showInactive)
        model.addAttribute("form", UserForm())
        return "admin/users"
    }

    @GetMapping("/{userId}/edit")
    fun edit(
        @PathVariable userId: String,
        @RequestParam(required = false, defaultValue = "false") showInactive: Boolean,
        model: Model,
    ): String {
        val user = userManagementService.findByIdOrNull(userId) ?: return "redirect:/admin/users"
        populateCommon(model, showInactive)
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
        @RequestParam(required = false, defaultValue = "false") showInactive: Boolean,
        model: Model,
        redirectAttributes: RedirectAttributes,
    ): String {
        if (form.password.isBlank()) {
            binding.rejectValue("password", "required", "신규 등록 시 비밀번호는 필수입니다")
        }
        if (binding.hasErrors()) {
            populateCommon(model, showInactive)
            return "admin/users"
        }
        // UserManagementService.create는 중복 아이디를 require(...)(IllegalArgumentException)로
        // 막는데, 이 컨트롤러가 이를 잡지 않으면 BindingResult 필드 오류 대신 500 에러 페이지가
        // 뜬다(2026-08-20 Opus 전체 리뷰 지적). 단, DB 연결 장애 등 예상 못한 예외까지 "중복
        // 아이디"로 오분류하면 실제 운영 장애가 로그 없이 은폐된다(2026-08-20 Codex 리뷰 지적) —
        // 의도한 IllegalArgumentException만 필드 오류로 변환하고 나머지는 그대로 전파한다.
        try {
            userManagementService.create(form)
            redirectAttributes.addFlashAttribute("message", "사용자가 등록되었습니다.")
        } catch (ex: IllegalArgumentException) {
            binding.rejectValue("userId", "duplicate", ex.message ?: "사용자 등록에 실패했습니다.")
            populateCommon(model, showInactive)
            return "admin/users"
        }
        return "redirect:/admin/users?showInactive=$showInactive"
    }

    @PostMapping("/{userId}")
    fun update(
        @PathVariable userId: String,
        @Validated @ModelAttribute("form") form: UserForm,
        binding: BindingResult,
        @RequestParam(required = false, defaultValue = "false") showInactive: Boolean,
        model: Model,
        redirectAttributes: RedirectAttributes,
    ): String {
        if (binding.hasErrors()) {
            populateCommon(model, showInactive)
            model.addAttribute("editingId", userId)
            return "admin/users"
        }
        userManagementService.update(userId, form)
        redirectAttributes.addFlashAttribute("message", "사용자 정보가 수정되었습니다.")
        return "redirect:/admin/users?showInactive=$showInactive"
    }

    @PostMapping("/{userId}/delete")
    fun delete(
        @PathVariable userId: String,
        @RequestParam(required = false, defaultValue = "false") showInactive: Boolean,
        redirectAttributes: RedirectAttributes,
    ): String {
        val currentUserId = SecurityContextHolder.getContext().authentication?.name
        // 예상되는 예외(로그인 중인 계정 자기 삭제, FK 참조 잔존)만 좁게 잡아 안내 메시지로 바꾼다
        // (코드 리뷰 지적, 2026-08-28) — GateDetailController.delete 등과 동일한 패턴. 나머지 모든
        // 예외(DB 커넥션 장애 등)까지 runCatching으로 삼키면 로그 없이 사라져 장애 추적이
        // 불가능해지므로, 예상 밖 예외는 잡지 않고 GlobalExceptionHandler로 그대로 전파한다.
        try {
            userManagementService.delete(userId, currentUserId)
            redirectAttributes.addFlashAttribute("message", "사용자가 삭제되었습니다.")
        } catch (ex: IllegalArgumentException) {
            redirectAttributes.addFlashAttribute("error", ex.message)
        } catch (ex: DataIntegrityViolationException) {
            logger.warn("사용자[{}] 삭제 실패 - 연관 데이터 존재", userId, ex)
            redirectAttributes.addFlashAttribute("error", "연관된 데이터가 남아있어 이 사용자를 삭제할 수 없습니다.")
        }
        return "redirect:/admin/users?showInactive=$showInactive"
    }

    private fun populateCommon(model: Model, showInactive: Boolean) {
        model.addAttribute("menu", menuProvider.menu())
        model.addAttribute("pageTitle", "사용자 관리")
        model.addAttribute("users", userManagementService.findAll(showInactive))
        model.addAttribute("showInactive", showInactive)
        model.addAttribute("legacyPlaintextCount", userManagementService.legacyPlaintextCount())
    }
}
