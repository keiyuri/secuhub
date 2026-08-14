package kr.co.securance.secuhub.server.db

import kotlinx.coroutines.Dispatchers
import kr.co.securance.secuhub.domain.entity.DataReceiveAnalysis
import kr.co.securance.secuhub.domain.entity.GateDetail
import kr.co.securance.secuhub.domain.entity.GateGroup
import kr.co.securance.secuhub.domain.entity.GateLocation
import kr.co.securance.secuhub.domain.repository.DataReceiveAckRepository
import kr.co.securance.secuhub.domain.repository.DataReceiveAnalysisRepository
import kr.co.securance.secuhub.domain.repository.DataReceiveFailRepository
import kr.co.securance.secuhub.domain.repository.DataReceiveRepository
import kr.co.securance.secuhub.domain.repository.GateDetailRepository
import kr.co.securance.secuhub.domain.repository.GateLaneInfo
import kr.co.securance.secuhub.protocol.GateStatusAnalyzer
import kr.co.securance.secuhub.protocol.SpeedFlapGateProtocolCodec
import kr.co.securance.secuhub.protocol.SpeedGateProtocolConstants
import kr.co.securance.secuhub.server.connection.GateConnectionActor
import kr.co.securance.secuhub.server.connection.GateConnectionState
import kr.co.securance.secuhub.server.control.GateFaultResolutionService
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.timeout
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import reactor.netty.Connection
import reactor.netty.NettyOutbound
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [GatePacketPersister.persistStatusAnalysis] 검증(2026-08-14 "당일 전체 상태 데이터 저장" 기능 +
 * dtl_type 캐시 staleness 수정). [OprStatusPersisterTest]와 동일하게 실제 [GateDbWriteQueue]
 * (shardCount=1)를 쓰고 `Mockito.timeout`으로 비동기 완료를 기다린다.
 */
class GatePacketPersisterTest {

    private val offsets = GateStatusAnalyzer.StatusOffset

    private fun statusPacket(vararg laneBlocks: ByteArray): ByteArray {
        val header = ByteArray(SpeedGateProtocolConstants.HEADER_LENGTH)
        header[SpeedGateProtocolConstants.HeaderOffset.STX] = SpeedGateProtocolConstants.STX
        header[SpeedGateProtocolConstants.HeaderOffset.OBJECT_CODE] = SpeedGateProtocolConstants.ObjectCode.GATE_STATUS

        val info = ByteArray(SpeedGateProtocolConstants.DATA_INFO_LENGTH)
        info[SpeedGateProtocolConstants.DATA_INFO_LENGTH - 1] = laneBlocks.size.toByte()

        val body = header + info + laneBlocks.reduce { acc, bytes -> acc + bytes }
        return body + ByteArray(SpeedGateProtocolConstants.TAIL_LENGTH)
    }

    private fun laneBlock(laneNo: Int, errCheck: Int = GateStatusAnalyzer.ErrorCheck.NORMAL, totalCount: Long = 0): ByteArray =
        ByteArray(SpeedGateProtocolConstants.STATUS_DATA_LENGTH).apply {
            this[offsets.LANE_NUMBER] = laneNo.toByte()
            this[offsets.GATE_TYPE] = 1
            this[offsets.ERROR_CHECK] = errCheck.toByte()
            this[offsets.TOTAL_COUNT] = ((totalCount shr 24) and 0xFF).toByte()
            this[offsets.TOTAL_COUNT + 1] = ((totalCount shr 16) and 0xFF).toByte()
            this[offsets.TOTAL_COUNT + 2] = ((totalCount shr 8) and 0xFF).toByte()
            this[offsets.TOTAL_COUNT + 3] = (totalCount and 0xFF).toByte()
        }

    private fun newState(dtlIp: String, dtlType: Int = 1): GateConnectionState =
        GateConnectionState(
            dtlIp = dtlIp,
            gateTypeCode = 1,
            codec = SpeedFlapGateProtocolCodec(),
            connection = mock(Connection::class.java),
            outbound = mock(NettyOutbound::class.java),
            actor = GateConnectionActor(dtlIp, Dispatchers.Default, queueCapacity = 100),
            laneInfo = listOf(
                GateLaneInfo(locId = 1, grpId = 70, dtlId = 159, dtlLaneNo = 1, dtlType = dtlType, analysisYn = true, dtlName = "캐시된이름"),
            ),
        )

