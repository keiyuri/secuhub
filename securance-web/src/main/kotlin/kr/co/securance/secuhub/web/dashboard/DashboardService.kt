package kr.co.securance.secuhub.web.dashboard

import kr.co.securance.secuhub.domain.repository.DataReceiveAnalysisRepository
import kr.co.securance.secuhub.domain.repository.NetStateRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 대시보드 위젯 데이터 조회 서비스. `uvw_anlz_error` 등 레거시 DB 뷰를
 * 리포지토리 쿼리 + 서비스 계층으로 이식한다(계획서 4.4/5.2절).
 */
@Service
class DashboardService(
    private val netStateRepository: NetStateRepository,
    private val dataReceiveAnalysisRepository: DataReceiveAnalysisRepository,
) {
    private val dateKeyFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmm")

    /**
     * 대시보드 화면 1회 렌더링에 필요한 요약 통계 + 최근 오류 목록을 한 번에 묶어 반환한다.
     * (이전에는 컨트롤러가 summary()/recentUnresolvedErrors()를 각각 호출해 동일 조건의
     * 미해결 오류 조회 쿼리가 페이지 로드마다 중복 실행되었다.)
     */
    fun loadDashboard(): DashboardView {
        val sinceDate = LocalDateTime.now().minusDays(5).format(dateKeyFormatter)
        val errors = recentUnresolvedErrors(sinceDate)
        val summary = DashboardSummary(
            onlineGateCount = netStateRepository.countByDtlState("Y"),
            offlineGateCount = netStateRepository.countByDtlState("N"),
            // 목록은 상위 8건만 보여주지만, 카운트는 별도의 COUNT 쿼리로 실제 총 건수를 센다
            // (페이지 크기로 미해결 오류 개수가 8건에 잘려 보이는 버그 방지).
            unresolvedErrorCount = dataReceiveAnalysisRepository.countRecentUnresolvedErrors(sinceDate),
            // 오늘 통행량(uvw_user_cnt 대응)은 loc/grp별 집계 화면과 함께 후속 작업으로 구현한다(계획서 5.2절).
            todayTrafficCount = 0,
        )
        return DashboardView(summary = summary, recentErrors = errors)
    }

    private fun recentUnresolvedErrors(sinceDate: String): List<GateErrorRow> =
        dataReceiveAnalysisRepository
            .findRecentUnresolvedErrors(sinceDate, PageRequest.of(0, 8))
            .map { anal ->
                // desc_* 컬럼은 스키마상 NOT NULL DEFAULT ''이라 "값 없음"이 null이 아니라 빈
                // 문자열로 들어온다 — null 여부가 아니라 공백 여부로 판단해야 한다.
                val description = listOf(
                    anal.descFireAlarm, anal.descMainMotorError, anal.descSlaveMotorError,
                ).firstOrNull { it.isNotBlank() } ?: "오류 상세 미확인"
                GateErrorRow(
                    dtlIp = anal.dtlIp,
                    description = description,
                    analDate = anal.analDate,
                    resolveYn = anal.resolveYn,
                )
            }
}

/** [DashboardController]가 한 번의 모델 조립으로 화면에 필요한 데이터를 모두 받도록 묶은 뷰 모델. */
data class DashboardView(
    val summary: DashboardSummary,
    val recentErrors: List<GateErrorRow>,
)
