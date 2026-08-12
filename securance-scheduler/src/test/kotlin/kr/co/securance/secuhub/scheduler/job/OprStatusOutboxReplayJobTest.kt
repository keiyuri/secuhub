package kr.co.securance.secuhub.scheduler.job

import kr.co.securance.secuhub.domain.entity.OprStatusOutbox
import kr.co.securance.secuhub.domain.repository.OprStatusOutboxRepository
import kr.co.securance.secuhub.scheduler.config.SchedulerProperties
import kr.co.securance.secuhub.server.db.OprStatusPersister
import org.mockito.Mockito
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.data.domain.PageRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Kotlin non-null 파라미터에 [Mockito.any]를 직접 쓰면 NPE가 나는 문제를 우회하는 표준 헬퍼.
 * ([kr.co.securance.secuhub.server.db.OprStatusPersisterTest]와 동일한 패턴)
 */
private fun <T> anyKt(): T {
    Mockito.any<T>()
    @Suppress("UNCHECKED_CAST")
    return null as T
}

/**
 * [OprStatusOutboxReplayJob] 검증 — 큐 드롭 durable 재작성(2026-08-12, `docs/작업일지.md` 참고).
 */
class OprStatusOutboxReplayJobTest {

    private fun entry(id: Long, retryCount: Int = 0) = OprStatusOutbox(
        dtlIp = "192.168.0.10",
        dtlLaneNo = 1,
        dtlId = 42L,
        dtlType = 1,
        locId = 7L,
        grpId = 3L,
        oprDate = "202608120959",
        sinceDate = "202608110000",
        currTotal = 100L,
        currDoor = 10L,
        currIn = 60L,
        gateTypeRaw = 1,
        userModeRaw = 1,
        securityModeRaw = 1,
        inoutTime = 5,
        reason = "DROPPED_OR_FINAL_FAILURE",
        retryCount = retryCount,
    ).apply { outboxId = id }

    private fun buildJob(
        outboxRepository: OprStatusOutboxRepository = mock(OprStatusOutboxRepository::class.java),
        oprStatusPersister: OprStatusPersister = mock(OprStatusPersister::class.java),
        properties: SchedulerProperties = SchedulerProperties(),
    ): OprStatusOutboxReplayJob {
        val job = OprStatusOutboxReplayJob()
        injectField(job, "outboxRepository", outboxRepository)
        injectField(job, "oprStatusPersister", oprStatusPersister)
        injectField(job, "properties", properties)
        return job
    }

    private val context = fakeJobExecutionContext()

    @Test
    fun `opr-status-outbox-replay-enabled가 false면 아무것도 하지 않는다`() {
        val outboxRepository = mock(OprStatusOutboxRepository::class.java)
        val job = buildJob(outboxRepository = outboxRepository, properties = SchedulerProperties(oprStatusOutboxReplayEnabled = false))

        job.execute(context)

        verify(outboxRepository, never()).findByProcessedFalseOrderByOutboxIdAsc(anyKt())
    }

    @Test
    fun `미처리 행이 없으면 아무것도 하지 않는다`() {
        val outboxRepository = mock(OprStatusOutboxRepository::class.java)
        `when`(outboxRepository.findByProcessedFalseOrderByOutboxIdAsc(anyKt())).thenReturn(emptyList())
        val persister = mock(OprStatusPersister::class.java)

        val job = buildJob(outboxRepository = outboxRepository, oprStatusPersister = persister)
        job.execute(context)

        verify(persister, never()).replayOutboxEntry(anyKt())
    }

    @Test
    fun `재처리에 성공하면 processed를 true로 표시한다`() {
        val outboxRepository = mock(OprStatusOutboxRepository::class.java)
        val target = entry(id = 1L)
        `when`(outboxRepository.findByProcessedFalseOrderByOutboxIdAsc(anyKt())).thenReturn(listOf(target))
        val persister = mock(OprStatusPersister::class.java)

        val job = buildJob(outboxRepository = outboxRepository, oprStatusPersister = persister)
        job.execute(context)

        verify(persister, times(1)).replayOutboxEntry(target)
        assertTrue(target.processed)
        verify(outboxRepository, times(1)).save(target)
    }

    @Test
    fun `재처리가 실패하면 retryCount만 올리고 processed는 그대로 둔다`() {
        val outboxRepository = mock(OprStatusOutboxRepository::class.java)
        val target = entry(id = 2L, retryCount = 0)
        `when`(outboxRepository.findByProcessedFalseOrderByOutboxIdAsc(anyKt())).thenReturn(listOf(target))
        val persister = mock(OprStatusPersister::class.java)
        doThrow(RuntimeException("DB 장애")).`when`(persister).replayOutboxEntry(target)

        val job = buildJob(
            outboxRepository = outboxRepository,
            oprStatusPersister = persister,
            properties = SchedulerProperties(oprStatusOutboxMaxRetries = 10),
        )
        job.execute(context)

        assertEquals(1, target.retryCount)
        assertFalse(target.processed)
        verify(outboxRepository, times(1)).save(target)
    }

    @Test
    fun `재시도 상한에 도달하면 더 이상 처리되지 않은 채로 남긴다`() {
        val outboxRepository = mock(OprStatusOutboxRepository::class.java)
        val target = entry(id = 3L, retryCount = 9) // 이번 실패로 10회째 — 상한 도달
        `when`(outboxRepository.findByProcessedFalseOrderByOutboxIdAsc(anyKt())).thenReturn(listOf(target))
        val persister = mock(OprStatusPersister::class.java)
        doThrow(RuntimeException("DB 장애")).`when`(persister).replayOutboxEntry(target)

        val job = buildJob(
            outboxRepository = outboxRepository,
            oprStatusPersister = persister,
            properties = SchedulerProperties(oprStatusOutboxMaxRetries = 10),
        )
        job.execute(context)

        assertEquals(10, target.retryCount)
        assertFalse(target.processed) // 포기해도 processed=true로 표시하지 않는다 — 조회에 계속 잡혀야 한다.
    }

    @Test
    fun `한 번에 조회하는 배치 크기는 설정을 따른다`() {
        val outboxRepository = mock(OprStatusOutboxRepository::class.java)
        `when`(outboxRepository.findByProcessedFalseOrderByOutboxIdAsc(anyKt())).thenReturn(emptyList())
        val properties = SchedulerProperties(oprStatusOutboxBatchSize = 77)

        val job = buildJob(outboxRepository = outboxRepository, properties = properties)
        job.execute(context)

        verify(outboxRepository).findByProcessedFalseOrderByOutboxIdAsc(PageRequest.of(0, 77))
    }
}
