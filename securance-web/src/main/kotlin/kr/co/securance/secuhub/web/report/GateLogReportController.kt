package kr.co.securance.secuhub.web.report

import jakarta.servlet.http.HttpServletResponse
import kr.co.securance.secuhub.domain.entity.GateLog
import kr.co.securance.secuhub.domain.repository.GateLogRepository
import kr.co.securance.secuhub.protocol.LogEventCodec
import kr.co.securance.secuhub.web.common.ExcelExportService
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

/**
 * `GATE_LOG`(0x61) 원시 수신 로그(`tb_gate_log`, [GateLogService][kr.co.securance.secuhub.server.tcp.GateLogService]가
 * 적재) 조회 화면. 계획서 3.8절 "로그 처리 — 신규 설계" 후속 작업으로, 기존 `/reports/logs`
 * (`LogReportController`, `tb_data_rcv_anal` 기반 통신/운영 로그)와는 원본 테이블이 다르다 — 그
 * 화면은 분석/집계된 이벤트를, 이 화면은 게이트가 `GATE_LOG` 패킷으로 보낸 원시 로그 엔트리를 그대로
 * 보여준다.
 *
 * `tb_gate_log`는 `tb_data_rcv_anal`과 달리 loc_id/grp_id를 반정규화해 저장하지 않으므로([GateLog]
 * KDoc 참고) 위치/그룹 필터는 제공하지 않는다 — IP/레인/이벤트유형/기간으로만 조회한다.
 */
data class GateLogSearchFilter(
    val dtlIp: String? = null,
    val dtlLaneNo: Int? = null,
    val eventType: Int? = null,
    val fromDate: LocalDate,
    val toDate: LocalDate,
) {
    fun toSpecification(): Specification<GateLog> = Specification { root, _, cb ->
        val predicates = buildList {
            add(cb.greaterThanOrEqualTo(root.get("eventTime"), fromDate.atStartOfDay()))
            add(cb.lessThan(root.get("eventTime"), toDate.plusDays(1).atStartOfDay()))
            if (!dtlIp.isNullOrBlank()) add(cb.equal(root.get<String>("dtlIp"), dtlIp))
            dtlLaneNo?.let { add(cb.equal(root.get<Int>("dtlLaneNo"), it)) }
            eventType?.let { add(cb.equal(root.get<Int>("eventType"), it)) }
        }
        cb.and(*predicates.toTypedArray())
    }
}

@Service
class GateLogReportService(private val gateLogRepository: GateLogRepository) {
    fun search(filter: GateLogSearchFilter, page: Int, size: Int) =
        gateLogRepository.findAll(
            filter.toSpecification(),
            PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "logId")),
        )

    fun searchForExport(filter: GateLogSearchFilter): List<GateLog> =
        gateLogRepository.findAll(
            filter.toSpecification(),
            PageRequest.of(0, MAX_EXPORT_ROWS, Sort.by(Sort.Direction.DESC, "logId")),
        ).content

    companion object {
        const val MAX_EXPORT_ROWS = 10_000
    }
}

/** 화면/엑셀 공통으로 쓰는 Event Type 표시명 — [LogEventCodec.EventType] 값 매핑. */
object GateLogEventTypeLabels {
    fun label(eventType: Int): String = when (eventType.toByte()) {
        LogEventCodec.EventType.ACCESS -> "출입(Access)"
        LogEventCodec.EventType.PARKING -> "주차(Parking)"
        LogEventCodec.EventType.DATA_OBJECT -> "데이터 객체"
        LogEventCodec.EventType.SYSTEM -> "시스템"
        LogEventCodec.EventType.COMMUNICATION -> "통신"
        else -> "기타(0x%02X)".format(eventType)
    }

    /** 필터 드롭다운에 노출할 (값, 표시명) 목록. */
    val options: List<Pair<Int, String>> = listOf(
        (LogEventCodec.EventType.ACCESS.toInt() and 0xFF) to "출입(Access)",
        (LogEventCodec.EventType.PARKING.toInt() and 0xFF) to "주차(Parking)",
        (LogEventCodec.EventType.DATA_OBJECT.toInt() and 0xFF) to "데이터 객체",
        (LogEventCodec.EventType.SYSTEM.toInt() and 0xFF) to "시스템",
        (LogEventCodec.EventType.COMMUNICATION.toInt() and 0xFF) to "통신",
    )
}

