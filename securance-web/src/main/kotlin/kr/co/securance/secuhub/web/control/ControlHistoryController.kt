package kr.co.securance.secuhub.web.control

import kr.co.securance.secuhub.domain.entity.DataSend
import kr.co.securance.secuhub.domain.repository.DataSendRepository
import kr.co.securance.secuhub.web.menu.MenuProvider
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Controller
import org.springframework.stereotype.Service
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 제어 명령 이력 조회 — 사이드바 메뉴("제어 명령 이력", `/control/history`)에 오래전부터
 * 링크되어 있었으나 실제 컨트롤러가 없어 404였다(2026-08-11 갭 분석 B5).
 *
 * `tb_data_snd`(제어 명령 발송 큐, [DataSend] KDoc 참고)를 읽기 전용으로 조회한다. 레거시에
 * 해당하는 전용 화면 이름은 확인되지 않았으나(계획서 문서에 이 화면의 레거시 대응 언급 없음),
 * `snd_yn`/`chk_yn` 상태 전이가 운영 중 명령 성공/실패를 추적하는 유일한 창구라 조회 화면
 * 자체는 반드시 필요하다. [EventReportController]와 동일한 검색+페이지네이션 패턴을 따른다.
 */
data class ControlHistoryFilter(
    val dtlIp: String? = null,
    val sndTypeCd: String? = null,
    val sndYn: String? = null,
    val chkYn: String? = null,
    val fromDate: LocalDate,
    val toDate: LocalDate,
) {
    fun toSpecification(): Specification<DataSend> = Specification { root, _, cb ->
        val predicates = buildList {
            add(cb.greaterThanOrEqualTo(root.get("sndDate"), "${fromDate.format(DAY)}000000"))
            add(cb.lessThanOrEqualTo(root.get("sndDate"), "${toDate.format(DAY)}235959"))
            if (!dtlIp.isNullOrBlank()) add(cb.equal(root.get<String>("dtlIp"), dtlIp))
            if (!sndTypeCd.isNullOrBlank()) add(cb.equal(root.get<String>("sndTypeCd"), sndTypeCd))
            if (!sndYn.isNullOrBlank()) add(cb.equal(root.get<String>("sndYn"), sndYn))
            if (!chkYn.isNullOrBlank()) add(cb.equal(root.get<String>("chkYn"), chkYn))
        }
        cb.and(*predicates.toTypedArray())
    }

    private companion object {
        val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    }
}

@Service
class ControlHistoryService(private val dataSendRepository: DataSendRepository) {
    fun search(filter: ControlHistoryFilter, page: Int, size: Int) =
        dataSendRepository.findAll(
            filter.toSpecification(),
            PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "sndId")),
        )
}

@Controller
@RequestMapping("/control/history")
class ControlHistoryController(
    private val controlHistoryService: ControlHistoryService,
    private val menuProvider: MenuProvider,
) {
    @GetMapping
    fun search(
        @RequestParam(required = false) dtlIp: String?,
        @RequestParam(required = false) sndTypeCd: String?,
        @RequestParam(required = false) sndYn: String?,
        @RequestParam(required = false) chkYn: String?,
        @RequestParam(required = false) fromDate: String?,
        @RequestParam(required = false) toDate: String?,
        @RequestParam(defaultValue = "0") page: Int,
        model: Model,
    ): String {
        val today = LocalDate.now()
        // 다른 리포트 컨트롤러(EventReportController 등)와 동일하게 파싱 실패를 흡수한다
        // (2026-08-13 코드 리뷰) — 이 컨트롤러만 LocalDate::parse를 그대로 써서, 잘못된 형식의
        // 쿼리 파라미터(오타/봇 스캔 등)가 들어오면 DateTimeParseException이 그대로 올라가 500
        // 에러 페이지가 노출됐다.
        val filter = ControlHistoryFilter(
            dtlIp = dtlIp,
            sndTypeCd = sndTypeCd,
            sndYn = sndYn,
            chkYn = chkYn,
            fromDate = fromDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: today.minusDays(7),
            toDate = toDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: today,
        )
        model.addAttribute("menu", menuProvider.menu())
        model.addAttribute("pageTitle", "제어 명령 이력")
        model.addAttribute("dtlIp", dtlIp)
        model.addAttribute("sndTypeCd", sndTypeCd)
        model.addAttribute("sndYn", sndYn)
        model.addAttribute("chkYn", chkYn)
        model.addAttribute("fromDate", filter.fromDate)
        model.addAttribute("toDate", filter.toDate)
        model.addAttribute("result", controlHistoryService.search(filter, page, PAGE_SIZE))
        return "control/history"
    }

    private companion object {
        const val PAGE_SIZE = 30
    }
}
