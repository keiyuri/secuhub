package kr.co.securance.secuhub.server.control

import kotlinx.coroutines.Dispatchers
import kr.co.securance.secuhub.common.util.HexCodec
import kr.co.securance.secuhub.domain.entity.DataSend
import kr.co.securance.secuhub.domain.repository.DataSendRepository
import kr.co.securance.secuhub.domain.repository.GateDetailRepository
import kr.co.securance.secuhub.domain.repository.GateLaneInfo
import kr.co.securance.secuhub.domain.repository.NetStateRepository
import kr.co.securance.secuhub.protocol.SpeedFlapGateProtocolCodec
import kr.co.securance.secuhub.protocol.SpeedGateControlCommand
import kr.co.securance.secuhub.protocol.SpeedGatePacketCodec
import kr.co.securance.secuhub.server.config.ServerModeConfig
import kr.co.securance.secuhub.server.connection.GateConnectionActor
import kr.co.securance.secuhub.server.connection.GateConnectionRegistryImpl
import kr.co.securance.secuhub.server.connection.GateConnectionState
import kr.co.securance.secuhub.server.db.GateDbWriteQueue
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.springframework.data.domain.Pageable
import org.springframework.orm.ObjectOptimisticLockingFailureException
import reactor.netty.Connection
import reactor.netty.NettyOutbound
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [GateControlDispatcher] 검증(2차 스프린트 1번 항목 — SendControlJob 정교화).
 *
 * 레거시 `ClsQuartzJobSendControl`은 소켓/DB/Quartz가 한 클래스에 엉켜 단위 테스트가 불가능했고,
 * 그 결과 중복 전송·명령 유실 버그가 실장비에서만 드러났다. 여기서는 시계를 주입해
 * 쿨다운/ACK 타임아웃/재시도 한도를 결정적으로 검증한다.
 */
class GateControlDispatcherTest {

    private val codec = SpeedFlapGateProtocolCodec()
    private val states = mutableListOf<GateConnectionState>()

