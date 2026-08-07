package kr.co.securance.secuhub.domain.repository

import kr.co.securance.secuhub.domain.entity.GateLog
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import java.time.LocalDateTime

/**
 * `tb_gate_log` 리포지토리.
 *
 * [existsByNaturalKey]는 `GateLogService`(securance-server)가 `GateDbWriteQueue`의 멱등성 요구사항
 * (find-or-create 패턴)을 지키기 위해 저장 직전 중복 여부를 확인하는 용도다 — `GateDbWriteQueue.kt`
 * 클래스 KDoc "주의(멱등성)" 참고. [JpaSpecificationExecutor]는 `GateLogReportController`(securance-web)의
 * 조회 화면이 필터 조합(IP/레인/이벤트유형/기간)을 동적으로 조립하는 데 쓴다.
 */
interface GateLogRepository : JpaRepository<GateLog, Long>, JpaSpecificationExecutor<GateLog> {

    fun existsByDtlIpAndDtlLaneNoAndEventTimeAndEventTypeAndCodeAndErrCodeAndFunctionCode(
        dtlIp: String,
        dtlLaneNo: Int,
        eventTime: LocalDateTime,
        eventType: Int,
        code: Int,
        errCode: Int,
        functionCode: Int,
    ): Boolean
}
