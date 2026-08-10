package kr.co.securance.secuhub.protocol

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ACK 회신/제어 명령 패킷 빌더 테스트(1차 스프린트 1·4·5번 항목).
 *
 * 레거시 `ClsCommon.MakeACKDataAddTime`와 `SR_C_DataHandler.SetControlCmd`/`GenerateCmdBody`가
 * 만들던 바이트 배열과 동일한 구조가 나오는지를 오프셋 단위로 검증한다.
 */
class SpeedGateAckControlCodecTest {

    private val fixedTime = LocalDateTime.of(2026, 8, 5, 10, 30, 15)

    // ── ACK 회신 ─────────────────────────────────────────────────────

    @Test
    fun `ACK 패킷은 레거시와 동일한 38바이트 구조를 가진다`() {
        val ack = SpeedGatePacketCodec.buildAck(SpeedGateProtocolConstants.ObjectCode.GATE_STATUS, fixedTime)

        // 레거시 MakeACKDataAddTime: new byte[38] = Header(27) + DataInfo(7) + Tail(4)
        assertEquals(SpeedGateProtocolConstants.ACK_PACKET_LENGTH, ack.size)
        assertEquals(38, ack.size)

        assertEquals(SpeedGateProtocolConstants.STX, ack[SpeedGateProtocolConstants.HeaderOffset.STX])
        assertEquals(SpeedGateProtocolConstants.PROTOCOL_VERSION, ack[SpeedGateProtocolConstants.HeaderOffset.PROTOCOL_VERSION])
        assertEquals(SpeedGateProtocolConstants.Command1.SEND_ACK, ack[SpeedGateProtocolConstants.HeaderOffset.COMMAND1])
        assertEquals(SpeedGateProtocolConstants.Command2.READ, ack[SpeedGateProtocolConstants.HeaderOffset.COMMAND2])
        assertEquals(7, ack[SpeedGateProtocolConstants.HeaderOffset.DATA_INFO_LENGTH].toInt())

        // 길이 필드와 체크섬은 빌더가 자동 계산한다.
        val declaredLength = ((ack[1].toInt() and 0xFF) shl 8) or (ack[2].toInt() and 0xFF)
        assertEquals(ack.size, declaredLength)
        assertTrue(SpeedGatePacketCodec.verifyChecksum(ack))
        assertEquals(SpeedGateProtocolConstants.ETX, ack.last())
    }

    @Test
    fun `ACK는 수신 패킷의 Object Code를 그대로 되돌려준다`() {
        for (objectCode in listOf(
            SpeedGateProtocolConstants.ObjectCode.GATE_STATUS,
            SpeedGateProtocolConstants.ObjectCode.GATE_SETTING,
            SpeedGateProtocolConstants.ObjectCode.GATE_MOTOR,
        )) {
            val ack = SpeedGatePacketCodec.buildAck(objectCode, fixedTime)
            assertEquals(objectCode, ack[SpeedGateProtocolConstants.HeaderOffset.OBJECT_CODE])
        }
    }

    @Test
    fun `ACK의 DataInfo에는 현재 시각이 BCD로 실린다`() {
        val ack = SpeedGatePacketCodec.buildAck(SpeedGateProtocolConstants.ObjectCode.GATE_STATUS, fixedTime)
        val dataInfo = ack.copyOfRange(
            SpeedGateProtocolConstants.HEADER_LENGTH,
            SpeedGateProtocolConstants.HEADER_LENGTH + 7,
        )

        assertEquals(SpeedGatePacketCodec.encodeDateTime(fixedTime).toList(), dataInfo.toList())
        assertEquals(26, SpeedGatePacketCodec.fromBcd(dataInfo[0])) // 2026
        assertEquals(30, SpeedGatePacketCodec.fromBcd(dataInfo[5])) // 분
    }

    @Test
    fun `ACK의 Address 구간은 레거시와 동일하게 0으로 채워진다`() {
        val ack = SpeedGatePacketCodec.buildAck(SpeedGateProtocolConstants.ObjectCode.GATE_STATUS, fixedTime)
        val address = ack.copyOfRange(
            SpeedGateProtocolConstants.HeaderOffset.ADDRESS,
            SpeedGateProtocolConstants.HeaderOffset.ADDRESS + SpeedGateProtocolConstants.ADDRESS_LENGTH,
        )

        assertTrue(address.all { it == 0.toByte() })
    }

    // ── 제어 명령 ────────────────────────────────────────────────────

