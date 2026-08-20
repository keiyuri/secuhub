package kr.co.securance.secuhub.scheduler.job

import kr.co.securance.secuhub.domain.repository.DataReceiveAckRepository
import kr.co.securance.secuhub.domain.repository.DataReceiveAnalysisRepository
import kr.co.securance.secuhub.domain.repository.DataReceiveFailRepository
import kr.co.securance.secuhub.domain.repository.DataReceiveRepository
import kr.co.securance.secuhub.domain.repository.DataSendRepository
import kr.co.securance.secuhub.domain.repository.GateLogRepository
import kr.co.securance.secuhub.domain.repository.OprStatusOutboxRepository
import kr.co.securance.secuhub.domain.repository.OprStatusRepository
import kr.co.securance.secuhub.scheduler.config.SchedulerProperties
import org.quartz.DisallowConcurrentExecution
import org.quartz.JobExecutionContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.scheduling.quartz.QuartzJobBean
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * D5 데이터 보관/정리(retention) 정책 잡(2026-08-12, `docs/SR_Speed_Server_전환_계획.md` D5 항목).
 *
 * 레거시에도 없던 기능이라 "전환 누락"은 아니지만, `tb_data_rcv`/`tb_data_rcv_anal`/`tb_gate_log`는
 * 상태 패킷이 초 단위로 쌓이는 고빈도 적재 테이블인데도 정리하는 잡·쿼리가 신규 코드베이스에도
 * 전무했다. 사용자 결정에 따라 보관 기간을 [SchedulerProperties.retentionDays](기본 365일)로 두고,
 * 컷오프보다 오래된 행을 배치 단위로 영구 삭제한다.
 *
 * ## 정리 대상 확장(코드 리뷰 지적 D-3, 2026-08-20)
 * 최초 도입 시 `tb_data_rcv`/`tb_data_rcv_anal`/`tb_gate_log` 세 테이블만 대상으로 삼았는데,
 * 아래 테이블들도 동일하게 고빈도로 쌓이면서도 정리 대상에서 빠져 있었다 — 무기한 증가한다:
 * - `tb_data_rcv_ack`(장비 ACK 수신마다 1행), `tb_data_rcv_fail`(체크섬 실패마다 1행)
 * - `tb_opr_status`(레인 × 분 버킷마다 1행)
 * - `tb_data_snd`(제어 명령 발행마다 1행 — 단, 아직 처리 중인 행은 지우면 안 되므로 **종결된
 *   행만**([DataSendRepository.deleteBatchOlderThan] 참고) 대상으로 한다)
 * - `tb_opr_status_outbox`(큐 드롭/최종실패 시 durable 저장 — 삭제 쿼리[OprStatusOutboxRepository.deleteProcessedOlderThan]는
 *   이미 있었으나 호출하는 곳이 없어 죽은 코드였다)
 *
 * **배치 삭제인 이유**: 대상 테이블이 수백만 건 규모일 수 있어 `DELETE ... WHERE date < cutoff`를
 * 한 번에 실행하면 트랜잭션/락을 오래 쥐게 된다. [SchedulerProperties.retentionBatchSize] 단위로
 * 나눠 반복 삭제하고, [SchedulerProperties.retentionMaxBatchesPerRun]으로 한 번의 잡 실행이 지우는
 * 총량에 상한을 둔다 — 상한을 넘는 잔여분은 다음날 실행에서 이어서 지운다.
 *
 * **되돌릴 수 없는 삭제임에 주의**: 이 잡이 지우는 행은 백업이 아니면 복구할 수 없다. 보존 기간을
 * 바꾸려면 `securance.scheduler.retention-days`만 조정하면 되고, 잡 자체를 끄려면
 * `securance.scheduler.retention-enabled=false`로 설정한다.
 */
@DisallowConcurrentExecution
class RetentionCleanupJob : QuartzJobBean() {

    @Autowired
    private lateinit var dataReceiveRepository: DataReceiveRepository

    @Autowired
    private lateinit var dataReceiveAnalysisRepository: DataReceiveAnalysisRepository

    @Autowired
    private lateinit var gateLogRepository: GateLogRepository

    @Autowired
    private lateinit var dataReceiveAckRepository: DataReceiveAckRepository

    @Autowired
    private lateinit var dataReceiveFailRepository: DataReceiveFailRepository

    @Autowired
    private lateinit var oprStatusRepository: OprStatusRepository

    @Autowired
    private lateinit var oprStatusOutboxRepository: OprStatusOutboxRepository

