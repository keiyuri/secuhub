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
        `when`(repo.findEligiblePending(eqMatcher("N"), eqMatcher("N"), anyMatcher(), anyMatcher())).thenReturn(listOf(row))

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
    fun `전송 실패 시 sndYn은 N으로 두되 next_attempt_at을 미래로 저장해 재시도 대상으로 남긴다`() {
        // [Codex 적대적 리뷰 회귀 테스트] next_attempt_at이 실제로 갱신·저장되지 않으면
        // findEligiblePending 조회에서 이 행이 계속 선택되어 뒤쪽 행을 영구히 가리게 된다.
        val row = buildRow()
        val registry = FakeGateConnectionRegistry(sendResult = false)
        val repo = mock(DataSendRepository::class.java)
        `when`(repo.findEligiblePending(eqMatcher("N"), eqMatcher("N"), anyMatcher(), anyMatcher())).thenReturn(listOf(row))
        val before = LocalDateTime.now()

        val job = buildJob(registry, repo)
        job.execute(context)

        assertEquals("N", row.sndYn)
        assertTrue(row.nextAttemptAt != null && row.nextAttemptAt!!.isAfter(before))
        verify(repo, times(1)).save(row)
    }

    @Test
    fun `선행 행이 계속 실패해도 next_attempt_at으로 배제되면 다음 폴링에서 후속 행이 처리된다(큐 기아 방지)`() {
        // Codex 적대적 리뷰 지적: 실패한 큐 앞쪽 행이 매 폴링마다 다시 뽑혀 뒤쪽 정상 행을 영구히
        // 가리는 헤드 오브 라인 차단을 재현/방지 검증한다. 리포지토리의 실제 필터링은
        // DataSendRepository(@Query)의 몫이므로, 여기서는 "1번째 폴링에서 배제된 행이 2번째
        // 폴링 조회 결과에 없으면" job이 새 행을 정상 처리하는지만 검증한다.
        val stuckRow = buildRow(sndId = 10L, dtlIp = "192.168.0.20")
        val freshRow = buildRow(sndId = 11L, dtlIp = "192.168.0.21")
        val registry = FakeGateConnectionRegistry(sendResult = true)
        val repo = mock(DataSendRepository::class.java)
        `when`(repo.findEligiblePending(eqMatcher("N"), eqMatcher("N"), anyMatcher(), anyMatcher()))
            .thenReturn(listOf(stuckRow), listOf(freshRow))

        val job1 = buildJob(registry, repo)
        job1.execute(context)
        val job2 = buildJob(registry, repo)
        job2.execute(context)

        assertEquals(2, registry.sentCalls.size)
        assertTrue(registry.sentCalls.any { it.first == "192.168.0.20" })
        assertTrue(registry.sentCalls.any { it.first == "192.168.0.21" })
    }

    @Test
    fun `snd_raw가 유효하지 않은 hex이면 chkYn을 Y로 표시하고 재시도 대상에서 제외한다`() {
        val row = buildRow(sndId = 2L, sndRaw = "ZZ")
        val registry = FakeGateConnectionRegistry(sendResult = true)
        val repo = mock(DataSendRepository::class.java)
        `when`(repo.findEligiblePending(eqMatcher("N"), eqMatcher("N"), anyMatcher(), anyMatcher())).thenReturn(listOf(row))

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
        `when`(repo.findEligiblePending(eqMatcher("N"), eqMatcher("N"), anyMatcher(), anyMatcher())).thenReturn(listOf(row))

        val job = buildJob(registry, repo)
        job.execute(context)

        assertEquals("Y", row.chkYn)
        assertTrue(registry.sentCalls.isEmpty())
    }

    @Test
    fun `RESET_MOTOR 전송 성공 시 모터 오류 resolve만 호출한다`() {
        val row = buildRow(sndId = 4L, sndTypeCd = "RESET_MOTOR", sndUser = "operator1")
        val registry = FakeGateConnectionRegistry(sendResult = true)
        val repo = mock(DataSendRepository::class.java)
        `when`(repo.findEligiblePending(eqMatcher("N"), eqMatcher("N"), anyMatcher(), anyMatcher())).thenReturn(listOf(row))
        val analRepo = mock(DataReceiveAnalysisRepository::class.java)
        `when`(
            analRepo.resolveMotorErrors(
                eqMatcher(row.dtlIp), eqMatcher("operator1"), anyMatcher<LocalDateTime>(), anyMatcher(), anyMatcher(),
            ),
        ).thenReturn(2)

        val job = buildJob(registry, repo, analRepo)
        job.execute(context)

        assertEquals("Y", row.sndYn)
        verify(analRepo, times(1))
            .resolveMotorErrors(eqMatcher(row.dtlIp), eqMatcher("operator1"), anyMatcher(), anyMatcher(), anyMatcher())
        verify(analRepo, times(0))
            .resolveSensorErrors(anyMatcher(), anyMatcher(), anyMatcher(), anyMatcher(), anyMatcher())
        verify(analRepo, times(0))
            .resolveGateErrors(anyMatcher(), anyMatcher(), anyMatcher(), anyMatcher(), anyMatcher())
    }

    @Test
    fun `RESET_OPER 전송 성공 시 센서 오류 resolve만 호출한다`() {
        val row = buildRow(sndId = 41L, sndTypeCd = "RESET_OPER", sndUser = "operator1")
        val registry = FakeGateConnectionRegistry(sendResult = true)
        val repo = mock(DataSendRepository::class.java)
        `when`(repo.findEligiblePending(eqMatcher("N"), eqMatcher("N"), anyMatcher(), anyMatcher())).thenReturn(listOf(row))
        val analRepo = mock(DataReceiveAnalysisRepository::class.java)

        val job = buildJob(registry, repo, analRepo)
        job.execute(context)

        assertEquals("Y", row.sndYn)
        verify(analRepo, times(1))
            .resolveSensorErrors(eqMatcher(row.dtlIp), anyMatcher(), anyMatcher(), anyMatcher(), anyMatcher())
        verify(analRepo, times(0))
            .resolveMotorErrors(anyMatcher(), anyMatcher(), anyMatcher(), anyMatcher(), anyMatcher())
    }

    @Test
    fun `RESET_GATE 전송 성공 시 게이트 오류 resolve만 호출한다`() {
        val row = buildRow(sndId = 42L, sndTypeCd = "RESET_GATE", sndUser = "operator1")
        val registry = FakeGateConnectionRegistry(sendResult = true)
        val repo = mock(DataSendRepository::class.java)
        `when`(repo.findEligiblePending(eqMatcher("N"), eqMatcher("N"), anyMatcher(), anyMatcher())).thenReturn(listOf(row))
        val analRepo = mock(DataReceiveAnalysisRepository::class.java)

        val job = buildJob(registry, repo, analRepo)
        job.execute(context)

        assertEquals("Y", row.sndYn)
        verify(analRepo, times(1))
            .resolveGateErrors(eqMatcher(row.dtlIp), anyMatcher(), anyMatcher(), anyMatcher(), anyMatcher())
        verify(analRepo, times(0))
            .resolveMotorErrors(anyMatcher(), anyMatcher(), anyMatcher(), anyMatcher(), anyMatcher())
    }

    @Test
    fun `인식할 수 없는 RESET 서브타입은 어떤 resolve도 호출하지 않는다`() {
        // [Codex 적대적 리뷰 회귀 테스트] 서브타입을 판별할 수 없다고 해서 "전부 resolve"로
        // 폭넓게 처리하면 무관한 오류까지 지워질 수 있다 — 레거시 switch문처럼 아무 것도 갱신하지
        // 않는 안전한 기본값을 유지해야 한다.
        val row = buildRow(sndId = 43L, sndTypeCd = "RESET_UNKNOWN")
        val registry = FakeGateConnectionRegistry(sendResult = true)
        val repo = mock(DataSendRepository::class.java)
        `when`(repo.findEligiblePending(eqMatcher("N"), eqMatcher("N"), anyMatcher(), anyMatcher())).thenReturn(listOf(row))
        val analRepo = mock(DataReceiveAnalysisRepository::class.java)

        val job = buildJob(registry, repo, analRepo)
        job.execute(context)

        assertEquals("Y", row.sndYn)
        verify(analRepo, times(0))
            .resolveMotorErrors(anyMatcher(), anyMatcher(), anyMatcher(), anyMatcher(), anyMatcher())
        verify(analRepo, times(0))
            .resolveSensorErrors(anyMatcher(), anyMatcher(), anyMatcher(), anyMatcher(), anyMatcher())
        verify(analRepo, times(0))
            .resolveGateErrors(anyMatcher(), anyMatcher(), anyMatcher(), anyMatcher(), anyMatcher())
    }

    @Test
    fun `RESET이 아닌 snd_type_cd는 resolve 처리를 호출하지 않는다`() {
        val row = buildRow(sndId = 5L, sndTypeCd = "OPEN")
        val registry = FakeGateConnectionRegistry(sendResult = true)
        val repo = mock(DataSendRepository::class.java)
        `when`(repo.findEligiblePending(eqMatcher("N"), eqMatcher("N"), anyMatcher(), anyMatcher())).thenReturn(listOf(row))
        val analRepo = mock(DataReceiveAnalysisRepository::class.java)

        val job = buildJob(registry, repo, analRepo)
        job.execute(context)

        assertEquals("Y", row.sndYn)
        verify(analRepo, times(0))
            .resolveMotorErrors(anyMatcher(), anyMatcher(), anyMatcher(), anyMatcher(), anyMatcher())
    }

    @Test
    fun `RESET으로 시작하지만 첫 토큰이 정확히 일치하지 않는 snd_type_cd는 resolve 처리하지 않는다`() {
        // 레거시는 snd_data_tp.Split('_')[0] == "RESET" 완전 일치로 판정한다. "RESETUP"처럼
        // RESET으로 시작만 하는 값을 startsWith로 오판하지 않는지 검증한다.
        val row = buildRow(sndId = 6L, sndTypeCd = "RESETUP_MOTOR")
        val registry = FakeGateConnectionRegistry(sendResult = true)
        val repo = mock(DataSendRepository::class.java)
        `when`(repo.findEligiblePending(eqMatcher("N"), eqMatcher("N"), anyMatcher(), anyMatcher())).thenReturn(listOf(row))
        val analRepo = mock(DataReceiveAnalysisRepository::class.java)

        val job = buildJob(registry, repo, analRepo)
        job.execute(context)

        assertEquals("Y", row.sndYn)
        verify(analRepo, times(0))
            .resolveMotorErrors(anyMatcher(), anyMatcher(), anyMatcher(), anyMatcher(), anyMatcher())
    }
}
