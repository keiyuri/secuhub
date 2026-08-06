package kr.co.securance.secuhub.scheduler.job

import kr.co.securance.secuhub.common.util.HexCodec
import kr.co.securance.secuhub.domain.entity.DataSend
import kr.co.securance.secuhub.domain.repository.DataReceiveAnalysisRepository
import kr.co.securance.secuhub.domain.repository.DataSendRepository
import kr.co.securance.secuhub.scheduler.config.SchedulerProperties
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.quartz.JobExecutionContext
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// mockito-kotlin을 의존성에 추가하지 않았으므로, eq()/any()가 인터페이스 메서드의 Kotlin non-null
// 파라미터에 null을 넘겨도 안전하도록(추상 메서드는 Kotlin의 null-check 바이트코드가 없음) 최소한의
// 매처 헬퍼를 직접 둔다(mockito-kotlin의 동일 트릭).
private fun <T> anyMatcher(): T {
    ArgumentMatchers.any<T>()
    @Suppress("UNCHECKED_CAST")
    return null as T
}

private fun <T> eqMatcher(value: T): T {
    ArgumentMatchers.eq(value)
    return value
}

class SendControlJobTest {

    private fun buildRow(
        sndId: Long = 1L,
        dtlIp: String = "192.168.0.10",
        dtlLaneNo: Int = 1,
        sndRaw: String? = "0200",
        sndTypeCd: String? = null,
        sndUser: String? = null,
    ) = DataSend(
        sndId = sndId,
        sndDate = "20260806120000",
        sndYn = "N",
        chkYn = "N",
        dtlIp = dtlIp,
        dtlLaneNo = dtlLaneNo,
        sndUser = sndUser,
        sndTypeCd = sndTypeCd,
        sndRaw = sndRaw,
    )

    private fun buildJob(
        registry: FakeGateConnectionRegistry,
        dataSendRepository: DataSendRepository,
        dataReceiveAnalysisRepository: DataReceiveAnalysisRepository = mock(DataReceiveAnalysisRepository::class.java),
        properties: SchedulerProperties = SchedulerProperties(),
    ): SendControlJob {
        val job = SendControlJob()
        injectField(job, "registry", registry)
        injectField(job, "dataSendRepository", dataSendRepository)
        injectField(job, "dataReceiveAnalysisRepository", dataReceiveAnalysisRepository)
        injectField(job, "properties", properties)
        return job
    }

    private val context: JobExecutionContext = fakeJobExecutionContext()

    @Test
    fun `정상 전송에 성공하면 sndYn을 Y로 갱신한다`() {
        val row = buildRow()
        val registry = FakeGateConnectionRegistry(sendResult = true)
        val repo = mock(DataSendRepository::class.java)
        `when`(repo.findBySndYnAndChkYnOrderBySndId("N", "N")).thenReturn(listOf(row))

        val job = buildJob(registry, repo)
        job.execute(context)

        assertEquals("Y", row.sndYn)
        assertEquals(1, registry.sentCalls.size)
        val (ip, lane, packet) = registry.sentCalls.first()
        assertEquals(row.dtlIp, ip)
        assertEquals(row.dtlLaneNo, lane)
        assertEquals(HexCodec.fromHex("0200").toList(), packet.toList())
        verify(repo, times(1)).save(row)
    }

    @Test
    fun `전송 실패 시 DB를 그대로 두어 재시도 대상으로 남긴다`() {
        val row = buildRow()
        val registry = FakeGateConnectionRegistry(sendResult = false)
        val repo = mock(DataSendRepository::class.java)
        `when`(repo.findBySndYnAndChkYnOrderBySndId("N", "N")).thenReturn(listOf(row))

        val job = buildJob(registry, repo)
        job.execute(context)

        assertEquals("N", row.sndYn)
        verify(repo, times(0)).save(row)
    }

    @Test
    fun `쿨다운 이내에는 같은 snd_id로 물리적 재전송을 하지 않는다`() {
        // 실패를 반복시켜 행이 계속 pending으로 남아 재조회되는 상황을 시뮬레이션한다.
        val row = buildRow(sndId = 999L)
        val registry = FakeGateConnectionRegistry(sendResult = false)
        val repo = mock(DataSendRepository::class.java)
        `when`(repo.findBySndYnAndChkYnOrderBySndId("N", "N")).thenReturn(listOf(row))

        val job1 = buildJob(registry, repo)
        job1.execute(context)
        val job2 = buildJob(registry, repo)
        job2.execute(context) // 같은 JVM 내 companion object 쿨다운 맵을 공유하므로 바로 이어서 실행해도 억제되어야 한다.

        assertEquals(1, registry.sentCalls.size)
    }

