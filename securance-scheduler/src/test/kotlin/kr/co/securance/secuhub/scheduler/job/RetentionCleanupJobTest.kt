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
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

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
 * [RetentionCleanupJob] 검증 — D5 데이터 보관 정책(2026-08-12, `docs/작업일지.md` 참고).
 *
 * 배치 삭제가 "더 지울 게 없을 때(반환값 < 배치 크기) 멈추는지"와 "배치 상한에 도달하면 남은 건
 * 다음 실행으로 넘기는지"를 핵심으로 검증한다 — 둘 다 틀리면 각각 무한 루프(전자) 또는 스케줄러
 * 스레드 장시간 점유(후자)로 이어진다.
 */
class RetentionCleanupJobTest {

    private fun buildJob(
        dataReceiveRepository: DataReceiveRepository = mock(DataReceiveRepository::class.java),
        dataReceiveAnalysisRepository: DataReceiveAnalysisRepository = mock(DataReceiveAnalysisRepository::class.java),
        gateLogRepository: GateLogRepository = mock(GateLogRepository::class.java),
        dataReceiveAckRepository: DataReceiveAckRepository = mock(DataReceiveAckRepository::class.java),
        dataReceiveFailRepository: DataReceiveFailRepository = mock(DataReceiveFailRepository::class.java),
        oprStatusRepository: OprStatusRepository = mock(OprStatusRepository::class.java),
        oprStatusOutboxRepository: OprStatusOutboxRepository = mock(OprStatusOutboxRepository::class.java),
        dataSendRepository: DataSendRepository = mock(DataSendRepository::class.java),
        properties: SchedulerProperties = SchedulerProperties(),
    ): RetentionCleanupJob {
        val job = RetentionCleanupJob()
        injectField(job, "dataReceiveRepository", dataReceiveRepository)
        injectField(job, "dataReceiveAnalysisRepository", dataReceiveAnalysisRepository)
        injectField(job, "gateLogRepository", gateLogRepository)
        injectField(job, "dataReceiveAckRepository", dataReceiveAckRepository)
        injectField(job, "dataReceiveFailRepository", dataReceiveFailRepository)
        injectField(job, "oprStatusRepository", oprStatusRepository)
        injectField(job, "oprStatusOutboxRepository", oprStatusOutboxRepository)
        injectField(job, "dataSendRepository", dataSendRepository)
        injectField(job, "properties", properties)
        return job
    }

    private val context = fakeJobExecutionContext()

    @Test
    fun `retention-enabled가 false면 아무 삭제도 하지 않는다`() {
        val dataReceiveRepository = mock(DataReceiveRepository::class.java)
        val job = buildJob(dataReceiveRepository = dataReceiveRepository, properties = SchedulerProperties(retentionEnabled = false))

        job.execute(context)

        verify(dataReceiveRepository, never()).deleteBatchOlderThan(anyString(), anyInt())
    }

    @Test
    fun `삭제 건수가 배치 크기보다 작으면 한 번만 호출하고 멈춘다`() {
        val dataReceiveRepository = mock(DataReceiveRepository::class.java)
        `when`(dataReceiveRepository.deleteBatchOlderThan(anyString(), anyInt())).thenReturn(3)
        val properties = SchedulerProperties(retentionBatchSize = 100, retentionMaxBatchesPerRun = 10)

        val job = buildJob(dataReceiveRepository = dataReceiveRepository, properties = properties)
        job.execute(context)

        verify(dataReceiveRepository, times(1)).deleteBatchOlderThan(anyString(), anyInt())
    }

    @Test
    fun `삭제 건수가 계속 배치 크기와 같으면 배치 상한만큼 반복하고 멈춘다`() {
        val dataReceiveRepository = mock(DataReceiveRepository::class.java)
        `when`(dataReceiveRepository.deleteBatchOlderThan(anyString(), anyInt())).thenReturn(50)
        val properties = SchedulerProperties(retentionBatchSize = 50, retentionMaxBatchesPerRun = 4)

        val job = buildJob(dataReceiveRepository = dataReceiveRepository, properties = properties)
        job.execute(context)

        verify(dataReceiveRepository, times(4)).deleteBatchOlderThan(anyString(), anyInt())
    }