    private fun newPersister(
        analysisRepository: DataReceiveAnalysisRepository,
        gateDetailRepository: GateDetailRepository = mock(GateDetailRepository::class.java),
    ): GatePacketPersister = GatePacketPersister(
        GateDbWriteQueue(shardCount = 1),
        mock(DataReceiveRepository::class.java),
        mock(DataReceiveAckRepository::class.java),
        mock(DataReceiveFailRepository::class.java),
        analysisRepository,
        gateDetailRepository,
        mock(GateFaultResolutionService::class.java),
    )

    @Test
    fun `정상 상태가 처음 수신되면 새 행을 INSERT한다`() {
        val analysisRepository = mock(DataReceiveAnalysisRepository::class.java)
        `when`(analysisRepository.findTopByDtlIpAndDtlLaneNoOrderByAnalIdDesc(anyString(), anyInt())).thenReturn(null)
        val persister = newPersister(analysisRepository)
        val state = newState("192.168.0.205")

        persister.persistStatusAnalysis(state, statusPacket(laneBlock(laneNo = 1, totalCount = 100)))

        val captor = ArgumentCaptor.forClass(DataReceiveAnalysis::class.java)
        verify(analysisRepository, timeout(5_000)).save(captor.capture())
        assertEquals("NOR", captor.value.analTp)
        assertEquals("100", captor.value.descTotalCount)
    }

    @Test
    fun `직전과 분석 결과가 완전히 동일하면 새 행 대신 rcv_date만 갱신한다`() {
        val analysisRepository = mock(DataReceiveAnalysisRepository::class.java)
        `when`(analysisRepository.findTopByDtlIpAndDtlLaneNoOrderByAnalIdDesc(anyString(), anyInt())).thenReturn(null)
        val persister = newPersister(analysisRepository)
        val state = newState("192.168.0.205")
        val packet = statusPacket(laneBlock(laneNo = 1, totalCount = 100))

        // 1차 수신 — 새 행 INSERT.
        persister.persistStatusAnalysis(state, packet)
        val firstCaptor = ArgumentCaptor.forClass(DataReceiveAnalysis::class.java)
        verify(analysisRepository, timeout(5_000)).save(firstCaptor.capture())
        val firstSaved = firstCaptor.value
        val originalAnalDate = firstSaved.analDate

        // 2차 수신 — 방금 저장된 행이 "오늘 날짜 + 동일 데이터"로 조회되도록 스텁.
        `when`(analysisRepository.findTopByDtlIpAndDtlLaneNoOrderByAnalIdDesc(anyString(), anyInt())).thenReturn(firstSaved)
        persister.persistStatusAnalysis(state, packet)

        val allCaptor = ArgumentCaptor.forClass(DataReceiveAnalysis::class.java)
        verify(analysisRepository, timeout(5_000).times(2)).save(allCaptor.capture())
        // 두 번째 저장은 새 인스턴스가 아니라 기존 행(firstSaved) 그대로 재사용해야 한다(= 새 행 없음).
        assertSame(firstSaved, allCaptor.allValues[1])
        // Codex 리뷰(2026-08-14) P2 지적 회귀 방지 — 동일 데이터 반복 시 anal_date는 최초 기록
        // 시각을 그대로 유지해야 한다(요구사항: "수신일자만 갱신").
        assertEquals(originalAnalDate, allCaptor.allValues[1].analDate)
    }

