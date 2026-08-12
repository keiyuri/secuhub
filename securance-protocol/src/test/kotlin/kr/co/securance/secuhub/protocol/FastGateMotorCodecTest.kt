package kr.co.securance.secuhub.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * [FastGateMotorCodec]이 `FastGate Protocol Ver1_2020102601_01.md`("■ Set/Request FAST GATE
 * MOTOR" 절)의 오프셋/길이대로 패킷을 조립하는지 검증한다.
 */
class FastGateMotorCodecTest {

    private fun stage(position: Int, rpm: Int, compensation: Int) =
        FastGateMotorCodec.MotorStage(position, rpm, compensation)

    private fun sampleAxis(base: Int) = FastGateMotorCodec.MotorAxis(
        stage1 = stage(base + 1, base + 2, base + 3),
        stage2 = stage(base + 4, base + 5, base + 6),
        stage3 = stage(base + 7, base + 8, base + 9),
        initSpeed = base,
    )

    @Test
    fun `Set 명령 - 헤더와 본문 길이를 정확히 채운다`() {
        val params = FastGateMotorCodec.Params(
            masterSlave = FastGateMotorCodec.MasterSlave.MASTER,
            turn = sampleAxis(100),
            slide = sampleAxis(200),
            autoCloseTimeMs10 = 300,
            autoTestTimeMs10 = 400,
            loofExitUsed = true,
            openTurnDelayTimeMs10 = 500,
            closedSlideDelayTimeMs10 = 600,
        )
        val packet = FastGateMotorCodec.buildSetCommand(laneNo = 3, params = params)

        // Header(27) + Data(72) + Tail(4) = 103
        assertEquals(103, packet.size)
        assertEquals(0x02, packet[0].toInt() and 0xFF) // STX
        assertEquals(0x05, packet[19].toInt() and 0xFF) // Command1 = SEND_DATA
        assertEquals(0x03, packet[20].toInt() and 0xFF) // Command2 = WRITE
        assertEquals(0x50, packet[21].toInt() and 0xFF) // ObjectCode = FAST_GATE_MOTOR('P')
        assertEquals(0, packet[22].toInt() and 0xFF) // DataInfoLength
        assertEquals(0, packet[23].toInt() and 0xFF) // DataCount high
        assertEquals(1, packet[24].toInt() and 0xFF) // DataCount low
        assertEquals(0, packet[25].toInt() and 0xFF) // DataLength high
        assertEquals(72, packet[26].toInt() and 0xFF) // DataLength low

        // Address(offset 6~18)는 문서 스펙 코덱 관례(SpeedGatePacketCodec.ZERO_ADDRESS)대로 전부 0.
        for (i in 6..18) assertEquals(0, packet[i].toInt() and 0xFF, "offset $i")

        // 패킷 길이 필드 = 전체 패킷 길이(103)
        assertEquals(0, packet[1].toInt() and 0xFF)
        assertEquals(103, packet[2].toInt() and 0xFF)

        val body = packet.copyOfRange(27, 27 + 72)
        assertEquals(3, body[0].toInt() and 0xFF) // 레인 번호
        assertEquals(0x01, body[1].toInt() and 0xFF) // Master

        // Turn 1-St Position = 101 (base=100, +1)
        assertEquals(101, ((body[2].toInt() and 0xFF) shl 8) or (body[3].toInt() and 0xFF))
        // Turn 3-St Compensation = 100+9=109 (offset 18~19)
        assertEquals(109, ((body[18].toInt() and 0xFF) shl 8) or (body[19].toInt() and 0xFF))
        // Turn Reserved(20~25) = 0
        for (i in 20..25) assertEquals(0, body[i].toInt() and 0xFF, "turn reserved $i")

        // Slide 1-St Position = 201 (base=200, +1), offset 26~27
        assertEquals(201, ((body[26].toInt() and 0xFF) shl 8) or (body[27].toInt() and 0xFF))
        // Slide Reserved(44~49) = 0
        for (i in 44..49) assertEquals(0, body[i].toInt() and 0xFF, "slide reserved $i")

        // Turn Init Speed(50~51)=100, Slide Init Speed(52~53)=200
        assertEquals(100, ((body[50].toInt() and 0xFF) shl 8) or (body[51].toInt() and 0xFF))
        assertEquals(200, ((body[52].toInt() and 0xFF) shl 8) or (body[53].toInt() and 0xFF))

        // Auto Close(54~55)=300, Auto Test(56~57)=400
        assertEquals(300, ((body[54].toInt() and 0xFF) shl 8) or (body[55].toInt() and 0xFF))
        assertEquals(400, ((body[56].toInt() and 0xFF) shl 8) or (body[57].toInt() and 0xFF))

        // Loof Exit Used(58)=1
        assertEquals(1, body[58].toInt() and 0xFF)

        // Open Turn Delay(59~60)=500, Closed Slide Delay(61~62)=600
        assertEquals(500, ((body[59].toInt() and 0xFF) shl 8) or (body[60].toInt() and 0xFF))
        assertEquals(600, ((body[61].toInt() and 0xFF) shl 8) or (body[62].toInt() and 0xFF))

        // Motor Reserved(63~71) = 0
        for (i in 63..71) assertEquals(0, body[i].toInt() and 0xFF, "motor reserved $i")

        // Tail 체크섬 검증
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
    fun `Set 명령 - laneNo 범위 밖이면 예외`() {
        val axis = sampleAxis(0)
        val params = FastGateMotorCodec.Params(FastGateMotorCodec.MasterSlave.MASTER, axis, axis)
        assertFailsWith<IllegalArgumentException> { FastGateMotorCodec.buildSetCommand(0, params) }
        assertFailsWith<IllegalArgumentException> { FastGateMotorCodec.buildSetCommand(33, params) }
    }

    @Test
    fun `MotorStage - 범위 밖 값은 예외`() {
        assertFailsWith<IllegalArgumentException> { stage(-1, 0, 0) }
        assertFailsWith<IllegalArgumentException> { stage(0x10000, 0, 0) }
    }

    @Test
    fun `Request 명령 - 레인 번호 N개를 본문에 그대로 나열한다`() {
        val packet = FastGateMotorCodec.buildRequestCommand(listOf(1, 5, 10))

        // Header(27) + Data(3) + Tail(4) = 34
        assertEquals(34, packet.size)
        assertEquals(0x06, packet[19].toInt() and 0xFF) // Command1 = REQUEST_DATA
        assertEquals(0x02, packet[20].toInt() and 0xFF) // Command2 = READ
        assertEquals(0x50, packet[21].toInt() and 0xFF) // ObjectCode = FAST_GATE_MOTOR
        assertEquals(0, packet[23].toInt() and 0xFF) // DataCount high
        assertEquals(3, packet[24].toInt() and 0xFF) // DataCount low = N
        assertEquals(1, packet[26].toInt() and 0xFF) // DataLength low = 1(레인 번호 1개당 1바이트)

        val body = packet.copyOfRange(27, 27 + 3)
        assertEquals(listOf(1, 5, 10), body.map { it.toInt() and 0xFF })
    }

    @Test
    fun `Request 명령 - 빈 목록이면 예외`() {
        assertFailsWith<IllegalArgumentException> { FastGateMotorCodec.buildRequestCommand(emptyList()) }
    }
}