    @Test
    fun `snd_raw가 유효하지 않은 hex이면 chkYn을 Y로 표시하고 재시도 대상에서 제외한다`() {
        val row = buildRow(sndId = 2L, sndRaw = "ZZ")
        val registry = FakeGateConnectionRegistry(sendResult = true)
        val repo = mock(DataSendRepository::class.java)
        `when`(repo.findBySndYnAndChkYnOrderBySndId("N", "N")).thenReturn(listOf(row))

        val job = buildJob(registry, repo)
        job.execute(context)

        assertEquals("N", row.sndYn)
        assertEquals("Y", row.chkYn)
        assertTrue(registry.sentCalls.isEmpty())
        verify(repo, times(1)).save(row)
    }

    @Test
    fun `snd_raw가 비어 있으면 chkYn을 Y로 표시하고 스킵한다`() {
        val row = buildRow(sndId = 3L, sndRaw = null)
        val registry = FakeGateConnectionRegistry(sendResult = true)
        val repo = mock(DataSendRepository::class.java)
        `when`(repo.findBySndYnAndChkYnOrderBySndId("N", "N")).thenReturn(listOf(row))

        val job = buildJob(registry, repo)
        job.execute(context)

        assertEquals("Y", row.chkYn)
        assertTrue(registry.sentCalls.isEmpty())
    }

    @Test
    fun `RESET으로 시작하는 snd_type_cd 전송 성공 시 미해결 오류를 resolve 처리한다`() {
        val row = buildRow(sndId = 4L, sndTypeCd = "RESET_MOTOR", sndUser = "operator1")
        val registry = FakeGateConnectionRegistry(sendResult = true)
        val repo = mock(DataSendRepository::class.java)
        `when`(repo.findBySndYnAndChkYnOrderBySndId("N", "N")).thenReturn(listOf(row))
        val analRepo = mock(DataReceiveAnalysisRepository::class.java)
        `when`(analRepo.resolveUnresolvedErrors(eqMatcher(row.dtlIp), eqMatcher("operator1"), anyMatcher<LocalDateTime>()))
            .thenReturn(2)

        val job = buildJob(registry, repo, analRepo)
        job.execute(context)

        assertEquals("Y", row.sndYn)
        verify(analRepo, times(1))
            .resolveUnresolvedErrors(eqMatcher(row.dtlIp), eqMatcher("operator1"), anyMatcher())
    }

    @Test
    fun `RESET이 아닌 snd_type_cd는 resolve 처리를 호출하지 않는다`() {
        val row = buildRow(sndId = 5L, sndTypeCd = "OPEN")
        val registry = FakeGateConnectionRegistry(sendResult = true)
        val repo = mock(DataSendRepository::class.java)
        `when`(repo.findBySndYnAndChkYnOrderBySndId("N", "N")).thenReturn(listOf(row))
        val analRepo = mock(DataReceiveAnalysisRepository::class.java)

        val job = buildJob(registry, repo, analRepo)
        job.execute(context)

        assertEquals("Y", row.sndYn)
        verify(analRepo, times(0))
            .resolveUnresolvedErrors(eqMatcher(row.dtlIp), anyMatcher(), anyMatcher())
    }

    @Test
    fun `RESET으로 시작하지만 첫 토큰이 정확히 일치하지 않는 snd_type_cd는 resolve 처리하지 않는다`() {
        // 레거시는 snd_data_tp.Split('_')[0] == "RESET" 완전 일치로 판정한다. "RESETUP"처럼
        // RESET으로 시작만 하는 값을 startsWith로 오판하지 않는지 검증한다.
        val row = buildRow(sndId = 6L, sndTypeCd = "RESETUP_MOTOR")
        val registry = FakeGateConnectionRegistry(sendResult = true)
        val repo = mock(DataSendRepository::class.java)
        `when`(repo.findBySndYnAndChkYnOrderBySndId("N", "N")).thenReturn(listOf(row))
        val analRepo = mock(DataReceiveAnalysisRepository::class.java)

        val job = buildJob(registry, repo, analRepo)
        job.execute(context)

        assertEquals("Y", row.sndYn)
        verify(analRepo, times(0))
            .resolveUnresolvedErrors(eqMatcher(row.dtlIp), anyMatcher(), anyMatcher())
    }
}