    @Test
    fun `연결 캐시와 실 DB 식별정보가 달라도 동일 데이터가 반복되면 rcv_date만 갱신한다`() {
        // Codex 리뷰(2026-08-14) P1 지적 회귀 방지 — dtl_type 등 식별정보가 tb_gate_dtl 재조회로
        // 최신화된 뒤에도(=저장된 행의 식별정보와 커넥션 캐시가 어긋난 뒤에도), 동일 데이터가
        // 반복되면 식별정보 불일치 때문에 매번 새 행이 INSERT되면 안 된다.
        val analysisRepository = mock(DataReceiveAnalysisRepository::class.java)
        `when`(analysisRepository.findTopByDtlIpAndDtlLaneNoOrderByAnalIdDesc(anyString(), anyInt())).thenReturn(null)
        val gateDetailRepository = mock(GateDetailRepository::class.java)
        val location = GateLocation(locId = 77, locName = "새위치")
        val group = GateGroup(grpId = 88, location = location, grpName = "새그룹", gateTypeCode = 1)
        val liveDetail = GateDetail(
            dtlId = 159, location = location, group = group,
            dtlIp = "192.168.0.205", dtlLaneNo = 1, dtlType = 9, dtlName = "새이름",
        )
        `when`(gateDetailRepository.findByDtlIpAndDtlLaneNo("192.168.0.205", 1)).thenReturn(liveDetail)
        val persister = newPersister(analysisRepository, gateDetailRepository)
        val state = newState("192.168.0.205", dtlType = 1) // 캐시는 여전히 dtlType=1(라이브 값 9와 불일치)
        val packet = statusPacket(laneBlock(laneNo = 1, totalCount = 100))

        // 1차 수신 — 새 행 INSERT(라이브 조회로 dtlType=9 반영).
        persister.persistStatusAnalysis(state, packet)
        val firstCaptor = ArgumentCaptor.forClass(DataReceiveAnalysis::class.java)
        verify(analysisRepository, timeout(5_000)).save(firstCaptor.capture())
        val firstSaved = firstCaptor.value
        assertEquals(9, firstSaved.dtlType)

        // 2차 수신 — 동일 데이터. 저장된 행(dtlType=9)과 커넥션 캐시(dtlType=1)가 어긋나 있어도
        // 새 행이 아니라 기존 행 재사용(rcv_date만 갱신)이어야 한다.
        `when`(analysisRepository.findTopByDtlIpAndDtlLaneNoOrderByAnalIdDesc(anyString(), anyInt())).thenReturn(firstSaved)
        persister.persistStatusAnalysis(state, packet)

        val allCaptor = ArgumentCaptor.forClass(DataReceiveAnalysis::class.java)
        verify(analysisRepository, timeout(5_000).times(2)).save(allCaptor.capture())
        assertSame(firstSaved, allCaptor.allValues[1])
    }

    @Test
    fun `상태가 하루 종일 동일해도 날짜가 바뀌면 최신 식별정보로 다시 INSERT한다`() {
        // Codex 적대적 리뷰(2026-08-14) 지적 대응 — isSameContent가 식별정보를 비교하지 않게 되면서
        // (P1 수정) "상태가 전혀 안 바뀌면 식별정보가 무기한 정체되는 것 아니냐"는 지적을 받았다.
        // 실제로는 anal_date를 절대 갱신하지 않으므로(P2 수정) 자정을 넘기면 "오늘 날짜" 조건이
        // 깨져 무조건 한 번은 새 INSERT(+resolveLaneIdentity)가 일어난다 — 이 경계(worst-case
        // staleness ≤ 하루)를 여기서 고정한다.
        val analysisRepository = mock(DataReceiveAnalysisRepository::class.java)
        `when`(analysisRepository.findTopByDtlIpAndDtlLaneNoOrderByAnalIdDesc(anyString(), anyInt())).thenReturn(null)
        val gateDetailRepository = mock(GateDetailRepository::class.java)
        val location = GateLocation(locId = 77, locName = "새위치")
        val group = GateGroup(grpId = 88, location = location, grpName = "새그룹", gateTypeCode = 1)
        val liveDetail = GateDetail(
            dtlId = 159, location = location, group = group,
            dtlIp = "192.168.0.205", dtlLaneNo = 1, dtlType = 9, dtlName = "새이름",
        )
        `when`(gateDetailRepository.findByDtlIpAndDtlLaneNo("192.168.0.205", 1)).thenReturn(liveDetail)
        val persister = newPersister(analysisRepository, gateDetailRepository)
        val state = newState("192.168.0.205", dtlType = 1)
        val packet = statusPacket(laneBlock(laneNo = 1, totalCount = 100))

        // 1차 수신 — 새 행 INSERT.
        persister.persistStatusAnalysis(state, packet)
        val firstCaptor = ArgumentCaptor.forClass(DataReceiveAnalysis::class.java)
        verify(analysisRepository, timeout(5_000)).save(firstCaptor.capture())
        val firstSaved = firstCaptor.value

        // "어제" 저장된 행처럼 보이도록 anal_date를 하루 전으로 되돌린다(내용은 그대로 동일).
        firstSaved.analDate = "20260101" + firstSaved.analDate.substring(8)
        `when`(analysisRepository.findTopByDtlIpAndDtlLaneNoOrderByAnalIdDesc(anyString(), anyInt())).thenReturn(firstSaved)

        // 2차 수신 — 내용은 완전히 동일하지만 최신 행이 "오늘"이 아니므로 새로 INSERT되어야 한다.
        persister.persistStatusAnalysis(state, packet)

        val allCaptor = ArgumentCaptor.forClass(DataReceiveAnalysis::class.java)
        verify(analysisRepository, timeout(5_000).times(2)).save(allCaptor.capture())
        assertNotSame(firstSaved, allCaptor.allValues[1])
        assertEquals(9, allCaptor.allValues[1].dtlType)
    }

