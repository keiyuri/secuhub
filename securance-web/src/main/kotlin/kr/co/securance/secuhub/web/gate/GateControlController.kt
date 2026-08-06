package kr.co.securance.secuhub.web.gate

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import kr.co.securance.secuhub.common.util.HexCodec
import kr.co.securance.secuhub.domain.entity.DataSend
import kr.co.securance.secuhub.domain.entity.GateDetail
import kr.co.securance.secuhub.domain.repository.DataSendRepository
import kr.co.securance.secuhub.domain.repository.GateDetailRepository
import kr.co.securance.secuhub.protocol.GateControlCommandBuilder
import kr.co.securance.secuhub.web.menu.MenuProvider
import org.springframework.security.core.context.SecurityContextHolder
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
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * #12 SR_P_GateModeChange / #13 SR_P_GateSetupMotor — 개별 게이트(레인) 제어 명령을
 * `tb_data_snd` 큐에 적재한다(계획서 Phase 4, 2026-08-06 사용자 확인: "레거시 그대로 재현").
 *
 * #3 GateReset/#1 GateControl과 달리 이 화면은 **실제로 명령을 큐에 적재한다** — 패킷 조립을
 * [GateControlCommandBuilder]가 레거시 `SR_C_DataHandler`를 바이트 단위로 이식했기 때문이다.
 * 물리 전송 자체는 기존 `securance-scheduler`의 `SendControlJob`(QUEUED 경로, 계획서 5.5절)이 담당한다.
 *
 * 시간대 스케줄(레거시 cmbUserSch/cmbSecuSch)은 Phase 5에서 TimeZone 엔티티가 만들어지기 전까지는
 * 항상 "미선택"으로 취급한다 — 레거시도 `SelectedIndex == 0`이면 스케줄 바이트를 채우지 않는 것과
 * 동일한 동작이다.
 */
@Service
class GateControlService(
    private val detailRepository: GateDetailRepository,
    private val dataSendRepository: DataSendRepository,
) {
    fun findDetailOrNull(dtlId: Long): GateDetail? = detailRepository.findById(dtlId).orElse(null)

    /**
     * #3 GateReset "선택 리셋 실행" — 레거시 `SR_F_GateReset.pbReset_MouseClick`은
     * `SetControlCmd(boardType, ip, laneNo, "AC", "", "")`로 리셋 명령을 만드는데, 이는
     * `GenerateCmdBody`의 controlType "AC" 분기(offset 20에 0x01, System Reset)만 다를 뿐
     * #12 모드변경과 완전히 동일한 패킷 조립 경로다 — 별도 빌더 없이 [buildModeChangeCommand]를
     * "AC"로 호출하는 것만으로 바이트 단위 동일 패킷이 나온다. 타입 코드만 레거시와 동일하게
     * "GATE_RESET"으로 구분한다.
     */
    @Transactional
    fun sendReset(dtlId: Long, requestedBy: String): Boolean {
        val detail = findDetailOrNull(dtlId) ?: return false
        val packet = GateControlCommandBuilder.buildModeChangeCommand(detail.dtlLaneNo, "AC")
        enqueue(detail, packet, "GATE_RESET", requestedBy)
        return true
    }

    @Transactional
    fun sendModeChange(dtlId: Long, userMode: String, secuMode: String, requestedBy: String): Boolean {
        val detail = findDetailOrNull(dtlId) ?: return false
        val userModeSafe = userMode.ifBlank { DEFAULT_USER_MODE }
        val secuModeSafe = secuMode.ifBlank { DEFAULT_SECU_MODE }
        val combined = (userModeSafe + secuModeSafe).trim().uppercase()

        val packet = GateControlCommandBuilder.buildModeChangeCommand(detail.dtlLaneNo, combined)
        enqueue(detail, packet, "MODE_$combined", requestedBy)
        return true
    }

    @Transactional
    fun sendMotorSetup(
        dtlId: Long,
        main: GateControlCommandBuilder.MotorParams,
        sub: GateControlCommandBuilder.MotorParams,
        isInit: Boolean,
        requestedBy: String,
    ): Boolean {
        val detail = findDetailOrNull(dtlId) ?: return false
        val packet = GateControlCommandBuilder.buildMotorSetupCommand(detail.dtlLaneNo, main, sub)
        val typeCd = if (isInit) "MOTOR_INIT" else "MOTOR_CHANGE"
        enqueue(detail, packet, typeCd, requestedBy)
        return true
    }

    private fun enqueue(detail: GateDetail, packet: ByteArray, typeCd: String, requestedBy: String) {
        dataSendRepository.save(
            DataSend(
                sndDate = LocalDateTime.now().format(SND_DATE_FORMAT),
                dtlIp = detail.dtlIp,
                dtlLaneNo = detail.dtlLaneNo,
                sndUser = requestedBy,
                sndTypeCd = typeCd,
                sndRaw = HexCodec.toHex(packet),
            ),
        )
    }

    private companion object {
        const val DEFAULT_USER_MODE = "CC"
        const val DEFAULT_SECU_MODE = "LM"
        val SND_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
    }
}

data class ModeChangeForm(
    @field:NotBlank(message = "운영 모드를 선택하세요")
    var userMode: String = "CC",
    @field:NotBlank(message = "보안 모드를 선택하세요")
    var secuMode: String = "LM",
)