    @Test
    fun `여덟 테이블을 모두 정리한다`() {
        // 코드 리뷰 지적 D-3 회귀 테스트: 최초 도입 시 tb_data_rcv/tb_data_rcv_anal/tb_gate_log
        // 세 테이블만 대상이었는데, tb_data_rcv_ack/tb_data_rcv_fail/tb_opr_status/
        // tb_opr_status_outbox/tb_data_snd가 정리 대상에서 빠져 무기한 증가하고 있었다.
        val dataReceiveRepository = mock(DataReceiveRepository::class.java)
        val dataReceiveAnalysisRepository = mock(DataReceiveAnalysisRepository::class.java)
        val gateLogRepository = mock(GateLogRepository::class.java)
        val dataReceiveAckRepository = mock(DataReceiveAckRepository::class.java)
        val dataReceiveFailRepository = mock(DataReceiveFailRepository::class.java)
        val oprStatusRepository = mock(OprStatusRepository::class.java)
        val oprStatusOutboxRepository = mock(OprStatusOutboxRepository::class.java)
        val dataSendRepository = mock(DataSendRepository::class.java)
        `when`(dataReceiveRepository.deleteBatchOlderThan(anyString(), anyInt())).thenReturn(0)
        `when`(dataReceiveAnalysisRepository.deleteBatchOlderThan(anyString(), anyInt())).thenReturn(0)
        `when`(gateLogRepository.deleteBatchOlderThan(anyKt<LocalDateTime>(), anyInt())).thenReturn(0)
        `when`(dataReceiveAckRepository.deleteBatchOlderThan(anyString(), anyInt())).thenReturn(0)
        `when`(dataReceiveFailRepository.deleteBatchOlderThan(anyString(), anyInt())).thenReturn(0)
        `when`(oprStatusRepository.deleteBatchOlderThan(anyString(), anyInt())).thenReturn(0)
        `when`(oprStatusOutboxRepository.deleteProcessedOlderThan(anyKt<LocalDateTime>(), anyInt())).thenReturn(0)
        `when`(dataSendRepository.deleteBatchOlderThan(anyString(), anyInt())).thenReturn(0)

        val job = buildJob(
            dataReceiveRepository = dataReceiveRepository,
            dataReceiveAnalysisRepository = dataReceiveAnalysisRepository,
            gateLogRepository = gateLogRepository,
            dataReceiveAckRepository = dataReceiveAckRepository,
            dataReceiveFailRepository = dataReceiveFailRepository,
            oprStatusRepository = oprStatusRepository,
            oprStatusOutboxRepository = oprStatusOutboxRepository,
            dataSendRepository = dataSendRepository,
        )
        job.execute(context)

        verify(dataReceiveRepository, times(1)).deleteBatchOlderThan(anyString(), anyInt())
        verify(dataReceiveAnalysisRepository, times(1)).deleteBatchOlderThan(anyString(), anyInt())
        verify(gateLogRepository, times(1))
            .deleteBatchOlderThan(anyKt<LocalDateTime>(), anyInt())
        verify(dataReceiveAckRepository, times(1)).deleteBatchOlderThan(anyString(), anyInt())
        verify(dataReceiveFailRepository, times(1)).deleteBatchOlderThan(anyString(), anyInt())
        verify(oprStatusRepository, times(1)).deleteBatchOlderThan(anyString(), anyInt())
        verify(oprStatusOutboxRepository, times(1))
            .deleteProcessedOlderThan(anyKt<LocalDateTime>(), anyInt())
        verify(dataSendRepository, times(1)).deleteBatchOlderThan(anyString(), anyInt())
    }

    @Test
    fun `기본 보관 기간은 365일이다`() {
        assertEquals(365L, SchedulerProperties().retentionDays)
    }
}
