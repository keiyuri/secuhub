package kr.co.securance.secuhub.web.dashboard

/** 대시보드 1행 SmallBox 위젯(계획서 5.2절)에 쓰이는 요약 지표. */
data class DashboardSummary(
    val onlineGateCount: Long,
    val offlineGateCount: Long,
    val unresolvedErrorCount: Long,
    val todayTrafficCount: Long,
)

/** `uvw_anlz_error` 대응 위젯(계획서 5.2/4.4절)에 표시할 오류 1건. */
data class GateErrorRow(
    val dtlIp: String,
    val description: String,
    val analDate: String,
    val resolveYn: String,
)
