package kr.co.securance.secuhub.web.report

import jakarta.servlet.http.HttpServletResponse
import kr.co.securance.secuhub.domain.repository.OprStatusRepository
import kr.co.securance.secuhub.web.common.ExcelExportService
import kr.co.securance.secuhub.web.gate.GateGroupService
import kr.co.securance.secuhub.web.gate.GateLocationService
import kr.co.securance.secuhub.web.menu.MenuProvider
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
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
    /**
     * 코드 리뷰 지적(2026-08-28): 형제 리포트들(LogReportController 등)과 달리 이 화면만
     * 페이지네이션 없이 조건에 맞는 전체 행을 로드했다 — 기간 상한(3개월)은 있어도 건수 상한이
     * 없어, 통행량이 많은 그룹을 3개월치로 조회하면(레인 x 분 버킷 단위라 그룹당 하루 1440행
     * 이상 가능) 수십만 행이 한 요청에 메모리로 올라갈 수 있었다. 화면 표시는 [Page]로 나누고,
     * 상단 요약 합계는 DB에서 SUM으로 직접 집계해(행을 메모리로 가져오지 않음) 페이지 여부와
     * 무관하게 항상 전체 기간 합계를 보여준다.
     */
    fun search(locId: Long, grpId: Long, fromDate: LocalDate, toDate: LocalDate, page: Int): AccessReportResult {
        val from = "${fromDate.format(DAY)}0000"
        val to = "${toDate.format(DAY)}2359"
        val rows = oprStatusRepository.findByLocIdAndGrpIdAndIdOprDateBetween(
            locId, grpId, from, to,
            PageRequest.of(page, PAGE_SIZE, Sort.by(Sort.Direction.DESC, "id.oprDate", "id.oprSeq")),
        )
        val totals = oprStatusRepository.sumAccessTotals(locId, grpId, from, to)
        return AccessReportResult(
            rows = rows,
            totalIn = totals.totalIn,
            totalOut = totals.totalOut,
            totalDoor = totals.totalDoor,
        )
    }

    /**
     * Codex 적대적 리뷰 지적(2026-08-28): 이전에는 `.content`만 반환해, 조회 결과가
     * [MAX_EXPORT_ROWS]를 넘으면 오래된 행이 아무 고지 없이 조용히 잘렸다 — HTTP 200과 정상
     * 파일명이 그대로 내려가 감사/운영 담당자가 이를 "전체 결과"로 오인할 위험이 있었다.
     * [Page] 전체(=`totalElements` 포함)를 반환해, 호출측(컨트롤러)이 상한 초과 여부를 판단해
     * 명시적으로 내보내기를 거부할 수 있게 한다.
     */
    fun searchForExport(locId: Long, grpId: Long, fromDate: LocalDate, toDate: LocalDate): Page<kr.co.securance.secuhub.domain.entity.OprStatus> =
        oprStatusRepository.findByLocIdAndGrpIdAndIdOprDateBetween(
            locId, grpId, "${fromDate.format(DAY)}0000", "${toDate.format(DAY)}2359",
            PageRequest.of(0, MAX_EXPORT_ROWS, Sort.by(Sort.Direction.DESC, "id.oprDate", "id.oprSeq")),
        )

    companion object {
        const val PAGE_SIZE = 30
        const val MAX_EXPORT_ROWS = 10_000
        private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    }
}

data class AccessReportResult(
    val rows: Page<kr.co.securance.secuhub.domain.entity.OprStatus>,
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
        @RequestParam(defaultValue = "0") page: Int,
        model: Model,
    ): String {
        val range = resolveRange(fromDate, toDate, model)
        model.addAttribute("menu", menuProvider.menu())
        model.addAttribute("pageTitle", "이용자 통계 조회")
        model.addAttribute("allLocations", locationService.findAllActive())
        model.addAttribute("allGroups", groupService.findAllActiveByLocation(locId))
        model.addAttribute("selectedLocId", locId)
        model.addAttribute("selectedGrpId", grpId)
        model.addAttribute("fromDate", range.first)
        model.addAttribute("toDate", range.second)
        if (locId != null && grpId != null) {
            model.addAttribute("result", accessReportService.search(locId, grpId, range.first, range.second, page))
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
        val page = accessReportService.searchForExport(locId, grpId, range.first, range.second)
        // Codex 적대적 리뷰 지적(2026-08-28): 조회 결과가 상한(MAX_EXPORT_ROWS)을 넘으면 오래된
        // 행이 조용히 잘린 채로 "정상" 엑셀 파일이 내려갔다 — 감사/운영 담당자가 이를 완전한
        // 결과로 오인할 위험이 있어, 상한 초과 시에는 파일을 내려주는 대신 명시적으로 거부하고
        // 조회 기간을 좁히도록 안내한다.
        if (page.totalElements > AccessReportService.MAX_EXPORT_ROWS) {
            response.sendError(
                HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, // 413 — Jakarta Servlet API의 표준 상수명(SC_PAYLOAD_TOO_LARGE 없음)
                "조회 결과가 ${AccessReportService.MAX_EXPORT_ROWS}건을 초과합니다(총 ${page.totalElements}건). " +
                    "조회 기간을 좁혀 다시 시도하세요.",
            )
            return
        }
        excelExportService.export(
            response = response,
            fileName = "이용자통계",
            headers = listOf("일시", "순번", "레인", "총 통행", "입", "출", "도어"),
            rows = page.content.map {
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