@Controller
@RequestMapping("/reports/gate-logs")
class GateLogReportController(
    private val gateLogReportService: GateLogReportService,
    private val excelExportService: ExcelExportService,
    private val menuProvider: MenuProvider,
) {
    @GetMapping
    fun search(
        @RequestParam(required = false) dtlIp: String?,
        @RequestParam(required = false) dtlLaneNo: Int?,
        @RequestParam(required = false) eventType: Int?,
        @RequestParam(required = false) fromDate: String?,
        @RequestParam(required = false) toDate: String?,
        @RequestParam(defaultValue = "0") page: Int,
        model: Model,
    ): String {
        val filter = toFilter(dtlIp, dtlLaneNo, eventType, fromDate, toDate, model)
        populateCommon(model, filter)
        model.addAttribute("result", gateLogReportService.search(filter, page, PAGE_SIZE))
        return "reports/gate-logs"
    }

    @GetMapping("/excel")
    fun exportExcel(
        @RequestParam(required = false) dtlIp: String?,
        @RequestParam(required = false) dtlLaneNo: Int?,
        @RequestParam(required = false) eventType: Int?,
        @RequestParam(required = false) fromDate: String?,
        @RequestParam(required = false) toDate: String?,
        response: HttpServletResponse,
    ) {
        // 엑셀 다운로드 응답에는 안내 메시지를 실을 화면이 없다(LogReportController.exportExcel과
        // 동일한 이유로 Model을 요구하지 않는 대신 빈 Model을 넘긴다).
        val filter = toFilter(dtlIp, dtlLaneNo, eventType, fromDate, toDate, ExtendedModelMap())
        val rows = gateLogReportService.searchForExport(filter)
        excelExportService.export(
            response = response,
            fileName = "수신로그(GATE_LOG)",
            headers = listOf(
                "발생일시(장치)", "IP", "레인", "이벤트유형", "Code", "ErrCode",
                "OperationMode", "DoorStatus", "FunctionCode", "UserData1", "UserData2",
            ),
            rows = rows.map {
                listOf(
                    it.eventTime.toString(), it.dtlIp, it.dtlLaneNo, GateLogEventTypeLabels.label(it.eventType),
                    it.code, it.errCode, it.operationMode, it.doorStatus, it.functionCode,
                    it.userData1, it.userData2,
                )
            },
        )
    }

    private fun toFilter(
        dtlIp: String?, dtlLaneNo: Int?, eventType: Int?, fromDate: String?, toDate: String?, model: Model,
    ): GateLogSearchFilter {
        val to = toDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now()
        var from = fromDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: to.minusDays(6)

        // 다른 리포트(LogReportController/AccessReportController)와 동일하게 조회기간 상한을 둔다
        // (코드 리뷰 지적, 2026-08-28) — 이 화면만 상한이 없어 넓은 기간을 반복 조회하면 매 요청마다
        // 넓은 eventTime 범위 스캔이 발생해 DB 부하가 커질 수 있었다.
        if (from.isBefore(to.minusMonths(3))) {
            model.addAttribute("error", "최근 3개월까지만 조회 가능합니다.")
            from = to.minusMonths(3)
        }
        return GateLogSearchFilter(dtlIp, dtlLaneNo, eventType, from, to)
    }

    private fun populateCommon(model: Model, filter: GateLogSearchFilter) {
        model.addAttribute("menu", menuProvider.menu())
        model.addAttribute("pageTitle", "수신 로그(GATE_LOG) 조회")
        model.addAttribute("eventTypeOptions", GateLogEventTypeLabels.options)
        model.addAttribute("dtlIp", filter.dtlIp)
        model.addAttribute("dtlLaneNo", filter.dtlLaneNo)
        model.addAttribute("eventType", filter.eventType)
        model.addAttribute("fromDate", filter.fromDate)
        model.addAttribute("toDate", filter.toDate)
    }

    private companion object {
        const val PAGE_SIZE = 30
    }
}
