package kr.co.securance.secuhub.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 레인 상태 블록 분석 검증 — 레거시 DB 트리거 `utrg_data_rcv_anlz`의 판정 규칙을
 * 그대로 재현하는지 확인한다(2차 스프린트 2번 항목).
 */
class GateStatusAnalyzerTest {

    private val offsets = GateStatusAnalyzer.StatusOffset

    /**
     * 상태 패킷 하나를 합성한다. Header(27) + DataInfo(45) + 레인 블록(74×N) + Tail(4).
     *
     * @param laneBlocks 레인별 74바이트 블록. 각 블록은 호출자가 필드를 채워 넘긴다.
     */
    private fun statusPacket(vararg laneBlocks: ByteArray): ByteArray {
        val header = ByteArray(SpeedGateProtocolConstants.HEADER_LENGTH)
        header[SpeedGateProtocolConstants.HeaderOffset.STX] = SpeedGateProtocolConstants.STX
        header[SpeedGateProtocolConstants.HeaderOffset.OBJECT_CODE] =
            SpeedGateProtocolConstants.ObjectCode.GATE_STATUS

        val info = ByteArray(SpeedGateProtocolConstants.DATA_INFO_LENGTH)
        // DataInfo의 마지막 바이트(패킷 기준 오프셋 71)가 LOCAL GATE LANE COUNT다.
        info[SpeedGateProtocolConstants.DATA_INFO_LENGTH - 1] = laneBlocks.size.toByte()

        val body = header + info + laneBlocks.reduce { acc, bytes -> acc + bytes }
        return body + ByteArray(SpeedGateProtocolConstants.TAIL_LENGTH)
    }

    /** 정상 상태 레인 블록(모든 필드 0)에서 시작해 필요한 필드만 덮어쓴다. */
    private fun laneBlock(laneNo: Int, block: ByteArray.() -> Unit = {}): ByteArray =
        ByteArray(SpeedGateProtocolConstants.STATUS_DATA_LENGTH).apply {
            this[offsets.LANE_NUMBER] = laneNo.toByte()
            block()
        }

    @Test
    fun `정상 레인은 NOR로 분류되고 설명이 비어 있다`() {
        val packet = statusPacket(laneBlock(1))

        val result = GateStatusAnalyzer.analyze(packet).single()

        assertEquals(1, result.laneNumber)
        assertEquals(GateStatusAnalyzer.AnalysisType.NOR, result.analysisType)
        assertFalse(result.hasAnyDescription)
        // err_type이 0(ERROR 아님)이면 해결 완료 상태로 기록된다.
        assertEquals("Y", result.resolveYn)
    }

    @Test
    fun `운영 센서 장애는 PLM으로 분류되고 S00 라벨이 붙는다`() {
        val packet = statusPacket(
            laneBlock(1) {
                this[offsets.OPERATION_SENSOR1] = 3 // 첫 번째 채널 장애
                this[offsets.ERROR_CHECK] = 3
            },
        )

        val result = GateStatusAnalyzer.analyze(packet).single()

        assertEquals(GateStatusAnalyzer.AnalysisType.PLM, result.analysisType)
        assertEquals("S00", result.descOperation[0])
        assertEquals(GateStatusAnalyzer.ErrorCheck.ERROR, result.errType)
        assertEquals("N", result.resolveYn)
        assertTrue(result.hasAnyDescription)
    }

    @Test
    fun `안전 센서 4채널은 S04부터 S07까지 라벨링된다`() {
        val packet = statusPacket(
            laneBlock(1) {
                this[offsets.SAFETY_SENSOR + 3] = 3
                this[offsets.OPERATION_SENSOR2 + 3] = 3
            },
        )

        val result = GateStatusAnalyzer.analyze(packet).single()

        assertEquals("S07", result.descSafety[3])
        assertEquals("S11", result.descOperation2[3])
    }

    @Test
    fun `모터 장애는 PLM으로 분류되고 status10에 기록된다`() {
        val packet = statusPacket(
            laneBlock(1) {
                this[offsets.MAIN_MOTOR_ERROR] = 3
                this[offsets.ERROR_CHECK] = 3
            },
        )

        val result = GateStatusAnalyzer.analyze(packet).single()

        assertEquals(GateStatusAnalyzer.AnalysisType.PLM, result.analysisType)
        assertEquals("MAIN MOTOR ERROR", result.descMainMotorError)
        assertEquals("MAIN MOTOR ERROR", result.descGateStatus[9])
    }

    @Test
    fun `화재 경보는 EVT로 분류되고 status07에 기록된다`() {
        val packet = statusPacket(laneBlock(1) { this[offsets.FIRE_ALARM] = 1 })

        val result = GateStatusAnalyzer.analyze(packet).single()

        assertEquals(GateStatusAnalyzer.AnalysisType.EVT, result.analysisType)
        assertEquals("FIRE ALARM", result.descFireAlarm)
    }

