package kr.co.securance.secuhub.server.db

import kotlinx.coroutines.Dispatchers
import kr.co.securance.secuhub.domain.entity.OprStatus
import kr.co.securance.secuhub.domain.entity.OprStatusId
import kr.co.securance.secuhub.domain.repository.GateLaneInfo
import kr.co.securance.secuhub.domain.repository.OprStatusRepository
import kr.co.securance.secuhub.protocol.GateStatusAnalyzer
import kr.co.securance.secuhub.protocol.SpeedFlapGateProtocolCodec
import kr.co.securance.secuhub.protocol.SpeedGateProtocolConstants
import kr.co.securance.secuhub.server.connection.GateConnectionActor
import kr.co.securance.secuhub.server.connection.GateConnectionState
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.timeout
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import reactor.netty.Connection
import reactor.netty.NettyOutbound
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Optional
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Kotlin non-null 파라미터에 [Mockito.any]를 직접 쓰면 NPE가 나는 문제를 우회하는 표준 헬퍼.
 * ([kr.co.securance.secuhub.web.dashboard.DashboardServiceTest]와 동일한 패턴)
 */
private fun <T> anyKt(): T {
    Mockito.any<T>()
    @Suppress("UNCHECKED_CAST")
    return null as T
}

/**
 * [OprStatusPersister] 검증 — 레거시 저장 프로시저 `usp_process_status`(2026-08-12 B6 조사,
 * `92_DB_Script/20260805/securance_gate/usp_process_status.sql`)의 delta 계산 규칙을
 * 그대로 재현하는지 확인한다. [DirectGateControlServiceTest]와 동일하게 실제
 * [GateDbWriteQueue](shardCount=1)를 쓰고 `Mockito.timeout`으로 비동기 완료를 기다린다.
 */
class OprStatusPersisterTest {

    private val offsets = GateStatusAnalyzer.StatusOffset
    private val minuteFormat = DateTimeFormatter.ofPattern("yyyyMMddHHmm")

    /** [GateStatusAnalyzerTest]와 동일한 패킷 합성 헬퍼. */
    private fun statusPacket(vararg laneBlocks: ByteArray): ByteArray {
        val header = ByteArray(SpeedGateProtocolConstants.HEADER_LENGTH)
        header[SpeedGateProtocolConstants.HeaderOffset.STX] = SpeedGateProtocolConstants.STX
        header[SpeedGateProtocolConstants.HeaderOffset.OBJECT_CODE] = SpeedGateProtocolConstants.ObjectCode.GATE_STATUS

        val info = ByteArray(SpeedGateProtocolConstants.DATA_INFO_LENGTH)
        info[SpeedGateProtocolConstants.DATA_INFO_LENGTH - 1] = laneBlocks.size.toByte()

        val body = header + info + laneBlocks.reduce { acc, bytes -> acc + bytes }
        return body + ByteArray(SpeedGateProtocolConstants.TAIL_LENGTH)
    }

    private fun laneBlock(laneNo: Int, total: Long, door: Long, inCount: Long): ByteArray =
        ByteArray(SpeedGateProtocolConstants.STATUS_DATA_LENGTH).apply {
            this[offsets.LANE_NUMBER] = laneNo.toByte()
            this[offsets.GATE_TYPE] = 1
            putU32(offsets.TOTAL_COUNT, total)
            putU32(offsets.MOTOR_COUNT, door) // = 레거시 SP의 "Door Count" 바이트 위치
            putU32(offsets.MASTER_IN_COUNT, inCount) // = 레거시 SP의 "In Count" 바이트 위치
        }

    private fun ByteArray.putU32(offset: Int, value: Long) {
        this[offset] = ((value shr 24) and 0xFF).toByte()
        this[offset + 1] = ((value shr 16) and 0xFF).toByte()
        this[offset + 2] = ((value shr 8) and 0xFF).toByte()
        this[offset + 3] = (value and 0xFF).toByte()
    }

    private fun newState(dtlIp: String, dtlId: Long = 42L): GateConnectionState =
        GateConnectionState(
            dtlIp = dtlIp,
            gateTypeCode = 1,
            codec = SpeedFlapGateProtocolCodec(),
            connection = mock(Connection::class.java),
            outbound = mock(NettyOutbound::class.java),
            actor = GateConnectionActor(dtlIp, Dispatchers.Default, queueCapacity = 100),
            laneInfo = listOf(
                GateLaneInfo(locId = 7, grpId = 3, dtlId = dtlId, dtlLaneNo = 1, dtlType = 1, analysisYn = true),
            ),
        )

