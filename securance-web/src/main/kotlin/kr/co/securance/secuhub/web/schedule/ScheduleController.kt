package kr.co.securance.secuhub.web.schedule

import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import kr.co.securance.secuhub.common.util.HexCodec
import kr.co.securance.secuhub.domain.entity.DataSend
import kr.co.securance.secuhub.domain.entity.GateDetail
import kr.co.securance.secuhub.domain.entity.GateTimeZone
import kr.co.securance.secuhub.domain.repository.DataSendRepository
import kr.co.securance.secuhub.domain.repository.GateDetailRepository
import kr.co.securance.secuhub.domain.repository.GateTimeZoneRepository
import kr.co.securance.secuhub.protocol.GateControlCommandBuilder
import kr.co.securance.secuhub.protocol.TimeZoneCommandBuilder
import kr.co.securance.secuhub.server.control.SND_SERVER_CD
import kr.co.securance.secuhub.web.gate.GateDetailService
import kr.co.securance.secuhub.web.gate.GateGroupService
import kr.co.securance.secuhub.web.gate.GateLocationService
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
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * #6 SR_F_SetupSchedule + #7 SR_F_Schedule 통합 / #15 SR_P_Timezone — 스케줄/타임존 화면
 * (계획서 Phase 5, 2026-08-06 확정: "6번과 7번은 통합 확정").
 *
 * 레거시에는 `SR_F_Schedule.cs`(구버전, 실사용 로직 대부분 주석 처리된 죽은 코드)와
 * `SR_F_SetupSchedule.cs`(현재 라이브 화면 — 3단 콤보 위치/그룹/게이트 필터 + 예약 라디오 +
 * 초기화 체크박스까지 전부 활성)가 중복 존재한다. 이 웹 전환은 **라이브 로직만** 이식한다 —
 * `SR_F_Schedule.cs`의 주석 처리된 `cmbUserSch`/`cmbSecuSch` 관련 코드는 원래도 어떤 이벤트에도
 * 연결돼 있지 않아(빌드는 되지만 죽은 코드) 포팅 대상에서 제외했다.
 */
@Service
class TimeZoneService(
    private val timeZoneRepository: GateTimeZoneRepository,
    private val detailRepository: GateDetailRepository,
    private val dataSendRepository: DataSendRepository,
) {
    fun findAllActive(): List<GateTimeZone> = timeZoneRepository.findByUseYnTrueOrderByTimezoneIdDesc()

    /**
     * 신규 타임존을 저장한다. 레거시 `SaveSchedule`/`SaveScheduleTime`과 동일하게 **저장이 성공하면
     * 자동으로 활성 게이트 전체에 TIME_SYNC(`DATA_TIME`) 명령을 큐에 적재한다** — 별도 "전송" 버튼이
     * 없고 등록 자체가 곧 배포다.
     */
    @Transactional
    fun create(form: TimeZoneForm, requestedBy: String): GateTimeZone {
        // 레거시는 저장 전에 SelectTimeZoneID(MAX+1)로 ID를 미리 얻어 hex 페이로드에 반영하지만,
        // 여기서는 JPA IDENTITY 채번을 그대로 쓴다 — 우선 빈 hexData로 저장해 PK를 발급받은 뒤
        // 그 PK로 hexData를 계산해 다시 채운다(같은 트랜잭션 내 update, 최종 커밋 결과는 레거시와 동일).
        val entity = timeZoneRepository.saveAndFlush(
            GateTimeZone(
                timezoneName = form.timezoneName,
                timezoneDesc = form.timezoneDesc,
                timezoneHexData = "",
                regUser = requestedBy,
            ),
        )
        val timezoneId = requireNotNull(entity.timezoneId)

        val slotForms = listOf(form.slot1, form.slot2, form.slot3, form.slot4)
        val slots = slotForms.map { it.toBuilderSlot() }
        val hexBytes = TimeZoneCommandBuilder.buildTimezoneHexData(timezoneId.toInt(), slots)

        entity.timezoneHexData = HexCodec.toHex(hexBytes)
        slotForms.forEachIndexed { index, slot ->
            // Opus 전체 리뷰 지적: "HH:mm ~ HH:mm" 형태로 합쳐서 timezone_fr*(VARCHAR(10)) 하나에
            // 몰아넣으면 13자가 되어 strict 모드 MariaDB에서 저장 자체가 실패한다. 스키마에는
            // 애초에 fr/to가 별도 컬럼(timezone_fr*/timezone_to*, 각 VARCHAR(10))으로 설계돼 있으므로
            // "HH:mm"(5자)씩 나눠 그대로 사용한다 — 컬럼 폭도 지키고 엔티티 설계 의도에도 맞는다.
            val from = if (slot.isBlank()) "" else "%02d:%02d".format(slot.fromHour, slot.fromMinute)
            val to = if (slot.isBlank()) "" else "%02d:%02d".format(slot.toHour, slot.toMinute)
            val days = slot.dayLabels().joinToString(",")
            when (index) {
                0 -> { entity.timezoneFr1 = from; entity.timezoneTo1 = to; entity.timezoneDay1 = days }
                1 -> { entity.timezoneFr2 = from; entity.timezoneTo2 = to; entity.timezoneDay2 = days }
                2 -> { entity.timezoneFr3 = from; entity.timezoneTo3 = to; entity.timezoneDay3 = days }
                3 -> { entity.timezoneFr4 = from; entity.timezoneTo4 = to; entity.timezoneDay4 = days }
            }
        }

        broadcastToAllGates(hexBytes, requestedBy)
        return entity
    }

    /** #7 "동기화" 버튼 — 등록된 모든 활성 타임존을 다시 전체 게이트에 전송한다(레거시 `pbSync`). */
    @Transactional
    fun syncAll(requestedBy: String): Int {
        val list = findAllActive()
        for (tz in list) {
            broadcastToAllGates(HexCodec.fromHex(tz.timezoneHexData), requestedBy)
        }
        return list.size
    }

    private fun broadcastToAllGates(timezoneData: ByteArray, requestedBy: String) {
        val packet = GateControlCommandBuilder.buildTimeSyncCommand(timezoneData)
        val hex = HexCodec.toHex(packet)
        val targets = detailRepository.findByUseYnTrueOrderByDtlIp()
        if (targets.isEmpty()) return
        val now = LocalDateTime.now().format(SND_DATE_FORMAT)
        dataSendRepository.saveAll(
            targets.map { detail ->
                DataSend(
                    sndDate = now,
                    dtlIp = detail.dtlIp,
                    dtlLaneNo = detail.dtlLaneNo,
                    sndUser = requestedBy,
                    // 컬럼 누락 수정(2026-09-08, 운영 DB 실측) — reg_user/snd_server_cd 참고는 DataSend.kt 필드 KDoc.
                    regUser = requestedBy,
                    sndServerCd = SND_SERVER_CD,
                    sndTypeCd = "DATA_TIME",
                    sndRaw = hex,
                )
            },
        )
    }

    private companion object {
        val SND_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
    }
}

