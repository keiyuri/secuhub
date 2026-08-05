package kr.co.securance.secuhub.protocol

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpeedGatePacketReassemblerTest {

    private fun samplePacket(): ByteArray {
        val address = SpeedGatePacketCodec.buildAddress(comSlot = 1, controller = 1, deviceNumber = 1)
        return SpeedGatePacketCodec.buildStatusRequestWithTimeSync(address, LocalDateTime.of(2026, 8, 5, 9, 0, 0))
    }

    @Test
    fun `한 번에 도착한 패킷 1개를 그대로 추출한다`() {
        val reassembler = SpeedGatePacketReassembler()
        val packet = samplePacket()

        val extracted = reassembler.append(packet)

        assertEquals(1, extracted.size)
        assertTrue(packet.contentEquals(extracted[0]))
        assertEquals(0, reassembler.pendingByteCount())
    }

    @Test
    fun `패킷이 여러 조각으로 나뉘어 도착해도 재조립한다`() {
        val reassembler = SpeedGatePacketReassembler()
        val packet = samplePacket()

        val firstHalf = packet.copyOfRange(0, 10)
        val secondHalf = packet.copyOfRange(10, packet.size)

        assertEquals(0, reassembler.append(firstHalf).size)
        val extracted = reassembler.append(secondHalf)

        assertEquals(1, extracted.size)
        assertTrue(packet.contentEquals(extracted[0]))
    }

    @Test
    fun `연속된 패킷 2개가 한 번에 도착해도 각각 추출한다`() {
        val reassembler = SpeedGatePacketReassembler()
        val packet1 = samplePacket()
        val packet2 = samplePacket()

        val extracted = reassembler.append(packet1 + packet2)

        assertEquals(2, extracted.size)
        assertTrue(packet1.contentEquals(extracted[0]))
        assertTrue(packet2.contentEquals(extracted[1]))
    }

    @Test
    fun `STX 앞에 쓰레기 바이트가 섞여 있어도 재동기화한다`() {
        val reassembler = SpeedGatePacketReassembler()
        val garbage = byteArrayOf(0x11, 0x22, 0x33)
        val packet = samplePacket()

        val extracted = reassembler.append(garbage + packet)

        assertEquals(1, extracted.size)
        assertTrue(packet.contentEquals(extracted[0]))
    }

    @Test
    fun `길이 필드가 손상된 가짜 STX는 건너뛰고 다음 진짜 패킷을 찾는다`() {
        val reassembler = SpeedGatePacketReassembler()
        val packet = samplePacket()
        // STX(0x02)로 시작하지만 길이 필드가 최소 패킷 길이(31)보다 작은 잡음
        val fakeStx = byteArrayOf(0x02, 0x00, 0x05, 0x11, 0x22)

        val extracted = reassembler.append(fakeStx + packet)

        assertEquals(1, extracted.size)
        assertTrue(packet.contentEquals(extracted[0]))
    }

    @Test
    fun `Tail 바이트가 깨진 가짜 STX는 건너뛰고 다음 진짜 패킷을 찾는다`() {
        val reassembler = SpeedGatePacketReassembler()
        val packet = samplePacket()

        // 길이 필드는 유효 범위(31~65536) 안이지만 Tail(0x08,0x03)이 깨진 가짜 패킷(40바이트).
        // 본문에 0x02(STX와 동일값)가 섞이지 않도록 0x01로만 채워 재동기화 경로만 검증한다.
        val fakeLength = 40
        val fake = ByteArray(fakeLength) { 0x01 }
        fake[0] = SpeedGateProtocolConstants.STX
        fake[1] = ((fakeLength ushr 8) and 0xFF).toByte()
        fake[2] = (fakeLength and 0xFF).toByte()
        fake[fakeLength - 2] = 0x00 // PACKET_CHECKSUM_FIXED(0x08)이어야 하지만 손상시킴
        fake[fakeLength - 1] = SpeedGateProtocolConstants.ETX

        val extracted = reassembler.append(fake + packet)

        assertEquals(1, extracted.size)
        assertTrue(packet.contentEquals(extracted[0]))
    }
}
