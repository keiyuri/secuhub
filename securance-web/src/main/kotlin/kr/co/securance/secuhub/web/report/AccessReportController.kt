package kr.co.securance.secuhub.web.report

import jakarta.servlet.http.HttpServletResponse
import kr.co.securance.secuhub.domain.repository.OprStatusRepository
import kr.co.securance.secuhub.web.common.ExcelExportService
import kr.co.securance.secuhub.web.gate.GateGroupService
import kr.co.securance.secuhub.web.gate.GateLocationService
import kr.co.securance.secuhub.web.menu.MenuProvider
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
 * #8 SR_F_ViewAccess — 위치/그룹별 통행량(이용자 통계) 조회. `tb_opr_status`(대시보드
 * `uvw_user_cnt` 위젯과 동일한 원본 테이블, 계획서 5.2절)를 기간으로 재조회한다.
 */
@Service
class AccessReportService(private val oprStatusRepository: OprStatusRepository) {
    fun search(locId: Long, grpId: Long, fromDate: LocalDate, toDate: LocalDate): AccessReportResult {
        val rows = oprStatusRepository.findByLocIdAndGrpIdAndIdOprDateBetween(
            locId, grpId, "${fromDate.format(DAY)}0000", "${toDate.format(DAY)}2359",
        )
        return AccessReportResult(
            rows = rows,
            totalIn = rows.sumOf { it.inTotal ?: 0L },
            totalOut = rows.sumOf { it.outTotal ?: 0L },
            totalDoor = rows.sumOf { it.doorTotal ?: 0L },
        )
    }

    private companion object {
        val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    }
}

data class AccessReportResult(
    val rows: List<kr.co.securance.secuhub.domain.entity.OprStatus>,
    val totalIn: Long,
    val totalOut: Long,
    val totalDoor: Long,
)

@Controller
@RequestMapping("/reports/access")
class AccessReportController(
    private val accessReportService: AccessReportService,
    private val locationService: GateLocationService,
    private val groupService: GateGroupService,
    private val excelExportService: ExcelExportService,
    private val menuProvider: MenuProvider,
) {
    @GetMapping
    fun search(
        @RequestParam(required = false) locId: Long?,
        @RequestParam(required = false) grpId: Long?,
        @RequestParam(required = false) fromDate: String?,
        @RequestParam(required = false) toDate: String?,
        model: Model,
    ): String {
        val range = resolveRange(fromDate, toDate, model)
        model.addAttribute("menu", menuProvider.menu())
        model.addAttribute("pageTitle", "이용자 통계 조회")
        model.addAttribute("allLocations", locationService.findAll())
        model.addAttribute("allGroups", groupService.findByLocation(locId))
        model.addAttribute("selectedLocId", locId)
        model.addAttribute("selectedGrpId", grpId)
        model.addAttribute("fromDate", range.first)
        model.addAttribute("toDate", range.second)
        if (locId != null && grpId != null) {
            model.addAttribute("result", accessReportService.search(locId, grpId, range.first, range.second))
        }
        return "reports/access"
    }

    @GetMapping("/excel")
    fun exportExcel(
        @RequestParam locId: Long,
        @RequestParam grpId: Long,
        @RequestParam(required = false) fromDate: String?,
        @RequestParam(required = false) toDate: String?,
        response: HttpServletResponse,
    ) {
        // 엑셀 다운로드 응답에는 안내 메시지를 실을 화면이 없다(LogReportController.exportExcel과
        // 동일한 이유로 Model을 요구하지 않는 대신 빈 Model을 넘긴다).
        val range = resolveRange(fromDate, toDate, ExtendedModelMap())
        val result = accessReportService.search(locId, grpId, range.first, range.second)
        excelExportService.export(
            response = response,
            fileName = "이용자통계",
            headers = listOf("일시", "순번", "레인", "총 통행", "입", "출", "도어"),
            rows = result.rows.map {
                listOf(it.id.oprDate, it.id.oprSeq, it.id.dtlLaneNo, it.totalCount, it.inTotal, it.outTotal, it.doorTotal)
            },
        )
    }

    private fun resolveRange(fromDate: String?, toDate: String?, model: Model): Pair<LocalDate, LocalDate> {
        val to = toDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now()
        var from = fromDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: to.minusDays(6)

        // 다른 리포트(LogReportController)와 동일하게 조회기간 상한을 둔다(2026-08-13 코드 리뷰) —
        // 이 화면만 상한이 없어 넓은 기간을 선택하면 tb_opr_status 전체 로우를 페이지 없이 한 번에
        // 조회해 메모리/응답 지연 위험이 있었다.
        if (from.isBefore(to.minusMonths(3))) {
            model.addAttribute("error", "최근 3개월까지만 조회 가능합니다.")
            from = to.minusMonths(3)
        }
        return from to to
    }
}