    @Test
    fun `역방향 진입 이벤트는 센서 장애보다 우선순위가 낮다`() {
        // 레거시 CASE 식은 센서 장애(PLM)를 먼저 평가하므로 둘 다 발생하면 PLM이다.
        val packet = statusPacket(
            laneBlock(1) {
                this[offsets.OPERATION_SENSOR1] = 3
                this[offsets.BACK_RUSH] = 1
            },
        )

        val result = GateStatusAnalyzer.analyze(packet).single()

        assertEquals(GateStatusAnalyzer.AnalysisType.PLM, result.analysisType)
        // 분류가 PLM이어도 이벤트 설명은 그대로 남는다(트리거의 desc_* 컬럼과 동일).
        assertEquals("BACK RUSH(역방향 진입) 발생", result.descGateStatus[0])
    }

    @Test
    fun `개폐 상태 변경은 STA로 분류된다`() {
        val packet = statusPacket(laneBlock(1) { this[offsets.OPEN_CLOSE] = 1 })

        assertEquals(
            GateStatusAnalyzer.AnalysisType.STA,
            GateStatusAnalyzer.analyze(packet).single().analysisType,
        )
    }

    @Test
    fun `리모컨 라벨은 운영 모드가 OPEN 또는 CLOSED일 때만 붙는다`() {
        val opened = statusPacket(
            laneBlock(1) {
                this[offsets.USER_MODE] = 5 // OPEN
                this[offsets.REMOCON] = 1
            },
        )
        assertEquals("REMOCON OPEN", GateStatusAnalyzer.analyze(opened).single().descGateStatus[5])

        // 운영 모드가 카드 모드(1)면 같은 리모컨 값이어도 라벨이 붙지 않는다.
        val carded = statusPacket(
            laneBlock(1) {
                this[offsets.USER_MODE] = 1
                this[offsets.REMOCON] = 1
            },
        )
        assertTrue(GateStatusAnalyzer.analyze(carded).single().descGateStatus[5].isBlank())
    }

    @Test
    fun `다중 레인 패킷은 레인마다 개별 분석 결과를 만든다`() {
        // 레거시 DB 트리거는 고정 오프셋만 사용해 1번 레인만 분석했다 — 2번 레인 장애가
        // 영원히 기록되지 않던 문제를 재현하지 않는지 확인한다.
        val packet = statusPacket(
            laneBlock(1),
            laneBlock(2) {
                this[offsets.MAIN_MOTOR_ERROR] = 3
                this[offsets.ERROR_CHECK] = 3
            },
        )

        val results = GateStatusAnalyzer.analyze(packet)

        assertEquals(2, results.size)
        assertEquals(GateStatusAnalyzer.AnalysisType.NOR, results[0].analysisType)
        assertEquals(2, results[1].laneNumber)
        assertEquals(GateStatusAnalyzer.AnalysisType.PLM, results[1].analysisType)
        assertEquals("N", results[1].resolveYn)
    }

    @Test
    fun `누적 카운트는 4바이트 빅엔디언으로 읽는다`() {
        val packet = statusPacket(
            laneBlock(1) {
                this[offsets.TOTAL_COUNT] = 0x00
                this[offsets.TOTAL_COUNT + 1] = 0x01
                this[offsets.TOTAL_COUNT + 2] = 0x02
                this[offsets.TOTAL_COUNT + 3] = 0x03
                this[offsets.MOTOR_COUNT + 3] = 0x0A
                this[offsets.MASTER_IN_COUNT + 3] = 0x07
            },
        )

        val result = GateStatusAnalyzer.analyze(packet).single()

        assertEquals(0x00010203L, result.totalCount)
        assertEquals(10, result.motorCount)
        assertEquals(7, result.masterInTotal)
    }

    @Test
    fun `잘린 패킷은 예외 대신 null을 반환해 정상 레인 분석을 막지 않는다`() {
        val truncated = ByteArray(SpeedGateProtocolConstants.HEADER_LENGTH + 10)

        assertNull(GateStatusAnalyzer.analyzeLane(truncated, blockOffset = 72))
        assertTrue(GateStatusAnalyzer.analyze(truncated).isEmpty())
    }

    @Test
    fun `코드 설명 매핑은 레거시 트리거와 동일하다`() {
        assertEquals("SR-1400", GateStatusAnalyzer.describeGateType(1))
        assertEquals("Flap", GateStatusAnalyzer.describeGateType(2))
        assertEquals("IN(CARD)/OUT(FREE)", GateStatusAnalyzer.describeUserMode(2))
        assertEquals("OPEN", GateStatusAnalyzer.describeUserMode(5))
        assertEquals("HIGH MODE", GateStatusAnalyzer.describeSecurityMode(3))
        // 정의되지 않은 코드는 숫자를 그대로 문자열화한다(트리거의 ELSE 분기).
        assertEquals("99", GateStatusAnalyzer.describeGateType(99))
    }
}