    @Test
    fun `제어 명령 패킷은 레거시 헤더 규격(0x05-0x03-0x4C, DataCount=1, DataLength=93)을 따른다`() {
        val packet = SpeedGatePacketCodec.buildControlCommand(laneNo = 1, command = SpeedGateControlCommand.OPEN)

        // Header(27) + Body(93) + Tail(4)
        assertEquals(124, packet.size)
        assertEquals(SpeedGateProtocolConstants.Command1.SEND_DATA, packet[SpeedGateProtocolConstants.HeaderOffset.COMMAND1])
        assertEquals(SpeedGateProtocolConstants.Command2.WRITE, packet[SpeedGateProtocolConstants.HeaderOffset.COMMAND2])
        assertEquals(SpeedGateProtocolConstants.ObjectCode.GATE_SETTING, packet[SpeedGateProtocolConstants.HeaderOffset.OBJECT_CODE])

        val dataCount = ((packet[SpeedGateProtocolConstants.HeaderOffset.DATA_COUNT].toInt() and 0xFF) shl 8) or
            (packet[SpeedGateProtocolConstants.HeaderOffset.DATA_COUNT + 1].toInt() and 0xFF)
        val dataLength = ((packet[SpeedGateProtocolConstants.HeaderOffset.DATA_LENGTH].toInt() and 0xFF) shl 8) or
            (packet[SpeedGateProtocolConstants.HeaderOffset.DATA_LENGTH + 1].toInt() and 0xFF)
        assertEquals(1, dataCount)
        assertEquals(0x5D, dataLength) // 레거시 header[26] = 0x5D (=93)

        assertTrue(SpeedGatePacketCodec.verifyChecksum(packet))
    }

    @Test
    fun `레인 번호는 16진(바이트 값 그대로)으로 인코딩된다`() {
        // 레거시 2026-07-28 확정 규칙: 레인 10 -> 0x0A (BCD 아님)
        val packet = SpeedGatePacketCodec.buildControlCommand(laneNo = 10, command = SpeedGateControlCommand.CLOSE)
        val body = controlBodyOf(packet)

        assertEquals(0x0A, body[SpeedGateProtocolConstants.ControlBodyOffset.LANE_NUMBER].toInt() and 0xFF)
    }

    @Test
    fun `운영 모드 명령은 모드 바이트만 채우고 리셋 바이트는 0으로 둔다`() {
        val expectedModes = mapOf(
            SpeedGateControlCommand.NORMAL to 0x01,
            SpeedGateControlCommand.CARD_FREE to 0x02,
            SpeedGateControlCommand.FREE_CARD to 0x03,
            SpeedGateControlCommand.FREE_FREE to 0x04,
            SpeedGateControlCommand.OPEN to 0x05,
            SpeedGateControlCommand.CLOSE to 0x06,
        )

        for ((command, expectedMode) in expectedModes) {
            val body = controlBodyOf(SpeedGatePacketCodec.buildControlCommand(1, command))
            assertEquals(expectedMode, body[SpeedGateProtocolConstants.ControlBodyOffset.CONTROL_MODE].toInt() and 0xFF)
            assertEquals(0, body[SpeedGateProtocolConstants.ControlBodyOffset.RESET_CODE].toInt())
            assertFalse(command.isReset)
        }
    }

    @Test
    fun `리셋 명령은 오프셋 20에 리셋 코드를, 모드에는 기본값 Normal을 채운다`() {
        // 레거시 GenerateCmdBody: 리셋 코드("AC"/"OS"/"SS"/"MT"/"MB")는 mode switch의 default로 빠져
        // 항상 Normal(0x01)이 들어가고, 리셋 코드만 bCmdData[20]에 별도로 채워진다.
        val expectedResets = mapOf(
            SpeedGateControlCommand.RESET_SYSTEM to 0x01,
            SpeedGateControlCommand.RESET_OPERATION_SENSOR to 0x02,
            SpeedGateControlCommand.RESET_SAFETY_SENSOR to 0x03,
            SpeedGateControlCommand.RESET_MOTOR to 0x04,
            SpeedGateControlCommand.RESET_BOARD to 0x05,
        )

        for ((command, expectedReset) in expectedResets) {
            val body = controlBodyOf(SpeedGatePacketCodec.buildControlCommand(3, command))
            assertTrue(command.isReset, "$command 은(는) 리셋 명령이어야 한다")
            assertEquals(0x01, body[SpeedGateProtocolConstants.ControlBodyOffset.CONTROL_MODE].toInt() and 0xFF)
            assertEquals(expectedReset, body[SpeedGateProtocolConstants.ControlBodyOffset.RESET_CODE].toInt() and 0xFF)
        }
    }

    @Test
    fun `레거시 ControlMode 열거형의 나머지 모드도 동일한 값으로 인코딩된다`() {
        // SR_C_DataHandler.cs:9 ControlMode — 2차 스프린트에서 추가한 5종.
        val expectedModes = mapOf(
            SpeedGateControlCommand.CARD_CLOSE to 0x07,
            SpeedGateControlCommand.CLOSE_CARD to 0x08,
            SpeedGateControlCommand.FREE_CLOSE to 0x09,
            SpeedGateControlCommand.CLOSE_FREE to 0x0A,
            SpeedGateControlCommand.REVERSE_OPEN to 0x15,
        )

        for ((command, expectedMode) in expectedModes) {
            val body = controlBodyOf(SpeedGatePacketCodec.buildControlCommand(1, command))
            assertEquals(expectedMode, body[SpeedGateProtocolConstants.ControlBodyOffset.CONTROL_MODE].toInt() and 0xFF)
            assertFalse(command.isReset)
        }
    }