data class TimeZoneForm(
    @field:NotBlank(message = "명칭을 입력하세요")
    var timezoneName: String = "",
    var timezoneDesc: String = "",
    // @field:Valid — 중첩 폼(슬롯)의 제약을 실제로 검증하려면 캐스케이드 애너테이션이 필요하다.
    // 이게 없으면 TimeZoneSlotForm의 @Min/@Max는 바인딩 단계에서 전혀 평가되지 않고, 범위를
    // 벗어난 값이 그대로 TimeZoneCommandBuilder.Slot의 require()까지 흘러가 컨트롤러
    // BindingResult가 아닌 IllegalArgumentException(→ 500 에러 페이지)으로 터진다
    // (2026-08-20 Opus 전체 리뷰 지적).
    @field:Valid
    var slot1: TimeZoneSlotForm = TimeZoneSlotForm(),
    @field:Valid
    var slot2: TimeZoneSlotForm = TimeZoneSlotForm(),
    @field:Valid
    var slot3: TimeZoneSlotForm = TimeZoneSlotForm(),
    @field:Valid
    var slot4: TimeZoneSlotForm = TimeZoneSlotForm(),
)

/**
 * 타임존 슬롯 1개 입력 — 값을 전혀 입력하지 않으면(모두 0/미체크) "미사용 슬롯"으로 취급한다.
 *
 * `@Min/@Max`는 [TimeZoneCommandBuilder.Slot]의 `require(fromHour in 0..23)` 등과 동일한 범위를
 * 폼 바인딩 단계에서 미리 강제한다(2026-08-20 Opus 전체 리뷰 지적 — 이전에는 이 폼에 아무 제약이
 * 없어 `fromHour=99` 같은 값이 `binding.hasErrors()`를 통과한 뒤 서비스 계층의 `require()`에서
 * `IllegalArgumentException`으로 터져 필드 오류 대신 500 에러 페이지가 떴다).
 */