    @Autowired
    private lateinit var dataSendRepository: DataSendRepository

    @Autowired
    private lateinit var properties: SchedulerProperties

    private val logger = LoggerFactory.getLogger(RetentionCleanupJob::class.java)

    override fun executeInternal(context: JobExecutionContext) {
        if (!properties.retentionEnabled) {
            logger.debug("[Retention] retention-enabled=false, 이번 실행은 건너뜁니다.")
            return
        }

        val cutoffDateTime = LocalDateTime.now().minusDays(properties.retentionDays)
        // tb_data_rcv/tb_data_rcv_anal/tb_opr_status는 날짜 컬럼이 `yyyyMMddHHmm` 문자열이라 같은
        // 형식으로 컷오프를 만들어야 사전식 비교(<)가 시간 비교와 일치한다.
        val cutoffKey = cutoffDateTime.format(DATE_KEY_FORMAT)
        // tb_data_rcv_ack/tb_data_rcv_fail/tb_data_snd는 초 단위 `yyyyMMddHHmmss` 문자열이다
        // ([GatePacketPersister]의 TIMESTAMP_FORMAT, [GateControlDispatcher]의 SEND_DATE_FORMAT).
        val cutoffTimestampKey = cutoffDateTime.format(TIMESTAMP_KEY_FORMAT)

        val dataReceiveDeleted = deleteInBatches("tb_data_rcv") {
            dataReceiveRepository.deleteBatchOlderThan(cutoffKey, properties.retentionBatchSize)
        }
        val analysisDeleted = deleteInBatches("tb_data_rcv_anal") {
            dataReceiveAnalysisRepository.deleteBatchOlderThan(cutoffKey, properties.retentionBatchSize)
        }
        val gateLogDeleted = deleteInBatches("tb_gate_log") {
            gateLogRepository.deleteBatchOlderThan(cutoffDateTime, properties.retentionBatchSize)
        }
        val ackDeleted = deleteInBatches("tb_data_rcv_ack") {
            dataReceiveAckRepository.deleteBatchOlderThan(cutoffTimestampKey, properties.retentionBatchSize)
        }
        val failDeleted = deleteInBatches("tb_data_rcv_fail") {
            dataReceiveFailRepository.deleteBatchOlderThan(cutoffTimestampKey, properties.retentionBatchSize)
        }
        val oprStatusDeleted = deleteInBatches("tb_opr_status") {
            oprStatusRepository.deleteBatchOlderThan(cutoffKey, properties.retentionBatchSize)
        }
        val oprStatusOutboxDeleted = deleteInBatches("tb_opr_status_outbox") {
            oprStatusOutboxRepository.deleteProcessedOlderThan(cutoffDateTime, properties.retentionBatchSize)
        }
        val dataSendDeleted = deleteInBatches("tb_data_snd") {
            dataSendRepository.deleteBatchOlderThan(cutoffTimestampKey, properties.retentionBatchSize)
        }

        logger.info(
            "[Retention] 정리 완료(cutoff={}): tb_data_rcv={}건, tb_data_rcv_anal={}건, tb_gate_log={}건, " +
                "tb_data_rcv_ack={}건, tb_data_rcv_fail={}건, tb_opr_status={}건, tb_opr_status_outbox={}건, " +
                "tb_data_snd={}건 삭제",
            cutoffKey,
            dataReceiveDeleted,
            analysisDeleted,
            gateLogDeleted,
            ackDeleted,
            failDeleted,
            oprStatusDeleted,
            oprStatusOutboxDeleted,
            dataSendDeleted,
        )
    }

    /**
     * 한 테이블에 대해 배치 삭제를 [SchedulerProperties.retentionMaxBatchesPerRun]회까지 반복한다.
     * 삭제된 행 수가 배치 크기보다 작으면(= 더 지울 게 없으면) 조기 종료한다.
     */
    private fun deleteInBatches(tableName: String, deleteBatch: () -> Int): Long {
        var totalDeleted = 0L
        repeat(properties.retentionMaxBatchesPerRun) {
            val deleted = deleteBatch()
            totalDeleted += deleted
            if (deleted < properties.retentionBatchSize) return totalDeleted
        }
        logger.warn(
            "[Retention] {}: 배치 상한({}회)에 도달해 이번 실행을 종료합니다 — 잔여분은 다음 실행에서 이어집니다.",
            tableName,
            properties.retentionMaxBatchesPerRun,
        )
        return totalDeleted
    }

    private companion object {
        val DATE_KEY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmm")
        val TIMESTAMP_KEY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
    }
}
