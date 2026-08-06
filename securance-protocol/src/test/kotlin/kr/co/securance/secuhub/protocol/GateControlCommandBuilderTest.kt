package kr.co.securance.secuhub.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * [GateControlCommandBuilder]가 레거시 `SR_C_DataHandler`(Brian 보드 분기)와 바이트 단위로
 * 동일한 패킷을 만드는지 검증한다. 기대값은 레거시 소스(`SR_C_DataHandler.cs`,
 * `SR_P_GateModeChange.cs`, `SR_P_GateSetupMotor.cs`)의 로직을 손으로 재계산한 것이다.
 */
class GateControlCommandBuilderTest {

    @Test
    fun `모드 변경 - 기본값(CC+LM, 레인 1) 헤더와 바디를 정확히 채운다`() {
        val packet = GateControlCommandBuilder.buildModeChangeCommand(laneNo = 1, controlType = "CCLM")

        // Header(27) + Data(93) + Tail(4) = 124
        assertEquals(124, packet.size)

        assertEquals(0x02, packet[0].toInt() and 0xFF) // STX
        assertEquals(0x04, packet[3].toInt() and 0xFF) // Version
        assertEquals(0x80, packet[4].toInt() and 0xFF) // Frame Option1
        assertEquals(0x40, packet[5].toInt() and 0xFF) // Frame Option2
        assertEquals(0x05, packet[19].toInt() and 0xFF) // Command1 = SEND_DATA
        assertEquals(0x03, packet[20].toInt() and 0xFF) // Command2 = WRITE
        assertEquals(0x4C, packet[21].toInt() and 0xFF) // ObjectCode = GATE_SETTING('L')
        assertEquals(0x01, packet[24].toInt() and 0xFF) // Data Count(low) — 레거시 고정값
        assertEquals(0x5D, packet[26].toInt() and 0xFF) // Data Length(low) — 레거시 고정값(버그 재현)

        // Length 필드 = 전체 패킷 길이(124)
        assertEquals(0, packet[1].toInt() and 0xFF)
        assertEquals(124, packet[2].toInt() and 0xFF)

        // Address(offset 6~18)는 레거시와 동일하게 전부 0이어야 한다.
        for (i in 6..18) assertEquals(0, packet[i].toInt() and 0xFF, "offset $i")

        // Data Body(offset 27부터): [0]=레인번호, [1]=운영모드(CC->Normal=0x01), [2]=보안모드(LM->0x01)
        assertEquals(1, packet[27].toInt() and 0xFF)
        assertEquals(0x01, packet[28].toInt() and 0xFF) // ControlMode.NORMAL(그 외 값이므로 기본)
        assertEquals(0x01, packet[29].toInt() and 0xFF) // LM -> 0x01

        // Tail 체크섬 검증(XOR/SUM은 STX~Data 마지막 바이트까지)
        val bodyEnd = packet.size - 4
        var xor = 0
        var sum = 0
        for (i in 0 until bodyEnd) {
            val v = packet[i].toInt() and 0xFF
            xor = xor xor v
            sum = (sum + v) and 0xFF
        }
        assertEquals(xor, packet[bodyEnd].toInt() and 0xFF)
        assertEquals(sum, packet[bodyEnd + 1].toInt() and 0xFF)
        assertEquals(0x08, packet[bodyEnd + 2].toInt() and 0xFF)
        assertEquals(0x03, packet[bodyEnd + 3].toInt() and 0xFF) // ETX
    }

    @Test
    fun `모드 변경 - CF+MM 조합이 운영-보안 모드 바이트에 정확히 반영된다`() {
        val packet = GateControlCommandBuilder.buildModeChangeCommand(laneNo = 5, controlType = "CFMM")
        assertEquals(5, packet[27].toInt() and 0xFF)
        assertEquals(0x02, packet[28].toInt() and 0xFF) // CF -> CARD_FREE
        assertEquals(0x02, packet[29].toInt() and 0xFF) // MM -> 0x02
    }

    @Test
    fun `모드 변경 - 시간대 스케줄 바이트가 offset 3, 6부터 복사된다`() {
        val userTime = byteArrayOf(0x01, 0x0A, 0x0B)
        val secuTime = byteArrayOf(0x02, 0x0C, 0x0D)
        val packet = GateControlCommandBuilder.buildModeChangeCommand(
            laneNo = 1,
            controlType = "CCLM",
            timeDataUser = userTime,
            timeDataSecu = secuTime,
        )
        assertEquals(listOf(0x01, 0x0A, 0x0B), (30..32).map { packet[it].toInt() and 0xFF })
        assertEquals(listOf(0x02, 0x0C, 0x0D), (33..35).map { packet[it].toInt() and 0xFF })
    }

    @Test
    fun `모드 변경 - 레인 번호가 범위를 벗어나면 예외`() {
        assertFailsWith<IllegalArgumentException> {
            GateControlCommandBuilder.buildModeChangeCommand(laneNo = 256, controlType = "CCLM")
        }
    }

    @Test
    fun `모터 설정 - 메인-서브 파라미터가 레거시 bMotor 인덱스 그대로 채워진다`() {
        val main = GateControlCommandBuilder.MotorParams(
            initSpeed = 10, initCount = 20, openSpeed = 30, openCount = 40, closeSpeed = 50, closeCount = 60,
        )
        val sub = GateControlCommandBuilder.MotorParams(
            initSpeed = 11, initCount = 21, openSpeed = 31, openCount = 41, closeSpeed = 51, closeCount = 61,
        )
        val packet = GateControlCommandBuilder.buildMotorSetupCommand(laneNo = 3, main = main, sub = sub)

        // Header(27) + Data(17) + Tail(4) = 48
        assertEquals(48, packet.size)
        assertEquals(0x4B, packet[21].toInt() and 0xFF) // ObjectCode = GATE_MOTOR('K')

        val data = packet.copyOfRange(27, 27 + 17)
        assertEquals(3, data[0].toInt() and 0xFF) // 레인 번호
        assertEquals(listOf(10, 20, 30, 40, 50, 60), (1..6).map { data[it].toInt() and 0xFF })
        assertEquals(0, data[7].toInt() and 0xFF) // 미사용 영역
        assertEquals(0, data[8].toInt() and 0xFF)
        assertEquals(listOf(11, 21, 31, 41, 51, 61), (9..14).map { data[it].toInt() and 0xFF })
        assertEquals(0, data[15].toInt() and 0xFF)
        assertEquals(0, data[16].toInt() and 0xFF)
    }

    @Test
    fun `모터 설정 - 파라미터가 byte 범위(0~255)를 벗어나면 예외`() {
        assertFailsWith<IllegalArgumentException> {
            GateControlCommandBuilder.MotorParams(initSpeed = 256)
        }
        assertFailsWith<IllegalArgumentException> {
            GateControlCommandBuilder.MotorParams(closeCount = -1)
        }
    }

    @Test
    fun `타임존 동기화 - ObjectCode 0x54로 프레이밍된다`() {
        val payload = ByteArray(26) { it.toByte() }
        val packet = GateControlCommandBuilder.buildTimeSyncCommand(payload)

        // Header(27) + Data(26) + Tail(4) = 57
        assertEquals(57, packet.size)
        assertEquals(0x54, packet[21].toInt() and 0xFF) // ObjectCode = TIME('T')
        assertEquals(payload.toList(), packet.copyOfRange(27, 27 + 26).toList())
    }
}
