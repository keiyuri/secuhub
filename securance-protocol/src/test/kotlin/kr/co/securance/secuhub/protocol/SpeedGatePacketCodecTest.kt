package kr.co.securance.secuhub.protocol

import java.time.DayOfWeek
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SpeedGatePacketCodecTest {

    @Test
    fun `프로토콜 문서 예시 - 연월일시분초 BCD 인코딩 (2020-08-11 21-47-13)`() {
        // 문서 예시: 0x20,0x08,0x11,...,0x21,0x47,0x13 = 2020-08-11, 21:47:13
        // (요일 바이트는 실제 달력 요일과 별개로 아래 테스트에서 검증)
        val dateTime = LocalDateTime.of(2020, 8, 11, 21, 47, 13)
        val encoded = SpeedGatePacketCodec.encodeDateTime(dateTime)

        assertEquals(0x20, encoded[0].toInt() and 0xFF) // Year(2020 -> 20)
        assertEquals(0x08, encoded[1].toInt() and 0xFF) // Month
        assertEquals(0x11, encoded[2].toInt() and 0xFF) // Day
        assertEquals(0x21, encoded[4].toInt() and 0xFF) // Hour
        assertEquals(0x47, encoded[5].toInt() and 0xFF) // Minute
        assertEquals(0x13, encoded[6].toInt() and 0xFF) // Second
    }

    @Test
    fun `요일 매핑이 프로토콜 요일 코드(Sunday=1 to Saturday=7)와 일치한다`() {
        // 2023-01-01(일)~01-07(토)은 정확히 한 주(Sun..Sat)이므로 매핑 검증에 사용한다.
        val base = LocalDateTime.of(2023, 1, 1, 0, 0, 0) // Sunday
        assertEquals(DayOfWeek.SUNDAY, base.dayOfWeek)

        val expected = mapOf(
            DayOfWeek.SUNDAY to SpeedGateProtocolConstants.Weekday.SUNDAY,
            DayOfWeek.MONDAY to SpeedGateProtocolConstants.Weekday.MONDAY,
            DayOfWeek.TUESDAY to SpeedGateProtocolConstants.Weekday.TUESDAY,
            DayOfWeek.WEDNESDAY to SpeedGateProtocolConstants.Weekday.WEDNESDAY,
            DayOfWeek.THURSDAY to SpeedGateProtocolConstants.Weekday.THURSDAY,
            DayOfWeek.FRIDAY to SpeedGateProtocolConstants.Weekday.FRIDAY,
            DayOfWeek.SATURDAY to SpeedGateProtocolConstants.Weekday.SATURDAY,
        )

        for (offset in 0..6) {
            val day = base.plusDays(offset.toLong())
            assertEquals(expected.getValue(day.dayOfWeek), SpeedGatePacketCodec.toProtocolWeekday(day))
        }
    }

    @Test
    fun `BCD 인코딩-디코딩 라운드트립`() {
        for (v in 0..99) {
            assertEquals(v, SpeedGatePacketCodec.fromBcd(SpeedGatePacketCodec.toBcd(v)))
        }
    }

    @Test
    fun `Device 비트마스크 인코딩 - 문서 예시(Device 1, Device 3)`() {
        assertEquals(listOf(0, 0, 0, 1), SpeedGatePacketCodec.encodeDeviceBitmask(1).map { it.toInt() and 0xFF })
        assertEquals(listOf(0, 0, 0, 4), SpeedGatePacketCodec.encodeDeviceBitmask(3).map { it.toInt() and 0xFF })
    }

    @Test
    fun `패킷 조립 후 체크섬 검증을 통과한다 (라운드트립)`() {
        val address = SpeedGatePacketCodec.buildAddress(comSlot = 1, controller = 1, deviceNumber = 1)
        val packet = SpeedGatePacketCodec.buildStatusRequestWithTimeSync(address, LocalDateTime.of(2026, 8, 5, 10, 0, 0))

        assertTrue(SpeedGatePacketCodec.verifyChecksum(packet))
        assertEquals(SpeedGateProtocolConstants.STX, packet[0])
        assertEquals(SpeedGateProtocolConstants.ETX, packet.last())
        assertEquals(SpeedGateProtocolConstants.PACKET_CHECKSUM_FIXED, packet[packet.size - 2])
    }

    @Test
    fun `조립된 패킷의 길이 필드가 실제 패킷 길이와 일치한다`() {
        val address = SpeedGatePacketCodec.buildAddress(comSlot = 1, controller = 5, deviceNumber = 3)
        val packet = SpeedGatePacketCodec.buildStatusRequestWithTimeSync(address)

        val declaredLength = ((packet[1].toInt() and 0xFF) shl 8) or (packet[2].toInt() and 0xFF)
        assertEquals(packet.size, declaredLength)
        // 문서 예시 구조: Header(27) + DataInfo(7, TimeSync) + Tail(4) = 38 byte
        assertEquals(38, packet.size)
    }

    @Test
    fun `체크섬이 변조된 패킷은 검증에 실패한다`() {
        val address = SpeedGatePacketCodec.buildAddress(comSlot = 1, controller = 1, deviceNumber = 1)
        val packet = SpeedGatePacketCodec.buildStatusRequestWithTimeSync(address).copyOf()
        packet[10] = (packet[10] + 1).toByte() // 본문 한 바이트를 변조

        assertTrue(!SpeedGatePacketCodec.verifyChecksum(packet))
    }

    @Test
    fun `payload가 헤더 길이 필드(2바이트) 표현 범위를 넘으면 buildPacket이 예외를 던진다`() {
        // totalLength = HEADER_LENGTH(27) + payload.size + TAIL_LENGTH(4)가 0xFFFF(65535)를
        // 넘도록 payload를 크게 잡는다 — 검증이 없으면 헤더의 PACKET_LENGTH 필드가 랩어라운드되어
        // 실제 패킷 크기와 다른 값을 담게 된다.
        val oversizedPayload = ByteArray(0xFFFF)

        assertFailsWith<IllegalArgumentException> {
            SpeedGatePacketCodec.buildPacket(
                address = SpeedGatePacketCodec.ZERO_ADDRESS,
                command1 = SpeedGateProtocolConstants.Command1.SEND_DATA,
                command2 = SpeedGateProtocolConstants.Command2.WRITE,
                objectCode = SpeedGateProtocolConstants.ObjectCode.GATE_SETTING,
                dataInfoLength = 0,
                dataCount = 1,
                dataLength = oversizedPayload.size,
                payload = oversizedPayload,
            )
        }
    }

    @Test
    fun `dataCount가 2바이트 표현 범위를 넘으면 buildPacket이 예외를 던진다`() {
        assertFailsWith<IllegalArgumentException> {
            SpeedGatePacketCodec.buildPacket(
                address = SpeedGatePacketCodec.ZERO_ADDRESS,
                command1 = SpeedGateProtocolConstants.Command1.SEND_DATA,
                command2 = SpeedGateProtocolConstants.Command2.WRITE,
                objectCode = SpeedGateProtocolConstants.ObjectCode.GATE_SETTING,
                dataInfoLength = 0,
                dataCount = 0x10000,
                dataLength = 0,
                payload = ByteArray(0),
            )
        }
    }
}