    @Test
    fun `보안 등급은 본문 오프셋 2에 인코딩된다`() {
        for (mode in SpeedGateSecurityMode.entries) {
            val packet = SpeedGatePacketCodec.buildControlCommand(
                laneNo = 1,
                payload = SpeedGateControlPayload(SpeedGateControlCommand.NORMAL, securityMode = mode),
            )
            val body = controlBodyOf(packet)
            assertEquals(
                mode.value,
                body[SpeedGateProtocolConstants.ControlBodyOffset.SECURITY_MODE],
            )
        }
    }

    @Test
    fun `보안 등급을 지정하지 않으면 오프셋 2는 0으로 남는다`() {
        // 레거시 GenerateCmdBody도 "LM/MM/HM"이 없으면 배열 초기값(0)을 그대로 보냈다 — 변경 없음 의미.
        val body = controlBodyOf(SpeedGatePacketCodec.buildControlCommand(1, SpeedGateControlCommand.OPEN))
        assertEquals(0, body[SpeedGateProtocolConstants.ControlBodyOffset.SECURITY_MODE].toInt())
    }

    @Test
    fun `스케줄 시간 데이터는 오프셋 3과 6에 3바이트씩 인코딩된다`() {
        val userTime = byteArrayOf(0x08, 0x1E, 0x00) // 08:30:00
        val securityTime = byteArrayOf(0x12, 0x00, 0x00) // 18:00:00

        val packet = SpeedGatePacketCodec.buildControlCommand(
            laneNo = 1,
            payload = SpeedGateControlPayload(
                command = SpeedGateControlCommand.NORMAL,
                userTime = userTime,
                securityTime = securityTime,
            ),
        )
        val body = controlBodyOf(packet)

        val userOffset = SpeedGateProtocolConstants.ControlBodyOffset.TIME_DATA_USER
        val secOffset = SpeedGateProtocolConstants.ControlBodyOffset.TIME_DATA_SECURITY
        assertEquals(userTime.toList(), body.copyOfRange(userOffset, userOffset + 3).toList())
        assertEquals(securityTime.toList(), body.copyOfRange(secOffset, secOffset + 3).toList())
        assertTrue(SpeedGatePacketCodec.verifyChecksum(packet))
    }

    @Test
    fun `시간 데이터 길이가 3바이트가 아니면 거부한다`() {
        assertFailsWith<IllegalArgumentException> {
            SpeedGateControlPayload(SpeedGateControlCommand.NORMAL, userTime = byteArrayOf(0x01, 0x02))
        }
        assertFailsWith<IllegalArgumentException> {
            SpeedGateControlPayload(SpeedGateControlCommand.NORMAL, securityTime = ByteArray(4))
        }
    }

    @Test
    fun `범위를 벗어난 레인 번호는 거부한다`() {
        assertFailsWith<IllegalArgumentException> { SpeedGatePacketCodec.buildControlCommand(0, SpeedGateControlCommand.OPEN) }
        assertFailsWith<IllegalArgumentException> { SpeedGatePacketCodec.buildControlCommand(33, SpeedGateControlCommand.OPEN) }
    }

    @Test
    fun `레거시 문자열 코드로 명령을 조회할 수 있다`() {
        assertEquals(SpeedGateControlCommand.OPEN, SpeedGateControlCommand.ofLegacyCode("op"))
        assertEquals(SpeedGateControlCommand.RESET_MOTOR, SpeedGateControlCommand.ofLegacyCode(" MT "))
        assertNull(SpeedGateControlCommand.ofLegacyCode("ZZ"))
        assertNull(SpeedGateControlCommand.ofLegacyCode(null))
    }

    // ── 코덱 인터페이스 위임 ─────────────────────────────────────────

    @Test
    fun `SpeedFlapGateProtocolCodec은 ACK 제어 명령 생성을 그대로 위임한다`() {
        val codec = SpeedFlapGateProtocolCodec()

        assertEquals(
            SpeedGatePacketCodec.buildAck(SpeedGateProtocolConstants.ObjectCode.GATE_STATUS, fixedTime).toList(),
            codec.buildAck(SpeedGateProtocolConstants.ObjectCode.GATE_STATUS, fixedTime).toList(),
        )
        assertEquals(
            SpeedGatePacketCodec.buildControlCommand(2, SpeedGateControlCommand.OPEN).toList(),
            codec.buildControlCommand(2, SpeedGateControlCommand.OPEN).toList(),
        )
        assertEquals(SpeedGateProtocolConstants.ADDRESS_LENGTH, codec.defaultAddress.size)
        assertTrue(codec.defaultAddress.all { it == 0.toByte() })
    }

    private fun controlBodyOf(packet: ByteArray): ByteArray =
        packet.copyOfRange(
            SpeedGateProtocolConstants.HEADER_LENGTH,
            SpeedGateProtocolConstants.HEADER_LENGTH + SpeedGateProtocolConstants.CONTROL_BODY_LENGTH,
        )
}