    /** 테스트가 시간을 임의로 진행시킬 수 있는 시계. */
    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneId.systemDefault()
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = now
        fun advance(duration: Duration) {
            now = now.plus(duration)
        }
    }

    /** 소켓 write를 가로채 기록하는 레지스트리 대역. */
    private class RecordingRegistry : GateConnectionRegistryImpl(
        mock(NetStateRepository::class.java),
        GateDbWriteQueue(shardCount = 1),
        mock(GateDetailRepository::class.java),
        ServerModeConfig(),
    ) {
        val sentPackets = mutableListOf<ByteArray>()
        var acceptSend = true

        override fun sendRaw(state: GateConnectionState, packet: ByteArray): Boolean {
            if (!acceptSend) return false
            sentPackets += packet
            return true
        }
    }

    private fun connect(registry: RecordingRegistry, dtlIp: String): GateConnectionState {
        val state = GateConnectionState(
            dtlIp = dtlIp,
            gateTypeCode = 1,
            codec = codec,
            connection = mock(Connection::class.java),
            outbound = mock(NettyOutbound::class.java),
            actor = GateConnectionActor(dtlIp, Dispatchers.Default, queueCapacity = 100),
            laneInfo = listOf(
                GateLaneInfo(locId = 7, grpId = 3, dtlId = 42, dtlLaneNo = 1, dtlType = 1, analysisYn = true),
            ),
        )
        registry.register(state)
        states += state
        return state
    }

    @AfterTest
    fun tearDown() {
        states.forEach { it.actor.close() }
        states.clear()
    }

    /** 대기 중인 제어 명령 1건. `snd_id`는 테스트가 직접 지정한다. */
    private fun pendingCommand(
        sndId: Long = 1,
        dtlIp: String = "192.168.0.10",
        command: SpeedGateControlCommand = SpeedGateControlCommand.OPEN,
    ): DataSend {
        val packet = SpeedGatePacketCodec.buildControlCommand(1, command)
        return DataSend(
            sndId = sndId,
            sndDate = "20260810120000",
            dtlIp = dtlIp,
            dtlLaneNo = 1,
            sndUser = "operator1",
            sndTypeCd = command.legacyCode,
            sndRaw = HexCodec.toHex(packet),
        )
    }

    /**
     * `tb_data_snd` 대역. 실제 엔티티 인스턴스를 그대로 보관하므로, 디스패처가 변경한
     * `snd_yn`/`chk_yn`을 테스트가 같은 객체로 확인할 수 있다.
     */
    private fun repositoryOf(vararg rows: DataSend): DataSendRepository {
        val store = rows.toMutableList()
        val repository = mock(DataSendRepository::class.java)
        org.mockito.Mockito.`when`(repository.findPendingCommands(anyNonNull<Pageable>()))
            .thenAnswer { store.filter { row -> row.isPending } }
        org.mockito.Mockito.`when`(repository.findAwaitingAck(anyNonNull<Pageable>()))
            .thenAnswer { store.filter { row -> row.isAwaitingAck } }
        org.mockito.Mockito.`when`(repository.save(anyNonNull<DataSend>()))
            .thenAnswer { it.arguments[0] }
        stubClaim(repository, store)
        return repository
    }

    /**
     * [DataSendRepository.claimForSend]/[DataSendRepository.releaseClaim]를 실제 DB의 원자적
     * UPDATE와 동등하게 흉내낸다 — `store`에 든 실제 엔티티를 직접 변형해 조건부 UPDATE의
     * "성공 시 1행 영향, 조건 불일치 시 0행"을 재현한다(P1 수정 회귀 테스트용).
     */
    private fun stubClaim(repository: DataSendRepository, store: List<DataSend>) {
        org.mockito.Mockito.`when`(
            repository.claimForSend(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                anyString(),
            ),
        ).thenAnswer { invocation ->
            val id = invocation.arguments[0] as Long
            val version = invocation.arguments[1] as Long
            val server = invocation.arguments[2] as String
            val row = store.find { it.sndId == id }
            if (row != null && row.sndYn == DataSend.NO && row.version == version) {
                row.sndYn = DataSend.YES
                row.sndServer = server
                row.version += 1
                1
            } else {
                0
            }
        }
        org.mockito.Mockito.`when`(repository.releaseClaim(org.mockito.ArgumentMatchers.anyLong()))
            .thenAnswer { invocation ->
                val id = invocation.arguments[0] as Long
                val row = store.find { it.sndId == id }
                if (row != null) {
                    row.sndYn = DataSend.NO
                    row.version += 1
                    1
                } else {
                    0
                }
            }
    }

    /**
     * Kotlin의 non-null 파라미터에 Mockito 매처를 쓰기 위한 우회.
     * `Mockito.any()`는 null을 반환하는데 Kotlin이 호출부에 null 검사를 넣어 NPE가 난다.
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T> anyNonNull(): T {
        org.mockito.Mockito.any<T>()
        return null as T
    }

    private fun dispatcherOf(
        registry: RecordingRegistry,
        repository: DataSendRepository,
        clock: MutableClock,
        resolution: GateFaultResolutionService = mock(GateFaultResolutionService::class.java),
        properties: ControlProperties = ControlProperties(
            resendGuardSeconds = 5,
            ackTimeoutSeconds = 10,
            maxSendAttempts = 3,
        ),
    ) = GateControlDispatcher(registry, repository, resolution, properties, clock)

    // ── 전송 ─────────────────────────────────────────────────────────

    @Test
    fun `대기 중인 명령을 전송하고 snd_yn을 Y로 바꾼다`() {
        val registry = RecordingRegistry()
        connect(registry, "192.168.0.10")
        val row = pendingCommand()
        val clock = MutableClock(Instant.parse("2026-08-10T03:00:00Z"))
        val dispatcher = dispatcherOf(registry, repositoryOf(row), clock)

        val summary = dispatcher.dispatchPending()

        assertEquals(1, summary.sent)
        assertEquals(1, registry.sentPackets.size)
        assertEquals(DataSend.YES, row.sndYn)
        // 아직 장비 ACK를 못 받았으므로 확인 플래그는 N으로 남아야 한다.
        assertEquals(DataSend.NO, row.chkYn)
        assertTrue(row.isAwaitingAck)
    }

    @Test
    fun `장비가 접속되어 있지 않으면 대기 상태를 유지한다`() {
        // 명령을 성공 처리해 버리면 장비 재접속 후에도 영영 전송되지 않는다(레거시 명령 유실 패턴).
        val registry = RecordingRegistry()
        val row = pendingCommand()
        val clock = MutableClock(Instant.parse("2026-08-10T03:00:00Z"))
        val dispatcher = dispatcherOf(registry, repositoryOf(row), clock)

        val summary = dispatcher.dispatchPending()

        assertEquals(0, summary.sent)
        assertEquals(1, summary.skipped)
        assertTrue(registry.sentPackets.isEmpty())
        assertTrue(row.isPending, "미접속 장비의 명령은 대기 상태로 남아야 한다")
    }

    @Test
    fun `액터 대기열이 포화되어 전송이 거부되면 대기 상태를 유지한다`() {
        val registry = RecordingRegistry().apply { acceptSend = false }
        connect(registry, "192.168.0.10")
        val row = pendingCommand()
        val clock = MutableClock(Instant.parse("2026-08-10T03:00:00Z"))
        val dispatcher = dispatcherOf(registry, repositoryOf(row), clock)

        dispatcher.dispatchPending()

        assertTrue(row.isPending, "전송되지 않은 명령을 성공으로 기록하면 명령이 영구 유실된다")
    }

    @Test
    fun `원시 데이터가 깨진 명령은 즉시 실패로 확정한다`() {
        val registry = RecordingRegistry()
        connect(registry, "192.168.0.10")
        val row = pendingCommand().apply { sndRaw = "ZZ-NOT-HEX" }
        val clock = MutableClock(Instant.parse("2026-08-10T03:00:00Z"))
        val dispatcher = dispatcherOf(registry, repositoryOf(row), clock)

        dispatcher.dispatchPending()

        // 몇 번을 재시도해도 성공하지 않으므로 재시도 대상으로 남기지 않는다.
        assertEquals(DataSend.FAILED, row.chkYn)
        assertTrue(registry.sentPackets.isEmpty())
    }

    // ── 쿨다운(중복 물리 전송 방지) ──────────────────────────────────

    @Test
    fun `쿨다운 이내에는 같은 명령을 다시 전송하지 않는다`() {
        // 레거시 RESEND_GUARD 대응 — DB 갱신 지연 중 게이트가 반복 동작하는 사고를 막는다.
        val registry = RecordingRegistry()
        connect(registry, "192.168.0.10")
        val row = pendingCommand()
        val clock = MutableClock(Instant.parse("2026-08-10T03:00:00Z"))
        val dispatcher = dispatcherOf(registry, repositoryOf(row), clock)

        dispatcher.dispatchPending()
        // 외부 요인으로 DB 갱신이 되돌아가 다시 대기 상태가 된 상황을 흉내낸다.
        row.sndYn = DataSend.NO
        clock.advance(Duration.ofSeconds(3))
        val second = dispatcher.dispatchPending()

        assertEquals(0, second.sent)
        assertEquals(1, registry.sentPackets.size, "쿨다운 이내에는 물리 전송이 한 번만 나가야 한다")
    }

    // ── ACK 확인 ─────────────────────────────────────────────────────

    @Test
    fun `장비 ACK가 도착하면 chk_yn을 Y로 확정한다`() {
        val registry = RecordingRegistry()
        connect(registry, "192.168.0.10")
        val row = pendingCommand()
        val clock = MutableClock(Instant.parse("2026-08-10T03:00:00Z"))
        val dispatcher = dispatcherOf(registry, repositoryOf(row), clock)

        dispatcher.dispatchPending()
        dispatcher.onDeviceControlAck("192.168.0.10", 1)
        val summary = dispatcher.dispatchPending()

        assertEquals(1, summary.confirmed)
        assertEquals(DataSend.YES, row.chkYn)
    }

    @Test
    fun `리셋 명령은 장비 ACK를 확인한 뒤에만 장애를 해제한다`() {
        // 레거시는 전송 직후 해제해, 장비가 명령을 받지 못해도 화면에서 장애가 사라졌다.
        val registry = RecordingRegistry()
        connect(registry, "192.168.0.10")
        val row = pendingCommand(command = SpeedGateControlCommand.RESET_MOTOR)
        val clock = MutableClock(Instant.parse("2026-08-10T03:00:00Z"))
        val resolution = mock(GateFaultResolutionService::class.java)
        val dispatcher = dispatcherOf(registry, repositoryOf(row), clock, resolution)

        dispatcher.dispatchPending()
        verify(resolution, never()).resolveByResetCommand(anyString(), anyInt(), anyNonNull(), anyString())

        dispatcher.onDeviceControlAck("192.168.0.10", 1)
        dispatcher.dispatchPending()

        verify(resolution).resolveByResetCommand(
            "192.168.0.10",
            1,
            SpeedGateControlCommand.RESET_MOTOR,
            "operator1",
        )
    }

    @Test
    fun `리셋이 아닌 명령은 ACK를 받아도 장애를 해제하지 않는다`() {
        val registry = RecordingRegistry()
        connect(registry, "192.168.0.10")
        val row = pendingCommand(command = SpeedGateControlCommand.OPEN)
        val clock = MutableClock(Instant.parse("2026-08-10T03:00:00Z"))
        val resolution = mock(GateFaultResolutionService::class.java)
        val dispatcher = dispatcherOf(registry, repositoryOf(row), clock, resolution)

        dispatcher.dispatchPending()
        dispatcher.onDeviceControlAck("192.168.0.10", 1)
        dispatcher.dispatchPending()

        verify(resolution, never()).resolveByResetCommand(anyString(), anyInt(), anyNonNull(), anyString())
    }

    // ── ACK 타임아웃 / 재전송 / 실패 확정 ────────────────────────────

    @Test
    fun `ACK 타임아웃이 지나면 재전송한다`() {
        val registry = RecordingRegistry()
        connect(registry, "192.168.0.10")
        val row = pendingCommand()
        val clock = MutableClock(Instant.parse("2026-08-10T03:00:00Z"))
        val dispatcher = dispatcherOf(registry, repositoryOf(row), clock)

        dispatcher.dispatchPending()
        assertEquals(1, registry.sentPackets.size)

        // 타임아웃(10초) + 쿨다운(5초)을 모두 넘긴다.
        clock.advance(Duration.ofSeconds(11))
        val summary = dispatcher.dispatchPending()

        assertEquals(1, summary.retried)
        assertEquals(2, registry.sentPackets.size, "ACK를 못 받았으면 다시 보내야 한다")
        assertEquals(DataSend.YES, row.sndYn)
    }

    @Test
    fun `최대 시도 횟수를 넘기면 실패로 확정하고 더 이상 보내지 않는다`() {
        val registry = RecordingRegistry()
        connect(registry, "192.168.0.10")
        val row = pendingCommand()
        val clock = MutableClock(Instant.parse("2026-08-10T03:00:00Z"))
        val dispatcher = dispatcherOf(registry, repositoryOf(row), clock)

        // 최초 전송 + 재전송 2회 = maxSendAttempts(3)
        repeat(3) {
            dispatcher.dispatchPending()
            clock.advance(Duration.ofSeconds(11))
        }
        assertEquals(3, registry.sentPackets.size)

        val summary = dispatcher.dispatchPending()

        assertEquals(1, summary.failed)
        assertEquals(DataSend.FAILED, row.chkYn)
        assertEquals(3, registry.sentPackets.size, "실패 확정 후에는 더 이상 전송하지 않는다")
    }

    @Test
    fun `타임아웃 직전에는 재전송하지 않는다`() {
        val registry = RecordingRegistry()
        connect(registry, "192.168.0.10")
        val row = pendingCommand()
        val clock = MutableClock(Instant.parse("2026-08-10T03:00:00Z"))
        val dispatcher = dispatcherOf(registry, repositoryOf(row), clock)

        dispatcher.dispatchPending()
        clock.advance(Duration.ofSeconds(9)) // ackTimeoutSeconds(10) 미만
        val summary = dispatcher.dispatchPending()

        assertEquals(0, summary.retried)
        assertEquals(1, registry.sentPackets.size)
    }

    @Test
    fun `ACK가 온 IP의 명령만 확정한다`() {
        val registry = RecordingRegistry()
        connect(registry, "192.168.0.10")
        connect(registry, "192.168.0.11")
        val first = pendingCommand(sndId = 1, dtlIp = "192.168.0.10")
        val second = pendingCommand(sndId = 2, dtlIp = "192.168.0.11")
        val clock = MutableClock(Instant.parse("2026-08-10T03:00:00Z"))
        val dispatcher = dispatcherOf(registry, repositoryOf(first, second), clock)

        dispatcher.dispatchPending()
        dispatcher.onDeviceControlAck("192.168.0.10", 1)
        dispatcher.dispatchPending()

        assertEquals(DataSend.YES, first.chkYn)
        assertEquals(DataSend.NO, second.chkYn, "다른 게이트의 ACK로 확정되면 안 된다")
    }

    @Test
    fun `같은 IP라도 ACK가 온 레인의 명령만 확정한다`() {
        // snd_id 기반 매칭이 프로토콜상 불가능해 (IP, 레인) 단위로 좁힌 것의 회귀 테스트.
        // 이전에는 IP만으로 매칭해 다중 레인 장비에서 다른 레인의 명령까지 함께 확인 처리됐다.
        val registry = RecordingRegistry()
        connect(registry, "192.168.0.10")
        val lane1 = pendingCommand(sndId = 1, dtlIp = "192.168.0.10").also { it.dtlLaneNo = 1 }
        val lane2 = pendingCommand(sndId = 2, dtlIp = "192.168.0.10").also { it.dtlLaneNo = 2 }
        val clock = MutableClock(Instant.parse("2026-08-10T03:00:00Z"))
        val dispatcher = dispatcherOf(registry, repositoryOf(lane1, lane2), clock)

        dispatcher.dispatchPending()
        dispatcher.onDeviceControlAck("192.168.0.10", 1)
        dispatcher.dispatchPending()

        assertEquals(DataSend.YES, lane1.chkYn)
        assertEquals(DataSend.NO, lane2.chkYn, "같은 IP라도 다른 레인의 ACK로 확정되면 안 된다")
    }

    @Test
    fun `전송 전에 도착한 오래된 ACK는 이후 새 명령을 허위로 확정하지 않는다`() {
        // Codex 적대적 리뷰 지적: purgeStaleAcks가 미매칭 ACK를 ackTimeoutSeconds*2까지
        // ackedLanes에 남겨두는데, 그 사이 같은 (IP, 레인)에 새 명령이 전송되면 이 오래된
        // ACK가 실제 장비 응답 없이 새 명령을 즉시 확정해버릴 수 있었다(리셋 명령이면 아직
        // 존재하는 장애까지 허위로 해제됨). ACK 시각이 명령의 실제 전송 시각보다 앞서면
        // 확정하지 않아야 한다.
        val registry = RecordingRegistry()
        connect(registry, "192.168.0.10")
        val clock = MutableClock(Instant.parse("2026-08-10T03:00:00Z"))
        val repository = mock(DataSendRepository::class.java)
        val store = mutableListOf<DataSend>()
        org.mockito.Mockito.`when`(repository.findPendingCommands(anyNonNull<Pageable>()))
            .thenAnswer { store.filter { it.isPending } }
        org.mockito.Mockito.`when`(repository.findAwaitingAck(anyNonNull<Pageable>()))
            .thenAnswer { store.filter { it.isAwaitingAck } }
        org.mockito.Mockito.`when`(repository.save(anyNonNull<DataSend>()))
            .thenAnswer { it.arguments[0] }
        stubClaim(repository, store)
        val dispatcher = dispatcherOf(registry, repository, clock)

        // 1) 이전 명령의 뒤늦은/스퓨리어스 ACK가, 새 명령이 대기열에 들어오기도 전에 도착한다.
        dispatcher.onDeviceControlAck("192.168.0.10", 1)
        dispatcher.dispatchPending() // 매칭할 대기 명령이 없어 ackedLanes에 그대로 남는다.

        // 2) 그 뒤(오래된 ACK가 purgeStaleAcks 유예기간 안에 있는 동안) 진짜 새 명령이 전송된다.
        // ACK 시각과 전송 시각이 같으면 선후관계를 구분할 수 없으므로 시간을 흘려보낸다.
        clock.advance(Duration.ofSeconds(1))
        val row = pendingCommand(command = SpeedGateControlCommand.RESET_MOTOR)
        store += row
        val sendCycle = dispatcher.dispatchPending()
        assertEquals(1, sendCycle.sent)
        assertTrue(row.isAwaitingAck)

        // 3) 새 명령의 실제 ACK 없이도, 남아있던 오래된 ACK로 확정되면 안 된다.
        val staleCycle = dispatcher.dispatchPending()
        assertEquals(0, staleCycle.confirmed, "전송 전에 도착한 ACK로 새 명령이 확정되면 안 된다")
        assertTrue(row.isAwaitingAck, "장비의 실제 ACK를 받기 전까지는 확정되면 안 된다")

        // 4) 전송 이후에 도착한 진짜 ACK는 정상적으로 확정된다.
        clock.advance(Duration.ofSeconds(1))
        dispatcher.onDeviceControlAck("192.168.0.10", 1)
        val confirmedCycle = dispatcher.dispatchPending()
        assertEquals(1, confirmedCycle.confirmed)
        assertEquals(DataSend.YES, row.chkYn)
    }

    // ── 레인당 동시 in-flight 명령 1건 제한(Codex 어드버서리얼 리뷰) ──

    @Test
    fun `같은 레인에 명령이 2건 대기 중이면 첫 명령의 ACK가 확정될 때까지 두번째는 보내지 않는다`() {
        // ACK 프레임에 명령 식별자가 없어, 같은 레인에 2건이 동시에 ACK 대기 상태면 ACK 1건이
        // 아직 수행되지 않은 명령까지 함께 확정해 버릴 수 있다(리셋이면 장애 기록 오해제로 이어짐).
        // 레인당 in-flight를 1건으로 제한해 이 상황 자체가 생기지 않게 한다.
        val registry = RecordingRegistry()
        connect(registry, "192.168.0.10")
        val first = pendingCommand(sndId = 1, command = SpeedGateControlCommand.OPEN)
        val second = pendingCommand(sndId = 2, command = SpeedGateControlCommand.RESET_MOTOR)
        val clock = MutableClock(Instant.parse("2026-08-10T03:00:00Z"))
        val dispatcher = dispatcherOf(registry, repositoryOf(first, second), clock)

        val firstCycle = dispatcher.dispatchPending()
        assertEquals(1, firstCycle.sent, "같은 레인의 두번째 명령은 함께 보내면 안 된다")
        assertEquals(1, registry.sentPackets.size)
        assertTrue(first.isAwaitingAck)
        assertTrue(second.isPending, "첫 명령이 ACK 확정되기 전까지는 대기 상태로 남아야 한다")

        // 첫 명령의 ACK가 도착해 확정되면, 그제서야 두번째 명령이 나간다.
        dispatcher.onDeviceControlAck("192.168.0.10", 1)
        val secondCycle = dispatcher.dispatchPending()

        assertEquals(DataSend.YES, first.chkYn)
        assertEquals(1, secondCycle.sent, "첫 명령 확정 후에는 두번째 명령을 보내야 한다")
        assertEquals(2, registry.sentPackets.size)
        assertTrue(second.isAwaitingAck)
    }

    // ── 다중 인스턴스 낙관적 잠금(3차 스프린트) ─────────────────────

    @Test
    fun `다른 인스턴스가 먼저 확정한 행은 낙관적 잠금 예외를 삼키고 다음 항목을 계속 처리한다`() {
        val registry = RecordingRegistry()
        connect(registry, "192.168.0.10")
        connect(registry, "192.168.0.11")
        val racedRow = pendingCommand(sndId = 1, dtlIp = "192.168.0.10")
        val healthyRow = pendingCommand(sndId = 2, dtlIp = "192.168.0.11")
        val clock = MutableClock(Instant.parse("2026-08-10T03:00:00Z"))

        val repository = mock(DataSendRepository::class.java)
        val store = mutableListOf(racedRow, healthyRow)
        org.mockito.Mockito.`when`(repository.findPendingCommands(anyNonNull<Pageable>()))
            .thenAnswer { store.filter { it.isPending } }
        org.mockito.Mockito.`when`(repository.findAwaitingAck(anyNonNull<Pageable>()))
            .thenAnswer { store.filter { it.isAwaitingAck } }
        stubClaim(repository, store)
        // racedRow 확인 저장만 다른 인스턴스가 먼저 처리한 것처럼 낙관적 잠금 예외를 던진다.
        org.mockito.Mockito.`when`(repository.save(anyNonNull<DataSend>())).thenAnswer { invocation ->
            val row = invocation.arguments[0] as DataSend
            if (row.sndId == racedRow.sndId && row.chkYn == DataSend.YES) {
                throw ObjectOptimisticLockingFailureException(DataSend::class.java, row.sndId!!)
            }
            row
        }

        val dispatcher = dispatcherOf(registry, repository, clock)
        dispatcher.dispatchPending()
        dispatcher.onDeviceControlAck("192.168.0.10", 1)
        dispatcher.onDeviceControlAck("192.168.0.11", 1)

        val summary = dispatcher.dispatchPending()

        assertEquals(1, summary.confirmed, "경합에서 진 행은 세지 않되, 나머지 한 건은 정상 확정돼야 한다")
        assertEquals(DataSend.YES, healthyRow.chkYn)
    }
}
