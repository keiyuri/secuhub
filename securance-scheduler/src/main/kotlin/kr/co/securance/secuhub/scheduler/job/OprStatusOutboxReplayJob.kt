package kr.co.securance.secuhub.scheduler.job

import kotlinx.coroutines.runBlocking
import kr.co.securance.secuhub.domain.entity.OprStatusOutbox
import kr.co.securance.secuhub.domain.repository.OprStatusOutboxRepository
import kr.co.securance.secuhub.scheduler.config.SchedulerProperties
import kr.co.securance.secuhub.server.db.OprStatusPersister
import org.quartz.DisallowConcurrentExecution
import org.quartz.JobExecutionContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.quartz.QuartzJobBean
import java.time.LocalDateTime

/**
 * 큐 드롭 durable 재작성(2026-08-12, `docs/작업일지.md` 참고) — `tb_opr_status_outbox`(0003 항목
 * codex 적대적 리뷰 지적, [OprStatusPersister] KDoc 참고)의 미처리 행을 주기적으로 재처리한다.
 *
 * `GateDbWriteQueue`가 통행량 집계(`UpsertOprStatus`) 작업을 드롭하거나 최종 실패하면
 * [OprStatusPersister]가 그 즉시 원시값을 outbox에 동기 저장해둔다. 이 잡은 그 행들을 오래된
 * 순서로 읽어 [OprStatusPersister.replayOutboxEntry]로 원래 UPSERT를 재시도하고, 성공하면
 * `processed=true`로 표시한다. 재시도 자체가 다시 실패해도(예: 재처리 시점에도 DB 장애가 계속되는
 * 경우) 행은 미처리 상태로 남아 다음 실행에서 다시 시도되며, [SchedulerProperties.oprStatusOutboxMaxRetries]를
 * 넘기면 더 이상 자동 재시도하지 않고 경고 로그만 남긴다(운영자가 직접 확인해야 하는 상태로 전환).
 */
@DisallowConcurrentExecution
class OprStatusOutboxReplayJob : QuartzJobBean() {

    @Autowired
    private lateinit var outboxRepository: OprStatusOutboxRepository

    @Autowired
    private lateinit var oprStatusPersister: OprStatusPersister

    @Autowired
    private lateinit var properties: SchedulerProperties

    private val logger = LoggerFactory.getLogger(OprStatusOutboxReplayJob::class.java)

    override fun executeInternal(context: JobExecutionContext) {
        if (!properties.oprStatusOutboxReplayEnabled) {
            logger.debug("[OprStatusOutboxReplay] opr-status-outbox-replay-enabled=false, 이번 실행은 건너뜁니다.")
            return
        }

        val pending = outboxRepository.findByProcessedFalseOrderByOutboxIdAsc(
            PageRequest.of(0, properties.oprStatusOutboxBatchSize),
        )
        if (pending.isEmpty()) return

        var replayed = 0
        var gaveUp = 0
        for (entry in pending) {
            try {
                // replayOutboxEntry는 이제 GateDbWriteQueue를 거쳐 라이브 상태 패킷 처리와 같은
                // 파티션(dtlIp)에서 직렬화된다(코드 리뷰 지적, OprStatusPersister KDoc 참고) —
                // Quartz 잡은 동기식이라 결과를 runBlocking으로 기다린다.
                val succeeded = runBlocking { oprStatusPersister.replayOutboxEntry(entry) }
                if (!succeeded) {
                    if (recordReplayFailure(entry, IllegalStateException("큐 드롭 또는 최종 실패"))) gaveUp++
                    continue
                }
                entry.processed = true
                entry.processedDate = LocalDateTime.now()
                outboxRepository.save(entry)
                replayed++
            } catch (ex: Exception) {
                if (recordReplayFailure(entry, ex)) gaveUp++
            }
        }

        if (replayed > 0 || gaveUp > 0) {
            logger.info("[OprStatusOutboxReplay] 재처리 완료: 성공={}건, 포기={}건", replayed, gaveUp)
        }
    }

    /**
     * 재처리 실패 1건을 기록한다 — retryCount를 올리고, 상한에 도달했으면 포기(더 이상 자동 재시도하지
     * 않음)로 표시한다. 반환값은 "이번에 포기했는지" 여부.
     *
     * catch 블록 본문에서 직접 `outboxRepository.save(...)`를 호출하는 대신 별도 메서드로 뺐다 — 같은
     * JVM 프로세스 안에서 다른 Mockito(inline mock-maker) 스텁이 던진 예외를 처리하는 catch 블록의
     * 어휘적 스코프 안에서 곧바로 또 다른 mock 호출을 이어가면, 관측상 재현 가능한 원인불명의
     * `NullPointerException`이 발생했다(2026-08-12, 이 잡 테스트 작성 중 실측). 호출을 별도 메서드로
     * 분리해 catch 블록의 어휘적 범위를 벗어나게 하면 문제가 사라진다 — 로직상 동등하며 부작용은 없다.
     */
    private fun recordReplayFailure(entry: OprStatusOutbox, ex: Exception): Boolean {
        entry.retryCount += 1
        val gaveUp = entry.retryCount >= properties.oprStatusOutboxMaxRetries
        if (gaveUp) {
            logger.error(
                "[OprStatusOutboxReplay] outbox_id={}가 재시도 상한({}회)에 도달했습니다 — " +
                    "이 통행량 집계는 자동 복구를 포기합니다. 수동 확인이 필요합니다.",
                entry.outboxId, properties.oprStatusOutboxMaxRetries, ex,
            )
            // processed는 여전히 false로 남긴다 — 완료 표시가 아니라 "포기"이므로, 조회 화면에서
            // 미처리로 계속 잡히게 해 운영자가 발견할 수 있게 한다. retryCount만으로 재시도 중단을 판단한다.
        } else {
            logger.warn(
                "[OprStatusOutboxReplay] outbox_id={} 재처리 실패(retry={}/{}), 다음 실행에서 다시 시도합니다.",
                entry.outboxId, entry.retryCount, properties.oprStatusOutboxMaxRetries, ex,
            )
        }
        outboxRepository.save(entry)
        return gaveUp
    }
}
