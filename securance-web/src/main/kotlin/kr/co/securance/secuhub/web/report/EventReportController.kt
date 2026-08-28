package kr.co.securance.secuhub.web.report

import jakarta.servlet.http.HttpServletResponse
import kr.co.securance.secuhub.domain.entity.DataReceiveAnalysis
import kr.co.securance.secuhub.domain.repository.DataReceiveAnalysisRepository
import kr.co.securance.secuhub.web.common.ExcelExportService
import kr.co.securance.secuhub.web.gate.GateGroupService
import kr.co.securance.secuhub.web.gate.GateLocationService
import kr.co.securance.secuhub.web.menu.MenuProvider
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Controller
import org.springframework.stereotype.Service
import org.springframework.ui.ExtendedModelMap
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * #9 SR_F_ViewEvent — 이벤트/오류 이력 조회(레거시 9종 필터: 위치/그룹/게이트IP/이벤트유형/해결여부/
 * 기간 등). 필터 조합이 화면마다 달라 `@Query` 메서드 나열 대신 [Specification]으로 동적 조립한다.
 */
data class EventSearchFilter(
    val locId: Long? = null,
    val grpId: Long? = null,
    val dtlIp: String? = null,
    val analType: String? = null,
    val resolveYn: String? = null,
    val fromDate: LocalDate,
    val toDate: LocalDate,
) {
    fun toSpecification(): Specification<DataReceiveAnalysis> = Specification { root, _, cb ->
        val predicates = buildList {
            add(cb.greaterThanOrEqualTo(root.get("analDate"), "${fromDate.format(DAY)}0000"))
            add(cb.lessThanOrEqualTo(root.get("analDate"), "${toDate.format(DAY)}2359"))
            locId?.let { add(cb.equal(root.get<Long>("locId"), it)) }
            grpId?.let { add(cb.equal(root.get<Long>("grpId"), it)) }
            if (!dtlIp.isNullOrBlank()) add(cb.equal(root.get<String>("dtlIp"), dtlIp))
            // 버그 수정(2026-08-14): "유형"(NOR/EVT/PLM/STA) 필터는 파티션 키 컬럼 anal_tp를 봐야
            // 한다 — 이름이 비슷한 anal_type은 레거시가 항상 'B'만 채우는 무관한 컬럼이라, 이 조건을
            // analType으로 걸면 필터를 하나라도 선택하는 순간 결과가 항상 0건이었다
            // (DashboardPushService와 동일한 근본 원인, docs/작업일지.md 0030 참고).
            if (!analType.isNullOrBlank()) add(cb.equal(root.get<String>("analTp"), analType))
            if (!resolveYn.isNullOrBlank()) add(cb.equal(root.get<String>("resolveYn"), resolveYn))
        }
        cb.and(*predicates.toTypedArray())
    }

    private companion object {
        val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    }
}

@Service
class EventReportService(private val analysisRepository: DataReceiveAnalysisRepository) {
    fun search(filter: EventSearchFilter, page: Int, size: Int) =
        analysisRepository.findAll(
            filter.toSpecification(),
            PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "analId")),
        )

    /** 엑셀 내보내기 — 화면에 보이는 페이지가 아니라 조건에 맞는 전체를 내보내되, 무제한 메모리
     * 사용을 막기 위해 상한을 둔다(초과분은 기간을 좁혀 재조회하도록 안내). */
    fun searchForExport(filter: EventSearchFilter): List<DataReceiveAnalysis> =
        analysisRepository.findAll(
            filter.toSpecification(),
            PageRequest.of(0, MAX_EXPORT_ROWS, Sort.by(Sort.Direction.DESC, "analId")),
        ).content

    companion object {
        const val MAX_EXPORT_ROWS = 10_000
    }
}

