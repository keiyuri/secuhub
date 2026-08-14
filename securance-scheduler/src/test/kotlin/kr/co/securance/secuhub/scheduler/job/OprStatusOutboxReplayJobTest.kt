package kr.co.securance.secuhub.scheduler.job

import kr.co.securance.secuhub.domain.entity.OprStatusOutbox
import kr.co.securance.secuhub.domain.repository.OprStatusOutboxRepository
import kr.co.securance.secuhub.domain.repository.OprStatusRepository
import kr.co.securance.secuhub.scheduler.config.SchedulerProperties
import kr.co.securance.secuhub.server.db.GateDbWriteQueue
import kr.co.securance.secuhub.server.db.OprStatusPersister
import org.mockito.Mockito
import org.mockito.Mockito.anyInt
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

/** 위 [anyKt]와 동일한 이유로 필요한 `eq()` 우회 — 값 자체가 non-null이라 그대로 반환해도 안전하다. */
private fun <T> eqKt(value: T): T {
    Mockito.eq(value)
    return value
}

/**
 * [OprStatusPersister.replayOutboxEntry]는 이제 `GateDbWriteQueue`를 거치는 suspend 함수라, 순수
 * Mockito로 suspend 반환값을 스텁하는 대신(이 코드베이스의 기존 관례 — `RecordingRegistry` 패턴,
 * [kr.co.securance.secuhub.server.control.DirectGateControlServiceTest] 참고) 실제 클래스를 얇게
 * 상속해 원하는 결과를 직접 반환하는 대역을 쓴다.
 */
private class FakeOprStatusPersister(
    var result: (OprStatusOutbox) -> Boolean = { true },
    var recorded: MutableList<OprStatusOutbox> = mutableListOf(),
) : OprStatusPersister(
    dbWriteQueue = GateDbWriteQueue(shardCount = 1),
    oprStatusRepository = mock(OprStatusRepository::class.java),
    oprStatusOutboxRepository = mock(OprStatusOutboxRepository::class.java),
) {
    override suspend fun replayOutboxEntry(entry: OprStatusOutbox): Boolean {
        recorded += entry
        return result(entry)
    }
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
        oprStatusPersister: OprStatusPersister = FakeOprStatusPersister(),
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

        verify(outboxRepository, never()).findByProcessedFalseAndRetryCountLessThanOrderByOutboxIdAsc(anyInt(), anyKt())
    }

    @Test
    fun `미처리 행이 없으면 아무것도 하지 않는다`() {
        val outboxRepository = mock(OprStatusOutboxRepository::class.java)
        `when`(outboxRepository.findByProcessedFalseAndRetryCountLessThanOrderByOutboxIdAsc(anyInt(), anyKt())).thenReturn(emptyList())
        val persister = FakeOprStatusPersister()

        val job = buildJob(outboxRepository = outboxRepository, oprStatusPersister = persister)
        job.execute(context)

        assertTrue(persister.recorded.isEmpty())
    }

    @Test
    fun `재처리에 성공하면 processed를 true로 표시한다`() {
        val outboxRepository = mock(OprStatusOutboxRepository::class.java)
        val target = entry(id = 1L)
        `when`(outboxRepository.findByProcessedFalseAndRetryCountLessThanOrderByOutboxIdAsc(anyInt(), anyKt())).thenReturn(listOf(target))
        val persister = FakeOprStatusPersister(result = { true })

        val job = buildJob(outboxRepository = outboxRepository, oprStatusPersister = persister)
        job.execute(context)

        assertEquals(listOf(target), persister.recorded)
        assertTrue(target.processed)
        verify(outboxRepository, times(1)).save(target)
    }

    @Test
    fun `재처리가 실패하면 retryCount만 올리고 processed는 그대로 둔다`() {
        val outboxRepository = mock(OprStatusOutboxRepository::class.java)
        val target = entry(id = 2L, retryCount = 0)
        `when`(outboxRepository.findByProcessedFalseAndRetryCountLessThanOrderByOutboxIdAsc(anyInt(), anyKt())).thenReturn(listOf(target))
        val persister = FakeOprStatusPersister(result = { throw RuntimeException("DB 장애") })

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
    fun `큐 드롭 또는 최종 실패로 재처리가 거부되면 retryCount만 올린다`() {
        val outboxRepository = mock(OprStatusOutboxRepository::class.java)
        val target = entry(id = 4L, retryCount = 0)
        `when`(outboxRepository.findByProcessedFalseAndRetryCountLessThanOrderByOutboxIdAsc(anyInt(), anyKt())).thenReturn(listOf(target))
        // 예외 없이 false만 반환하는 경로(GateDbWriteQueue 드롭/최종 실패)도 실패로 취급해야 한다.
        val persister = FakeOprStatusPersister(result = { false })

        val job = buildJob(
            outboxRepository = outboxRepository,
            oprStatusPersister = persister,
            properties = SchedulerProperties(oprStatusOutboxMaxRetries = 10),
        )
        job.execute(context)

        assertEquals(1, target.retryCount)
        assertFalse(target.processed)
    }

    @Test
    fun `재시도 상한에 도달하면 더 이상 처리되지 않은 채로 남긴다`() {
        val outboxRepository = mock(OprStatusOutboxRepository::class.java)
        val target = entry(id = 3L, retryCount = 9) // 이번 실패로 10회째 — 상한 도달
        `when`(outboxRepository.findByProcessedFalseAndRetryCountLessThanOrderByOutboxIdAsc(anyInt(), anyKt())).thenReturn(listOf(target))
        val persister = FakeOprStatusPersister(result = { throw RuntimeException("DB 장애") })

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
        `when`(outboxRepository.findByProcessedFalseAndRetryCountLessThanOrderByOutboxIdAsc(anyInt(), anyKt())).thenReturn(emptyList())
        val properties = SchedulerProperties(oprStatusOutboxBatchSize = 77)

        val job = buildJob(outboxRepository = outboxRepository, properties = properties)
        job.execute(context)

        verify(outboxRepository).findByProcessedFalseAndRetryCountLessThanOrderByOutboxIdAsc(anyInt(), eqKt(PageRequest.of(0, 77)))
    }

    @Test
    fun `조회 시 설정된 재시도 상한을 그대로 전달해 포기한 행을 다시 집지 않는다`() {
        // 회귀 대상: 예전에는 재시도 상한(gaveUp) 도달 행도 processed=false로 남는다는 이유만으로
        // 다음 실행에서 무한정 다시 조회돼 재시도됐다 — retryCount < maxRetries 조건이 쿼리에
        // 반영되는지를 직접 검증한다.
        val outboxRepository = mock(OprStatusOutboxRepository::class.java)
        `when`(outboxRepository.findByProcessedFalseAndRetryCountLessThanOrderByOutboxIdAsc(anyInt(), anyKt())).thenReturn(emptyList())
        val properties = SchedulerProperties(oprStatusOutboxMaxRetries = 5)

        val job = buildJob(outboxRepository = outboxRepository, properties = properties)
        job.execute(context)

        verify(outboxRepository).findByProcessedFalseAndRetryCountLessThanOrderByOutboxIdAsc(5, PageRequest.of(0, properties.oprStatusOutboxBatchSize))
    }
}