/**
 * 레거시는 "설정값 적용"/"초기값 적용" 버튼마다 별도의 12개 입력 필드(총 24개)를 두지만,
 * 두 버튼 모두 동일한 bMotor 바이트 레이아웃을 만드는 동일한 로직이라(SR_P_GateSetupMotor.cs
 * pbSetApply_MouseClick/pbInitApply_MouseClick 비교) 여기서는 입력 필드 하나로 통합하고
 * 어느 버튼을 눌렀는지(mode=CHANGE|INIT)만 구분해 snd_type_cd(MOTOR_CHANGE/MOTOR_INIT)에 반영한다.
 */
data class MotorSetupForm(
    @field:Min(0) @field:Max(255) var mInitSpeed: Int = 0,
    @field:Min(0) @field:Max(255) var mInitCount: Int = 0,
    @field:Min(0) @field:Max(255) var mOpenSpeed: Int = 0,
    @field:Min(0) @field:Max(255) var mOpenCount: Int = 0,
    @field:Min(0) @field:Max(255) var mCloseSpeed: Int = 0,
    @field:Min(0) @field:Max(255) var mCloseCount: Int = 0,
    @field:Min(0) @field:Max(255) var sInitSpeed: Int = 0,
    @field:Min(0) @field:Max(255) var sInitCount: Int = 0,
    @field:Min(0) @field:Max(255) var sOpenSpeed: Int = 0,
    @field:Min(0) @field:Max(255) var sOpenCount: Int = 0,
    @field:Min(0) @field:Max(255) var sCloseSpeed: Int = 0,
    @field:Min(0) @field:Max(255) var sCloseCount: Int = 0,
) {
    fun toMain() = GateControlCommandBuilder.MotorParams(mInitSpeed, mInitCount, mOpenSpeed, mOpenCount, mCloseSpeed, mCloseCount)
    fun toSub() = GateControlCommandBuilder.MotorParams(sInitSpeed, sInitCount, sOpenSpeed, sOpenCount, sCloseSpeed, sCloseCount)
}

@Controller
@RequestMapping("/gates/details/{dtlId}")
class GateControlController(
    private val gateControlService: GateControlService,
    private val menuProvider: MenuProvider,
) {
    @GetMapping("/mode")
    fun modeForm(@PathVariable dtlId: Long, model: Model): String {
        val detail = gateControlService.findDetailOrNull(dtlId) ?: return "redirect:/gates/details"
        populateCommon(model, detail, "게이트 모드 변경")
        model.addAttribute("form", ModeChangeForm())
        return "gates/mode-change"
    }

    @PostMapping("/mode")
    fun modeSubmit(
        @PathVariable dtlId: Long,
        @Validated @ModelAttribute("form") form: ModeChangeForm,
        binding: BindingResult,
        model: Model,
        redirectAttributes: RedirectAttributes,
    ): String {
        val detail = gateControlService.findDetailOrNull(dtlId) ?: return "redirect:/gates/details"
        if (binding.hasErrors()) {
            populateCommon(model, detail, "게이트 모드 변경")
            return "gates/mode-change"
        }
        gateControlService.sendModeChange(dtlId, form.userMode, form.secuMode, currentUsername())
        redirectAttributes.addFlashAttribute(
            "message",
            "모드 변경 명령을 전송 대기열에 등록했습니다. IP=${detail.dtlIp}, 레인=${detail.dtlLaneNo}",
        )
        return "redirect:/gates/details?grpId=${detail.group.grpId}"
    }

    @GetMapping("/motor")
    fun motorForm(@PathVariable dtlId: Long, model: Model): String {
        val detail = gateControlService.findDetailOrNull(dtlId) ?: return "redirect:/gates/details"
        populateCommon(model, detail, "게이트 모터 설정")
        model.addAttribute("form", MotorSetupForm())
        return "gates/motor-setup"
    }

    @PostMapping("/motor")
    fun motorSubmit(
        @PathVariable dtlId: Long,
        @RequestParam mode: String,
        @Validated @ModelAttribute("form") form: MotorSetupForm,
        binding: BindingResult,
        model: Model,
        redirectAttributes: RedirectAttributes,
    ): String {
        val detail = gateControlService.findDetailOrNull(dtlId) ?: return "redirect:/gates/details"
        if (binding.hasErrors()) {
            populateCommon(model, detail, "게이트 모터 설정")
            return "gates/motor-setup"
        }
        gateControlService.sendMotorSetup(dtlId, form.toMain(), form.toSub(), isInit = (mode == "INIT"), requestedBy = currentUsername())
        redirectAttributes.addFlashAttribute(
            "message",
            "모터 설정 명령을 전송 대기열에 등록했습니다. IP=${detail.dtlIp}, 레인=${detail.dtlLaneNo}",
        )
        return "redirect:/gates/details?grpId=${detail.group.grpId}"
    }

    private fun populateCommon(model: Model, detail: GateDetail, pageTitle: String) {
        model.addAttribute("menu", menuProvider.menu())
        model.addAttribute("pageTitle", pageTitle)
        model.addAttribute("detail", detail)
    }

    private fun currentUsername(): String = SecurityContextHolder.getContext().authentication?.name ?: "system"
}