data class TimeZoneSlotForm(
    @field:Min(0) @field:Max(23)
    var fromHour: Int = 0,
    @field:Min(0) @field:Max(59)
    var fromMinute: Int = 0,
    @field:Min(0) @field:Max(23)
    var toHour: Int = 0,
    @field:Min(0) @field:Max(59)
    var toMinute: Int = 0,
    var sunday: Boolean = false,
    var monday: Boolean = false,
    var tuesday: Boolean = false,
    var wednesday: Boolean = false,
    var thursday: Boolean = false,
    var friday: Boolean = false,
    var saturday: Boolean = false,
) {
    fun isBlank(): Boolean = dayLabels().isEmpty()

    fun dayLabels(): List<String> = buildList {
        if (sunday) add("일")
        if (monday) add("월")
        if (tuesday) add("화")
        if (wednesday) add("수")
        if (thursday) add("목")
        if (friday) add("금")
        if (saturday) add("토")
    }

    fun toBuilderSlot(): TimeZoneCommandBuilder.Slot = TimeZoneCommandBuilder.Slot(
        fromHour = fromHour,
        fromMinute = fromMinute,
        toHour = toHour,
        toMinute = toMinute,
        days = TimeZoneCommandBuilder.DaySelection(
            sunday = sunday, monday = monday, tuesday = tuesday, wednesday = wednesday,
            thursday = thursday, friday = friday, saturday = saturday,
        ),
    )
}

/**
 * #6 SetupSchedule — 위치/그룹/게이트(선택) 단위로 예약 모드를 일괄 적용한다.
 *
 * 운영모드·보안모드 라디오는 레거시 `rbUserSch01~10`/`rbSecuSch01~04`의 "예약 슬롯 번호"와
 * 1:1 대응이라(선택한 라디오 = 곧 슬롯 번호) 화면에서 별도로 슬롯 번호를 입력받지 않고
 * [USER_MODE_SLOTS]/[SECU_MODE_SLOTS] 매핑으로 계산한다.
 *
 * [주의 — 레거시 결함까지 재현] 운영모드 "CD"/"DC"/"FD"/"DF"는 레거시 `GenerateCmdBody`의
 * switch문에 해당 코드가 없어(그 switch는 CX/XC/FX/XF만 인식) 전부 `default`(Normal=0x01)로
 * 빠진다 — 즉 이 4개 옵션은 실제로는 "일반(Normal)"과 동일하게 동작하는 레거시 버그다.
 * [GateControlCommandBuilder.buildModeChangeCommand]가 동일한 switch를 그대로 포팅했기 때문에
 * 이 서비스는 특별한 처리 없이도 자동으로 같은 결함을 재현한다.
 */
