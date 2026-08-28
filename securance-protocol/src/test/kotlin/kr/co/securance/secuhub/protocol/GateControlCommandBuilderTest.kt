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
        assertEquals(0x00, packet[25].toInt() and 0xFF) // Data Length(high) — 93 < 256이므로 0
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
    fun `모드 변경 - 소문자 controlType도 보안모드가 대소문자 무관하게 반영된다`() {
        // 회귀 방지 테스트(코드 리뷰 지적, 2026-08-28): 운영모드(ctrlTp1)는 uppercase()를 거쳤지만
        // 보안모드 판정만 정규화 전 원본 문자열을 검사해, 소문자 호출 시 운영모드는 반영되고
        // 보안모드만 조용히 누락되는 불일치가 있었다.
        val packet = GateControlCommandBuilder.buildModeChangeCommand(laneNo = 5, controlType = "cfmm")
        assertEquals(0x02, packet[28].toInt() and 0xFF) // cf -> CARD_FREE
        assertEquals(0x02, packet[29].toInt() and 0xFF) // mm -> 0x02(대소문자 무관하게 반영돼야 함)
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
        // Data Length(low)는 #12의 레거시 고정값(0x5D=93)이 아니라 실제 payload 크기(17)여야 한다
        // (코드 리뷰 재검토, 2026-08-28 사용자 확인 — 93은 모드변경 페이로드 크기와 우연히 일치할
        // 뿐 모터설정 페이로드와는 무관하다).
        assertEquals(17, packet[26].toInt() and 0xFF)

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
        // Data Length(low)는 #12/#13의 레거시 고정값(0x5D=93)이 아니라 실제 payload 크기(26)여야
        // 한다 — 코드 리뷰 지적: 대응 legacy(SetTimeData)가 별개 함수라 고정값이 검증되지 않았다.
        assertEquals(26, packet[26].toInt() and 0xFF)
        assertEquals(payload.toList(), packet.copyOfRange(27, 27 + 26).toList())
    }

    @Test
    fun `Data Length가 256 이상이면 상위 바이트도 정확히 채워진다`() {
        // Opus 재검증 회귀 테스트(2026-08-28): 기존에는 header[26](하위 바이트)만 채우고
        // header[25](상위 바이트)를 항상 0으로 남겨, 256 이상 payload에서는 and 0xFF로 초과분이
        // 조용히 잘렸다. 300 = 0x012C -> high=0x01, low=0x2C가 나와야 한다.
        val payload = ByteArray(300) { 0 }
        val packet = GateControlCommandBuilder.buildTimeSyncCommand(payload)

        assertEquals(0x01, packet[25].toInt() and 0xFF) // Data Length(high)
        assertEquals(0x2C, packet[26].toInt() and 0xFF) // Data Length(low)
    }

    @Test
    fun `Data Length가 범위(0~65535)를 벗어나면 예외`() {
        // buildPacket은 private이므로 public 진입점(buildTimeSyncCommand)을 통해 검증한다.
        assertFailsWith<IllegalArgumentException> {
            GateControlCommandBuilder.buildTimeSyncCommand(ByteArray(65536))
        }
    }
}
