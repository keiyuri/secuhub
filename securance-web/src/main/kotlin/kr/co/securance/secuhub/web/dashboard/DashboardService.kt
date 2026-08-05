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

    fun summary(): DashboardSummary {
        val errors = recentUnresolvedErrors()
        return DashboardSummary(
            onlineGateCount = netStateRepository.countByDtlState("Y"),
            offlineGateCount = netStateRepository.countByDtlState("N"),
            unresolvedErrorCount = errors.size.toLong(),
            // 오늘 통행량(uvw_user_cnt 대응)은 loc/grp별 집계 화면과 함께 후속 작업으로 구현한다(계획서 5.2절).
            todayTrafficCount = 0,
        )
    }

    fun recentUnresolvedErrors(): List<GateErrorRow> {
        val sinceDate = LocalDateTime.now().minusDays(5).format(dateKeyFormatter)
        return dataReceiveAnalysisRepository
            .findRecentUnresolvedErrors(sinceDate, PageRequest.of(0, 8))
            .map { anal ->
                val description = listOfNotNull(
                    anal.descFireAlarm, anal.descMainMotorError, anal.descSlaveMotorError,
                ).firstOrNull() ?: "오류 상세 미확인"
                GateErrorRow(
                    dtlIp = anal.dtlIp,
                    description = description,
                    analDate = anal.analDate,
                    resolveYn = anal.resolveYn,
                )
            }
    }
}