@Service
class ScheduleApplyService(
    private val detailRepository: GateDetailRepository,
    private val dataSendRepository: DataSendRepository,
) {
    /**
     * 예약 모드 명령은 `analysisYn`(분석/제어 대상) 여부와 무관하게 적용 대상이다 — 레거시
     * `SelectGateDtlIPList`/`InsertSendDataAll`(타임존 동기화, [TimeZoneService.broadcastToAllGates]
     * 참고)과 동일하게 레인 자신의 `useYn`만 확인하고 `analysisYn`은 확인하지 않는다(2026-08-20
     * 사용자 확인: "분석=N도 포함, 사용=Y만 필터"). 화면(ScheduleController.index)의 레인 콤보도
     * 동일 조건([GateDetailService.findByGroupForSchedule])을 쓴다.
     *
     * 다만 소속 그룹·위치의 `useYn`은 레인 자신의 useYn과 별개로 항상 강제한다 — 위치/그룹 콤보는
     * 활성 항목만 보여주므로 정상 경로에서는 비활성 위치/그룹이 선택되지 않지만, 조작된 POST로
     * 비활성 위치·그룹의 locId/grpId/dtlId를 직접 보내면 그 아래 useYn=true인 레인에까지 예약
     * 명령이 나갈 수 있었다(2026-08-20 Codex 적대적 리뷰 지적 — 상위 계층 비활성화 우회 경로,
     * GateResetController.filterByGroupMembership과 동일한 문제).
     */
    fun targets(locId: Long?, grpId: Long?, dtlId: Long?): List<GateDetail> {
        if (dtlId != null) {
            val detail = detailRepository.findByIdAndUseYnTrueWithActiveParents(dtlId) ?: return emptyList()
            return listOf(detail)
        }
        if (grpId != null) return detailRepository.findByGroup_GrpIdAndUseYnTrue(grpId)
        if (locId != null) return detailRepository.findByLocation_LocIdAndUseYnTrue(locId)
        return emptyList()
    }

    @Transactional
    fun applyMode(form: ScheduleApplyForm, requestedBy: String): Int {
        val gates = targets(form.locId, form.grpId, form.dtlId)
        if (gates.isEmpty()) return 0

        val userMode = form.userMode.ifBlank { "CC" }.uppercase()
        val secuMode = form.secuMode.ifBlank { "NA" }.uppercase()
        val combined = userMode + secuMode

        val timeDataUser = form.userTimezoneId?.let {
            TimeZoneCommandBuilder.buildScheduleReference(USER_MODE_SLOTS[userMode] ?: 0x01, it.toInt())
        }
        val timeDataSecu = form.secuTimezoneId?.let {
            TimeZoneCommandBuilder.buildScheduleReference(SECU_MODE_SLOTS[secuMode] ?: 0x00, it.toInt())
        }

        insertBulk(gates, combined, timeDataUser, timeDataSecu, requestedBy)
        return gates.size
    }

    /** 레거시 `cbSchdRst` 체크박스 — 대상 게이트에 "NA+NA"(변경 없음) 명령을 다시 보내 예약을 해제한다. */
    @Transactional
    fun resetMode(locId: Long?, grpId: Long?, dtlId: Long?, requestedBy: String): Int {
        val gates = targets(locId, grpId, dtlId)
        if (gates.isEmpty()) return 0
        insertBulk(gates, "NANA", null, null, requestedBy)
        return gates.size
    }

    private fun insertBulk(
        gates: List<GateDetail>,
        combined: String,
        timeDataUser: ByteArray?,
        timeDataSecu: ByteArray?,
        requestedBy: String,
    ) {
        val now = LocalDateTime.now().format(SND_DATE_FORMAT)
        val rows = gates.map { detail ->
            val packet = GateControlCommandBuilder.buildModeChangeCommand(
                detail.dtlLaneNo, combined, timeDataUser, timeDataSecu,
            )
            DataSend(
                sndDate = now,
                dtlIp = detail.dtlIp,
                dtlLaneNo = detail.dtlLaneNo,
                sndUser = requestedBy,
                // 컬럼 누락 수정(2026-09-08, 운영 DB 실측) — reg_user/snd_server_cd 참고는 DataSend.kt 필드 KDoc.
                regUser = requestedBy,
                sndServerCd = SND_SERVER_CD,
                sndTypeCd = "MODE_$combined",
                sndRaw = HexCodec.toHex(packet),
            )
        }
        dataSendRepository.saveAll(rows)
    }

    companion object {
        /** 레거시 `rbUserSch01~10`의 순서 = 예약 슬롯 번호(0x01~0x0A). */
        val USER_MODE_SLOTS: Map<String, Int> = linkedMapOf(
            "CC" to 0x01, "CF" to 0x02, "FC" to 0x03, "FF" to 0x04, "OP" to 0x05,
            "CL" to 0x06, "CD" to 0x07, "DC" to 0x08, "FD" to 0x09, "DF" to 0x0A,
        )

        /** 레거시 `rbSecuSch01~04`의 순서 = 예약 슬롯 번호(0x00~0x03). "NA"=변함없음(기본). */
        val SECU_MODE_SLOTS: Map<String, Int> = linkedMapOf(
            "NA" to 0x00, "LM" to 0x01, "MM" to 0x02, "HM" to 0x03,
        )

        private val SND_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
    }
}

// Codex 리뷰 지적: GateControlController.ModeChangeForm과 동일하게, 화면(schedule/index.html)이
// 제공하는 선택지(USER_MODE_SLOTS/SECU_MODE_SLOTS의 키)와 정확히 일치하는 화이트리스트를 걸어
// tb_data_snd.snd_type_cd(="MODE_$combined")에 임의 문자열이 저장되는 것을 막는다.
data class ScheduleApplyForm(
    var locId: Long? = null,
    var grpId: Long? = null,
    var dtlId: Long? = null,
    @field:Pattern(regexp = "CC|CF|FC|FF|OP|CL|CD|DC|FD|DF", message = "허용되지 않은 운영 모드입니다")
    var userMode: String = "CC",
    @field:Pattern(regexp = "NA|LM|MM|HM", message = "허용되지 않은 보안 모드입니다")
    var secuMode: String = "NA",
    var userTimezoneId: Long? = null,
    var secuTimezoneId: Long? = null,
)

