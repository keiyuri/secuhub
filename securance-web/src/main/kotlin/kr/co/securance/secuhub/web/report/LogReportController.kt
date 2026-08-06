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
 * #10 SR_F_ViewLog — 통신/운영 로그 조회.
 *
 * 레거시(`SR_F_ViewLog.cs`/`SR_C_MariaDB.SelectGateLog`)는 `TB_DATA_RCV_LOG`를
 * `TB_LOG_EVENT`(event_type+object_code+code+err_code 조합)와 조인해 사람이 읽을 로그 메시지로
 * 치환한 별도 테이블이었다. 하지만 이번 전환에서는 `tb_log`/`tb_log_event`를 별도로 이식하지
 * 않기로 이미 확정했다(`V1__init_schema.sql`의 "tb_log_event(=tb_log 중복)는 이식하지 않는다" 주석,
 * 계획서 4.1절) — 통합된 `tb_data_rcv_anal`(#9 ViewEvent가 쓰는 것과 동일 테이블)이 그 대체 테이블이다.
 * 그래서 ViewLog는 새 테이블을 만드는 대신 같은 `tb_data_rcv_anal`을 원본으로 삼되, ViewEvent와는
 * 다른 화면으로 구분한다:
 *   - ViewEvent: 미해결 오류 추적 중심(resolve_yn 워크플로, PLM/STA 위주)
 *   - ViewLog(이 화면): 레거시 스펙 메모대로 정상(NOR) 통신까지 포함한 원시 로그 열람, 해결여부 없이
 *     "발생일시/유형/IP/레인/설명"만 보여준다. `analType`을 레거시 `log_cat`(ACCESS/PARKING/...) 대신
 *     그대로 노출한다 — 원본 event_type 코드가 현재 스키마에 없어 완전히 동일한 분류로 재현할 수는
 *     없지만, "통신 로그를 필터링해 조회한다"는 화면의 핵심 목적은 동일하게 제공한다.
 *   - 설명(desc) 컬럼은 레거시가 여러 desc_* 컬럼 중 값이 있는 것만 이어붙이던 방식을 그대로 옮겨
 *     [buildDescription]에서 조립하고, 레거시가 빈 설명 행을 결과에서 제외하던 것과 동일하게
 *     [LogSearchFilter.toSpecification]에서 desc_* 전체가 비어있는 행은 걸러낸다.
 *   - 조회기간은 레거시와 동일하게 기본 "전일~내일", 최대 3개월 제한(초과 시 안내 후 fromDate를
 *     전일로 되돌림)을 그대로 재현한다.
 */
data class LogSearchFilter(
    val locId: Long? = null,
    val grpId: Long? = null,
    val dtlIp: String? = null,
    val analType: String? = null,
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
            if (!analType.isNullOrBlank()) add(cb.equal(root.get<String>("analType"), analType))

            // 레거시 SelectGateLog가 빈 설명(sDesc)인 행을 제외하던 것과 동일 — desc_* 컬럼 중
            // 하나라도 채워져 있어야 한다.
            val descColumns = listOf(
                "descFireAlarm", "descOperation01", "descOperation02", "descOperation03", "descOperation04",
                "descOperation05", "descOperation06", "descOperation07", "descOperation08",
                "descSafety01", "descSafety02", "descSafety03", "descSafety04",
                "descGateStatus09", "descMainMotorError", "descSlaveMotorError",
            )
            add(cb.or(*descColumns.map { cb.isNotNull(root.get<String>(it)) }.toTypedArray()))
        }
        cb.and(*predicates.toTypedArray())
    }

    private companion object {
        val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    }
}

/** 화면에 보여줄 한 행 — [DataReceiveAnalysis]의 desc_* 다열을 [LogReportService.buildDescription]으로
 * 한 줄 문자열로 미리 합쳐서 템플릿에서 별도 함수 호출 없이 바로 쓸 수 있게 한다. */
data class LogRow(
    val analDate: String,
    val analType: String,
    val dtlIp: String,
    val dtlLaneNo: Int,
    val description: String,
)