@Controller
@RequestMapping("/reports/events")
class EventReportController(
    private val eventReportService: EventReportService,
    private val locationService: GateLocationService,
    private val groupService: GateGroupService,
    private val excelExportService: ExcelExportService,
    private val menuProvider: MenuProvider,
) {
    @GetMapping
    fun search(
        @RequestParam(required = false) locId: Long?,
        @RequestParam(required = false) grpId: Long?,
        @RequestParam(required = false) dtlIp: String?,
        @RequestParam(required = false) analType: String?,
        @RequestParam(required = false) resolveYn: String?,
        @RequestParam(required = false) fromDate: String?,
        @RequestParam(required = false) toDate: String?,
        @RequestParam(defaultValue = "0") page: Int,
        model: Model,
    ): String {
        val filter = toFilter(locId, grpId, dtlIp, analType, resolveYn, fromDate, toDate, model)
        populateCommon(model, filter, locId)
        model.addAttribute("result", eventReportService.search(filter, page, PAGE_SIZE))
        return "reports/events"
    }

    @GetMapping("/excel")
    fun exportExcel(
        @RequestParam(required = false) locId: Long?,
        @RequestParam(required = false) grpId: Long?,
        @RequestParam(required = false) dtlIp: String?,
        @RequestParam(required = false) analType: String?,
        @RequestParam(required = false) resolveYn: String?,
        @RequestParam(required = false) fromDate: String?,
        @RequestParam(required = false) toDate: String?,
        response: HttpServletResponse,
    ) {
        // 엑셀 다운로드 응답에는 안내 메시지를 실을 화면이 없다(LogReportController.exportExcel과
        // 동일한 이유로 Model을 요구하지 않는 대신 빈 Model을 넘긴다).
        val filter = toFilter(locId, grpId, dtlIp, analType, resolveYn, fromDate, toDate, ExtendedModelMap())
        val rows = eventReportService.searchForExport(filter)
        excelExportService.export(
            response = response,
            fileName = "이벤트이력",
            headers = listOf("발생일시", "유형", "IP", "레인", "화재경보", "메인모터", "서브모터", "해결여부", "해결자", "해결일시"),
            rows = rows.map {
                listOf(
                    // "유형" 컬럼도 같은 이유로 anal_tp를 내보낸다(analType은 항상 'B').
                    it.analDate, it.analTp, it.dtlIp, it.dtlLaneNo,
                    it.descFireAlarm, it.descMainMotorError, it.descSlaveMotorError,
                    it.resolveYn, it.resolveUser, it.resolveDate?.toString(),
                )
            },
        )
    }

    private fun toFilter(
        locId: Long?, grpId: Long?, dtlIp: String?, analType: String?, resolveYn: String?,
        fromDate: String?, toDate: String?, model: Model,
    ): EventSearchFilter {
        val to = toDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now()
        var from = fromDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: to.minusDays(6)

        // 다른 리포트(LogReportController/AccessReportController)와 동일하게 조회기간 상한을 둔다
        // (코드 리뷰 지적, 2026-08-28) — 이 화면만 상한이 없어 넓은 기간을 반복 조회하면 매 요청마다
        // 넓은 analDate 범위 스캔이 발생해 DB 부하가 커질 수 있었다.
        if (from.isBefore(to.minusMonths(3))) {
            model.addAttribute("error", "최근 3개월까지만 조회 가능합니다.")
            from = to.minusMonths(3)
        }
        return EventSearchFilter(locId, grpId, dtlIp, analType, resolveYn, from, to)
    }

    private fun populateCommon(model: Model, filter: EventSearchFilter, locId: Long?) {
        model.addAttribute("menu", menuProvider.menu())
        model.addAttribute("pageTitle", "이벤트 이력 조회")
        model.addAttribute("allLocations", locationService.findAllActive())
        model.addAttribute("allGroups", groupService.findAllActiveByLocation(locId))
        model.addAttribute("filter", filter)
        model.addAttribute("selectedLocId", filter.locId)
        model.addAttribute("selectedGrpId", filter.grpId)
        model.addAttribute("dtlIp", filter.dtlIp)
        model.addAttribute("analType", filter.analType)
        model.addAttribute("resolveYn", filter.resolveYn)
        model.addAttribute("fromDate", filter.fromDate)
        model.addAttribute("toDate", filter.toDate)
    }

    private companion object {
        const val PAGE_SIZE = 30
    }
}