@Controller
@RequestMapping("/schedule")
class ScheduleController(
    private val timeZoneService: TimeZoneService,
    private val scheduleApplyService: ScheduleApplyService,
    private val locationService: GateLocationService,
    private val groupService: GateGroupService,
    private val detailService: GateDetailService,
    private val menuProvider: MenuProvider,
) {
    @GetMapping
    fun index(
        @RequestParam(required = false) locId: Long?,
        @RequestParam(required = false) grpId: Long?,
        @RequestParam(required = false) dtlId: Long?,
        model: Model,
    ): String {
        model.addAttribute("menu", menuProvider.menu())
        model.addAttribute("pageTitle", "스케줄/타임존 관리")
        model.addAttribute("timezones", timeZoneService.findAllActive())
        model.addAttribute("timezoneForm", TimeZoneForm())

        // 관리(CRUD) 목록 화면이 아니므로 '사용=Y' 대상만 노출한다(2026-08-20 "예외 없이 전체 목록
        // 조회에 적용" 지시). 단, 레인 콤보(detailsForGrp)는 예약 명령이 analysisYn과 무관하게
        // 적용되므로 '분석' 컬럼은 확인하지 않는다(2026-08-20 사용자 확인: "분석=N도 포함, 사용=Y만
        // 필터" — GateDetailService.findByGroupForSchedule 참고).
        model.addAttribute("allLocations", locationService.findAllActive())
        model.addAttribute("groupsForLoc", locId?.let { groupService.findAllActiveByLocation(it) } ?: emptyList<Any>())
        model.addAttribute("detailsForGrp", grpId?.let { detailService.findByGroupForSchedule(it) } ?: emptyList<Any>())
        model.addAttribute("selectedLocId", locId)
        model.addAttribute("selectedGrpId", grpId)
        model.addAttribute("selectedDtlId", dtlId)
        model.addAttribute(
            "applyForm",
            ScheduleApplyForm(locId = locId, grpId = grpId, dtlId = dtlId),
        )
        return "schedule/index"
    }

    @PostMapping("/timezones")
    fun createTimezone(
        @Validated @ModelAttribute("timezoneForm") form: TimeZoneForm,
        binding: BindingResult,
        redirectAttributes: RedirectAttributes,
    ): String {
        if (binding.hasErrors()) {
            redirectAttributes.addFlashAttribute("errorMessage", "타임존 명칭을 입력하세요.")
            return "redirect:/schedule"
        }
        timeZoneService.create(form, currentUsername())
        redirectAttributes.addFlashAttribute("message", "타임존이 등록되었고, 전체 게이트에 시간 동기화 명령을 전송했습니다.")
        return "redirect:/schedule"
    }

    @PostMapping("/timezones/sync")
    fun syncTimezones(redirectAttributes: RedirectAttributes): String {
        val count = timeZoneService.syncAll(currentUsername())
        redirectAttributes.addFlashAttribute("message", "등록된 타임존 ${count}건을 전체 게이트에 재전송했습니다.")
        return "redirect:/schedule"
    }

    @PostMapping("/apply")
    fun apply(
        @Validated @ModelAttribute("applyForm") form: ScheduleApplyForm,
        binding: BindingResult,
        redirectAttributes: RedirectAttributes,
    ): String {
        if (binding.hasErrors()) {
            redirectAttributes.addFlashAttribute("errorMessage", "허용되지 않은 모드 값입니다.")
            return redirectTo(form.locId, form.grpId, form.dtlId)
        }
        val count = scheduleApplyService.applyMode(form, currentUsername())
        redirectAttributes.addFlashAttribute(
            "message",
            if (count > 0) "예약 모드 명령을 게이트 ${count}대에 전송 대기열로 등록했습니다." else "대상 게이트가 없습니다(위치를 선택하세요).",
        )
        return redirectTo(form.locId, form.grpId, form.dtlId)
    }

    @PostMapping("/reset")
    fun reset(
        @ModelAttribute("applyForm") form: ScheduleApplyForm,
        redirectAttributes: RedirectAttributes,
    ): String {
        val count = scheduleApplyService.resetMode(form.locId, form.grpId, form.dtlId, currentUsername())
        redirectAttributes.addFlashAttribute(
            "message",
            if (count > 0) "게이트 ${count}대의 예약 모드를 해제했습니다." else "대상 게이트가 없습니다(위치를 선택하세요).",
        )
        return redirectTo(form.locId, form.grpId, form.dtlId)
    }

    private fun redirectTo(locId: Long?, grpId: Long?, dtlId: Long?): String {
        val params = buildList {
            locId?.let { add("locId=$it") }
            grpId?.let { add("grpId=$it") }
            dtlId?.let { add("dtlId=$it") }
        }
        return "redirect:/schedule" + if (params.isEmpty()) "" else "?" + params.joinToString("&")
    }

    private fun currentUsername(): String = SecurityContextHolder.getContext().authentication?.name ?: "system"
}