@Service
class LogReportService(private val analysisRepository: DataReceiveAnalysisRepository) {
    fun search(filter: LogSearchFilter, page: Int, size: Int) =
        analysisRepository.findAll(
            filter.toSpecification(),
            PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "analId")),
        ).map { toRow(it) }

    fun searchForExport(filter: LogSearchFilter): List<DataReceiveAnalysis> =
        analysisRepository.findAll(
            filter.toSpecification(),
            PageRequest.of(0, MAX_EXPORT_ROWS, Sort.by(Sort.Direction.DESC, "analId")),
        ).content

    /** 값이 있는 desc_* 컬럼만 이어붙여 사람이 읽을 한 줄 설명을 만든다(레거시 TB_LOG_EVENT 조인
     * 결과를 대체 — 상세는 클래스 주석 참고). */
    fun buildDescription(row: DataReceiveAnalysis): String =
        listOfNotNull(
            row.descFireAlarm, row.descOperation01, row.descOperation02, row.descOperation03, row.descOperation04,
            row.descOperation05, row.descOperation06, row.descOperation07, row.descOperation08,
            row.descSafety01, row.descSafety02, row.descSafety03, row.descSafety04,
            row.descGateStatus09, row.descMainMotorError, row.descSlaveMotorError,
        ).filter { it.isNotBlank() }.joinToString(", ")

    private fun toRow(row: DataReceiveAnalysis) = LogRow(
        analDate = row.analDate,
        analType = row.analType,
        dtlIp = row.dtlIp,
        dtlLaneNo = row.dtlLaneNo,
        description = buildDescription(row),
    )

    companion object {
        const val MAX_EXPORT_ROWS = 10_000
    }
}

@Controller
@RequestMapping("/reports/logs")
class LogReportController(
    private val logReportService: LogReportService,
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
        @RequestParam(required = false) fromDate: String?,
        @RequestParam(required = false) toDate: String?,
        @RequestParam(defaultValue = "0") page: Int,
        model: Model,
    ): String {
        val filter = toFilter(locId, grpId, dtlIp, analType, fromDate, toDate, model)
        populateCommon(model, filter)
        model.addAttribute("result", logReportService.search(filter, page, PAGE_SIZE))
        return "reports/logs"
    }

    @GetMapping("/excel")
    fun exportExcel(
        @RequestParam(required = false) locId: Long?,
        @RequestParam(required = false) grpId: Long?,
        @RequestParam(required = false) dtlIp: String?,
        @RequestParam(required = false) analType: String?,
        @RequestParam(required = false) fromDate: String?,
        @RequestParam(required = false) toDate: String?,
        response: HttpServletResponse,
    ) {
        // 엑셀 다운로드 응답에는 안내 메시지를 실을 화면이 없으므로, 3개월 초과 경고는 버리고
        // 조건만 뽑아 쓴다(toFilter는 안내 메시지 부착을 위해 Model을 요구한다).
        val filter = toFilter(locId, grpId, dtlIp, analType, fromDate, toDate, ExtendedModelMap())
        val rows = logReportService.searchForExport(filter)
        excelExportService.export(
            response = response,
            fileName = "통신로그",
            headers = listOf("발생일시", "유형", "IP", "레인", "설명"),
            rows = rows.map { listOf(it.analDate, it.analType, it.dtlIp, it.dtlLaneNo, logReportService.buildDescription(it)) },
        )
    }

    private fun toFilter(
        locId: Long?, grpId: Long?, dtlIp: String?, analType: String?,
        fromDate: String?, toDate: String?, model: Model,
    ): LogSearchFilter {
        // 레거시 기본값: 전일~내일.
        val to = toDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now().plusDays(1)
        var from = fromDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now().minusDays(1)

        // 레거시: 조회기간이 3개월을 넘으면 "최근 3개월까지만 조회 가능" 안내 후 fromDate를 전일로 되돌린다.
        if (from.isBefore(to.minusMonths(3))) {
            model.addAttribute("error", "최근 3개월까지만 조회 가능합니다.")
            from = LocalDate.now().minusDays(1)
        }
        return LogSearchFilter(locId, grpId, dtlIp, analType, from, to)
    }

    private fun populateCommon(model: Model, filter: LogSearchFilter) {
        model.addAttribute("menu", menuProvider.menu())
        model.addAttribute("pageTitle", "통신/운영 로그 조회")
        model.addAttribute("allLocations", locationService.findAll())
        model.addAttribute("allGroups", groupService.findByLocation(filter.locId))
        model.addAttribute("filter", filter)
        model.addAttribute("selectedLocId", filter.locId)
        model.addAttribute("selectedGrpId", filter.grpId)
        model.addAttribute("dtlIp", filter.dtlIp)
        model.addAttribute("analType", filter.analType)
        model.addAttribute("fromDate", filter.fromDate)
        model.addAttribute("toDate", filter.toDate)
    }

    private companion object {
        const val PAGE_SIZE = 30
    }
}
