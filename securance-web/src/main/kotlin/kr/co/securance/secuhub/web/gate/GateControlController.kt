package kr.co.securance.secuhub.web.gate

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import kr.co.securance.secuhub.common.gate.GateTypeCodes
import kr.co.securance.secuhub.common.util.HexCodec
import kr.co.securance.secuhub.domain.entity.DataSend
import kr.co.securance.secuhub.domain.entity.GateDetail
import kr.co.securance.secuhub.domain.repository.DataSendRepository
import kr.co.securance.secuhub.domain.repository.GateDetailRepository
import kr.co.securance.secuhub.protocol.FastGateMotorCodec
import kr.co.securance.secuhub.protocol.GateControlCommandBuilder
import kr.co.securance.secuhub.protocol.SpeedGateControlCommand
import kr.co.securance.secuhub.server.control.SND_SERVER_CD
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
     * "AC"로 호출하는 것만으로 바이트 단위 동일 패킷이 나온다.
     *
     * `snd_type_cd`도 반드시 [SpeedGateControlCommand.RESET_SYSTEM]의 `legacyCode`("AC")와
     * 동일하게 저장해야 한다 — `GateControlDispatcher.resolveFaultsIfReset`가 ACK 확인 후
     * `SpeedGateControlCommand.ofLegacyCode(sndTypeCd)`로 리셋 여부를 판별해 `tb_data_rcv_anal`의
     * 장애를 자동 해제하기 때문이다. 이전에는 "GATE_RESET"이라는 임의 문자열을 저장해 이 조회가
     * 항상 null을 반환했고, 그 결과 이 화면(#3 GateReset)으로 리셋해도 장비 ACK 후 자동 장애
     * 해제가 전혀 동작하지 않았다(2026-08-12 B7 수정 — 물리 전송 자체는 `sndRaw`만 쓰므로
     * 영향받지 않았지만, 자동 해제 기능만 조용히 죽어 있었다).
     */
    @Transactional
    fun sendReset(dtlId: Long, requestedBy: String): Boolean {
        val detail = findDetailOrNull(dtlId) ?: return false
        val packet = GateControlCommandBuilder.buildModeChangeCommand(detail.dtlLaneNo, "AC")
        // sndDataTp="RESET_GATE"는 QueuedGateControlService.legacyDataTypeOf(GateFaultCategory.ALL)와
        // 동일한 값이다 — 같은 "시스템 전체 리셋" 동작이므로 두 진입 경로의 이력 표기를 맞춘다.
        enqueue(detail, packet, SpeedGateControlCommand.RESET_SYSTEM.legacyCode, requestedBy, dataTp = "RESET_GATE")
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

    /**
     * Fast Gate 전용 모터 설정(P11, [FastGateMotorCodec]) — 신규 게이트 타입([GateTypeCodes.FAST_GATE])
     * 전용 명령이라 레거시 대응이 없다(코덱 KDoc 참고). 다른 게이트 타입에 이 명령을 보내면 장비가
     * 이해하지 못하는 Object Code를 받게 되므로, 호출자([GateControlController.fastMotorForm]/
     * [GateControlController.fastMotorSubmit])가 `dtlType == FAST_GATE`를 먼저 확인해야 한다 —
     * 이 서비스 메서드 자체는 그 가드를 반복하지 않는다(다른 sendXxx 메서드들과 동일하게 얇게 유지).
     */
    @Transactional
    fun sendFastGateMotorSetup(dtlId: Long, params: FastGateMotorCodec.Params, requestedBy: String): Boolean {
        val detail = findDetailOrNull(dtlId) ?: return false
        val packet = FastGateMotorCodec.buildSetCommand(detail.dtlLaneNo, params)
        enqueue(detail, packet, "FAST_MOTOR_SET", requestedBy)
        return true
    }

    private fun enqueue(detail: GateDetail, packet: ByteArray, typeCd: String, requestedBy: String, dataTp: String = "") {
        dataSendRepository.save(
            DataSend(
                sndDate = LocalDateTime.now().format(SND_DATE_FORMAT),
                dtlIp = detail.dtlIp,
                dtlLaneNo = detail.dtlLaneNo,
                sndUser = requestedBy,
                // 컬럼 누락 수정(2026-09-08, 운영 DB 실측) — reg_user/snd_server_cd 참고는 DataSend.kt 필드 KDoc.
                regUser = requestedBy,
                sndServerCd = SND_SERVER_CD,
                sndTypeCd = typeCd,
                sndDataTp = dataTp,
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

// Opus 전체 리뷰 지적: GateControlCommandBuilder.buildModeChangeCommand는 알 수 없는 controlType을
// 예외 없이 조용히 NORMAL로 처리하기 때문에 패킷 조립 단계의 화이트리스트만으로는 저장되는
// tb_data_snd.snd_type_cd(=사용자 입력 그대로인 "MODE_$combined")를 막지 못한다 — 여기서 폼 단계에
// buildModeChangeCommand의 화이트리스트(주석 참고)와 동일한 값만 허용해 임의 문자열이 DB에 저장되는
// 것을 원천 차단한다.
data class ModeChangeForm(
    @field:NotBlank(message = "운영 모드를 선택하세요")
    @field:Pattern(regexp = "CC|CF|FC|FF|OP|RP|CL|CS|CX|XC|FX|XF", message = "허용되지 않은 운영 모드입니다")
    var userMode: String = "CC",
    @field:NotBlank(message = "보안 모드를 선택하세요")
    @field:Pattern(regexp = "LM|MM|HM", message = "허용되지 않은 보안 모드입니다")
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

/**
 * Fast Gate 전용 모터 설정 폼 — [FastGateMotorCodec.Params]를 그대로 반영한다(Turn/Slide 모터
 * 각 3단계(Position/Rpm/Compensation) + 초기속도, Master/Slave 구분, Auto Close/Test Time,
 * Loof Exit 사용여부, Open Turn/Closed Slide Delay). 필드 접두사 `t`=Turn, `s`=Slide.
 */
data class FastGateMotorForm(
    @field:NotBlank @field:Pattern(regexp = "MASTER|SLAVE", message = "Master/Slave를 선택하세요")
    var masterSlave: String = "MASTER",

    @field:Min(0) @field:Max(65535) var tPos1: Int = 0,
    @field:Min(0) @field:Max(65535) var tRpm1: Int = 0,
    @field:Min(0) @field:Max(65535) var tComp1: Int = 0,
    @field:Min(0) @field:Max(65535) var tPos2: Int = 0,
    @field:Min(0) @field:Max(65535) var tRpm2: Int = 0,
    @field:Min(0) @field:Max(65535) var tComp2: Int = 0,
    @field:Min(0) @field:Max(65535) var tPos3: Int = 0,
    @field:Min(0) @field:Max(65535) var tRpm3: Int = 0,
    @field:Min(0) @field:Max(65535) var tComp3: Int = 0,
    @field:Min(-32768) @field:Max(32767) var tInitSpeed: Int = 0,

    @field:Min(0) @field:Max(65535) var sPos1: Int = 0,
    @field:Min(0) @field:Max(65535) var sRpm1: Int = 0,
    @field:Min(0) @field:Max(65535) var sComp1: Int = 0,
    @field:Min(0) @field:Max(65535) var sPos2: Int = 0,
    @field:Min(0) @field:Max(65535) var sRpm2: Int = 0,
    @field:Min(0) @field:Max(65535) var sComp2: Int = 0,
    @field:Min(0) @field:Max(65535) var sPos3: Int = 0,
    @field:Min(0) @field:Max(65535) var sRpm3: Int = 0,
    @field:Min(0) @field:Max(65535) var sComp3: Int = 0,
    @field:Min(-32768) @field:Max(32767) var sInitSpeed: Int = 0,

    @field:Min(0) @field:Max(65535) var autoCloseTimeMs10: Int = 0,
    @field:Min(0) @field:Max(65535) var autoTestTimeMs10: Int = 0,
    var loofExitUsed: Boolean = false,
    @field:Min(0) @field:Max(65535) var openTurnDelayTimeMs10: Int = 0,
    @field:Min(0) @field:Max(65535) var closedSlideDelayTimeMs10: Int = 0,
) {
    fun toParams() = FastGateMotorCodec.Params(
        masterSlave = FastGateMotorCodec.MasterSlave.valueOf(masterSlave),
        turn = FastGateMotorCodec.MotorAxis(
            stage1 = FastGateMotorCodec.MotorStage(tPos1, tRpm1, tComp1),
            stage2 = FastGateMotorCodec.MotorStage(tPos2, tRpm2, tComp2),
            stage3 = FastGateMotorCodec.MotorStage(tPos3, tRpm3, tComp3),
            initSpeed = tInitSpeed,
        ),
        slide = FastGateMotorCodec.MotorAxis(
            stage1 = FastGateMotorCodec.MotorStage(sPos1, sRpm1, sComp1),
            stage2 = FastGateMotorCodec.MotorStage(sPos2, sRpm2, sComp2),
            stage3 = FastGateMotorCodec.MotorStage(sPos3, sRpm3, sComp3),
            initSpeed = sInitSpeed,
        ),
        autoCloseTimeMs10 = autoCloseTimeMs10,
        autoTestTimeMs10 = autoTestTimeMs10,
        loofExitUsed = loofExitUsed,
        openTurnDelayTimeMs10 = openTurnDelayTimeMs10,
        closedSlideDelayTimeMs10 = closedSlideDelayTimeMs10,
    )
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

    /**
     * Fast Gate 전용 모터 설정([FastGateMotorCodec], P11) — [GateTypeCodes.FAST_GATE]가 아닌
     * 레인은 이 화면에 들어올 수 없다(장비가 이해하지 못하는 Object Code라 아예 진입을 막는다).
     * `/gates/details.html`도 dtlType==FAST_GATE인 행에만 진입 버튼을 노출하지만, URL을 직접 입력해
     * 들어오는 경로도 있으므로 여기서 다시 한번 검사한다.
     */
    @GetMapping("/fast-motor")
    fun fastMotorForm(@PathVariable dtlId: Long, model: Model, redirectAttributes: RedirectAttributes): String {
        val detail = gateControlService.findDetailOrNull(dtlId) ?: return "redirect:/gates/details"
        if (detail.dtlType != GateTypeCodes.FAST_GATE) {
            redirectAttributes.addFlashAttribute("message", "Fast Gate 전용 화면입니다. 대상 레인의 타입이 다릅니다.")
            return "redirect:/gates/details?grpId=${detail.group.grpId}"
        }
        populateCommon(model, detail, "Fast Gate 모터 설정")
        model.addAttribute("form", FastGateMotorForm())
        return "gates/fast-motor-setup"
    }

    @PostMapping("/fast-motor")
    fun fastMotorSubmit(
        @PathVariable dtlId: Long,
        @Validated @ModelAttribute("form") form: FastGateMotorForm,
        binding: BindingResult,
        model: Model,
        redirectAttributes: RedirectAttributes,
    ): String {
        val detail = gateControlService.findDetailOrNull(dtlId) ?: return "redirect:/gates/details"
        if (detail.dtlType != GateTypeCodes.FAST_GATE) {
            redirectAttributes.addFlashAttribute("message", "Fast Gate 전용 화면입니다. 대상 레인의 타입이 다릅니다.")
            return "redirect:/gates/details?grpId=${detail.group.grpId}"
        }
        if (binding.hasErrors()) {
            populateCommon(model, detail, "Fast Gate 모터 설정")
            return "gates/fast-motor-setup"
        }
        gateControlService.sendFastGateMotorSetup(dtlId, form.toParams(), currentUsername())
        redirectAttributes.addFlashAttribute(
            "message",
            "Fast Gate 모터 설정 명령을 전송 대기열에 등록했습니다. IP=${detail.dtlIp}, 레인=${detail.dtlLaneNo}",
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