    @Test
    fun `PREV 레코드가 있으면 그 값을 기준으로 delta를 계산해 신규 분 버킷을 INSERT한다`() {
        val repository = mock(OprStatusRepository::class.java)
        `when`(repository.findById(anyKt())).thenReturn(Optional.empty())
        val prev = OprStatus(
            id = OprStatusId(oprDate = "202608120959", oprSeq = 1, dtlIp = "192.168.0.20", dtlLaneNo = 1),
            totalCount = 40L, doorTotal = 5L, inTotal = 20L, outTotal = 8L,
        )
        `when`(
            repository.findLatestBefore(anyLong(), anyString(), anyInt(), anyString(), anyString(), anyKt()),
        ).thenReturn(listOf(prev))

        val persister = OprStatusPersister(GateDbWriteQueue(shardCount = 1), repository)
        val state = newState("192.168.0.20")
        val packet = statusPacket(laneBlock(laneNo = 1, total = 100, door = 10, inCount = 60))

        persister.persistOprStatus(state, packet)

        val captor = ArgumentCaptor.forClass(OprStatus::class.java)
        verify(repository, timeout(5_000)).save(captor.capture())
        val saved = captor.value

        assertEquals(60, saved.userCount) // 100 - 40
        assertEquals(100L, saved.totalCount)
        assertEquals(40L, saved.beforeTotal)
        assertEquals(5, saved.doorCount) // 10 - 5
        assertEquals(10L, saved.doorTotal)
        assertEquals(5L, saved.doorBefore)
        assertEquals(40, saved.inCount) // 60 - 20
        assertEquals(60L, saved.inTotal)
        assertEquals(20L, saved.inBefore)
        assertEquals(20, saved.outCount) // (100-40) - (60-20)
        assertEquals(8L, saved.outBefore) // PREV의 outTotal
        assertEquals(28L, saved.outTotal) // 8(before) + 20(delta) — SP 원문에 없는 secuhub 자체 누적값
        assertEquals(42L, saved.dtlId)
        assertEquals(7L, saved.locId)
        assertEquals(3L, saved.grpId)
        assertEquals("Y", saved.useYn)
        assertEquals("SR-1400", saved.gateType)
    }

    @Test
    fun `같은 분에 재수신되면 INSERT 시점의 before 값 기준으로 재계산해 UPDATE한다`() {
        val dateKey = LocalDateTime.now().format(minuteFormat)
        val existing = OprStatus(
            id = OprStatusId(oprDate = dateKey, oprSeq = 1, dtlIp = "192.168.0.21", dtlLaneNo = 1),
            totalCount = 100L, beforeTotal = 40L,
            doorTotal = 10L, doorBefore = 5L,
            inTotal = 60L, inBefore = 20L,
            outTotal = 40L, outBefore = 8L,
        )
        val repository = mock(OprStatusRepository::class.java)
        `when`(repository.findById(anyKt())).thenReturn(Optional.of(existing))

        val persister = OprStatusPersister(GateDbWriteQueue(shardCount = 1), repository)
        val state = newState("192.168.0.21")
        // 같은 분 안에 더 많이 통행한 재수신 패킷 — 누적치가 더 늘어났다.
        val packet = statusPacket(laneBlock(laneNo = 1, total = 150, door = 12, inCount = 90))

        persister.persistOprStatus(state, packet)

        val captor = ArgumentCaptor.forClass(OprStatus::class.java)
        verify(repository, timeout(5_000)).save(captor.capture())
        val saved = captor.value

        assertEquals(110, saved.userCount) // 150 - 40(before, 최초 INSERT 시점 값 그대로)
        assertEquals(150L, saved.totalCount)
        assertEquals(40L, saved.beforeTotal) // before 값 자체는 재수신에도 변하지 않는다
        assertEquals(7, saved.doorCount) // 12 - 5
        assertEquals(70, saved.inCount) // 90 - 20
        assertEquals(40, saved.outCount) // 110 - 70
        assertEquals(8L, saved.outBefore) // INSERT 시점 값 그대로(재수신에도 변하지 않는다)
        assertEquals(48L, saved.outTotal) // 8(before) + 40(재계산된 delta)
        // PREV 조회는 UPDATE 분기에서 아예 일어나지 않는다(SP 원문과 동일).
        verify(repository, Mockito.never())
            .findLatestBefore(anyLong(), anyString(), anyInt(), anyString(), anyString(), anyKt())
    }

    @Test
    fun `레인 번호 0(사용하지 않는 슬롯)은 건너뛴다`() {
        val repository = mock(OprStatusRepository::class.java)
        val persister = OprStatusPersister(GateDbWriteQueue(shardCount = 1), repository)
        val state = newState("192.168.0.22")
        val packet = statusPacket(laneBlock(laneNo = 0, total = 100, door = 10, inCount = 60))

        persister.persistOprStatus(state, packet)

        // 큐가 비동기라 "호출되지 않음"을 즉시 단언할 수는 없으므로, 짧게 대기해도 save가 없는지 확인.
        Thread.sleep(200)
        verifyNoInteractions(repository)
    }
}
