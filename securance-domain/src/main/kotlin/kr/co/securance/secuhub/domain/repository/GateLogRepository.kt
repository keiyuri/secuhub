package kr.co.securance.secuhub.domain.repository

import kr.co.securance.secuhub.domain.entity.GateLog
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
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

    /**
     * D5 데이터 보관 정책(2026-08-12) — `reg_date`(실제 DB 적재 시각, [GateLog.regDate] 참고)
     * 기준 컷오프보다 오래된 로그를 배치 단위로 삭제한다. `event_time`(장치 자체 시각)이 아니라
     * `reg_date`를 기준으로 삼는 이유는 장치 시계 오차/역전에 영향받지 않는 서버 수신 시각이
     * 보관 정책의 기준으로 더 안정적이기 때문이다.
     *
     * `@Transactional` 필요 이유는 [DataReceiveRepository.deleteBatchOlderThan] KDoc 참고
     * (Codex 적대적 리뷰 지적, 2026-08-20, [high]).
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "DELETE FROM tb_gate_log WHERE reg_date < :cutoff LIMIT :batchSize", nativeQuery = true)
    fun deleteBatchOlderThan(@Param("cutoff") cutoff: LocalDateTime, @Param("batchSize") batchSize: Int): Int
}