    @Test
    fun `데이터가 바뀌면 동일한 날짜라도 새 행을 INSERT한다`() {
        val analysisRepository = mock(DataReceiveAnalysisRepository::class.java)
        `when`(analysisRepository.findTopByDtlIpAndDtlLaneNoOrderByAnalIdDesc(anyString(), anyInt())).thenReturn(null)
        val persister = newPersister(analysisRepository)
        val state = newState("192.168.0.205")

        persister.persistStatusAnalysis(state, statusPacket(laneBlock(laneNo = 1, totalCount = 100)))
        val firstCaptor = ArgumentCaptor.forClass(DataReceiveAnalysis::class.java)
        verify(analysisRepository, timeout(5_000)).save(firstCaptor.capture())
        val firstSaved = firstCaptor.value

        // 통행량(total_count)이 바뀐 상태로 재수신 — "동일 데이터"가 아니므로 새 행이어야 한다.
        `when`(analysisRepository.findTopByDtlIpAndDtlLaneNoOrderByAnalIdDesc(anyString(), anyInt())).thenReturn(firstSaved)
        persister.persistStatusAnalysis(state, statusPacket(laneBlock(laneNo = 1, totalCount = 101)))

        val allCaptor = ArgumentCaptor.forClass(DataReceiveAnalysis::class.java)
        verify(analysisRepository, timeout(5_000).times(2)).save(allCaptor.capture())
        assertNotSame(firstSaved, allCaptor.allValues[1])
        assertEquals("101", allCaptor.allValues[1].descTotalCount)
    }

    @Test
    fun `err_type=3(장애)는 직전과 동일해도 매번 새로 INSERT하고 최신행 조회를 하지 않는다`() {
        val analysisRepository = mock(DataReceiveAnalysisRepository::class.java)
        val persister = newPersister(analysisRepository)
        val state = newState("192.168.0.205")
        val packet = statusPacket(laneBlock(laneNo = 1, errCheck = GateStatusAnalyzer.ErrorCheck.ERROR, totalCount = 100))

        persister.persistStatusAnalysis(state, packet)
        persister.persistStatusAnalysis(state, packet)

        val captor = ArgumentCaptor.forClass(DataReceiveAnalysis::class.java)
        verify(analysisRepository, timeout(5_000).times(2)).save(captor.capture())
        assertNotSame(captor.allValues[0], captor.allValues[1])
        assertTrue(captor.allValues.all { it.errType == GateStatusAnalyzer.ErrorCheck.ERROR })
        verify(analysisRepository, never()).findTopByDtlIpAndDtlLaneNoOrderByAnalIdDesc(anyString(), anyInt())
    }

    @Test
    fun `새 행을 INSERT할 때는 tb_gate_dtl을 다시 조회해 dtl_type 등 최신값을 반영한다`() {
        // 코드 리뷰 지적(2026-08-14) 회귀 방지 — 커넥션 캐시(dtlType=1)가 아니라 실 DB(dtlType=9)를
        // 반영해야 한다(dtl_id=159 사례: 캐시는 접속 당시 값을 재접속 전까지 계속 들고 있었다).
        val analysisRepository = mock(DataReceiveAnalysisRepository::class.java)
        `when`(analysisRepository.findTopByDtlIpAndDtlLaneNoOrderByAnalIdDesc(anyString(), anyInt())).thenReturn(null)
        val gateDetailRepository = mock(GateDetailRepository::class.java)
        val location = GateLocation(locId = 77, locName = "새위치")
        val group = GateGroup(grpId = 88, location = location, grpName = "새그룹", gateTypeCode = 1)
        val liveDetail = GateDetail(
            dtlId = 159, location = location, group = group,
            dtlIp = "192.168.0.205", dtlLaneNo = 1, dtlType = 9, dtlName = "새이름",
        )
        `when`(gateDetailRepository.findByDtlIpAndDtlLaneNo("192.168.0.205", 1)).thenReturn(liveDetail)
        val persister = newPersister(analysisRepository, gateDetailRepository)
        val state = newState("192.168.0.205", dtlType = 1) // 캐시는 여전히 dtlType=1

        persister.persistStatusAnalysis(state, statusPacket(laneBlock(laneNo = 1, totalCount = 100)))

        val captor = ArgumentCaptor.forClass(DataReceiveAnalysis::class.java)
        verify(analysisRepository, timeout(5_000)).save(captor.capture())
        assertEquals(9, captor.value.dtlType)
        assertEquals("새이름", captor.value.descGateName)
        assertEquals(77L, captor.value.locId)
        assertEquals(88L, captor.value.grpId)
    }
}
