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

    @Test
    fun `길이 필드가 MIN_PACKET_LENGTH 미만이면 손상 패킷으로 재동기화한다`() {
        val reassembler = SpeedGatePacketReassembler()
        val packet = samplePacket()
        // MIN_PACKET_LENGTH(31) 바로 아래(30) 경계값.
        val boundary = SpeedGateProtocolConstants.MIN_PACKET_LENGTH - 1
        val fakeStx = byteArrayOf(
            SpeedGateProtocolConstants.STX,
            ((boundary ushr 8) and 0xFF).toByte(),
            (boundary and 0xFF).toByte(),
        )

        val extracted = reassembler.append(fakeStx + packet)

        assertEquals(1, extracted.size)
        assertTrue(packet.contentEquals(extracted[0]))
    }

    @Test
    fun `하드 캡을 넘겨도 그 안에 파묻힌 정상 패킷은 폐기되지 않고 살아남는다`() {
        // 적대적 리뷰 지적 회귀 테스트: 예전에는 하드 캡 초과 시 buffer 전체를 폐기해, 유효 범위
        // 안의 길이를 선언한 가짜 STX 뒤에 이미 도착해있던 정상 패킷까지 함께 날아갔다.
        val maxBufferSize = 100
        val reassembler = SpeedGatePacketReassembler(maxBufferSize = maxBufferSize)
        val realPacket = samplePacket()

        // 유효 범위(31~65536) 안이지만 실제로는 절대 채워지지 않을 큰 길이를 선언하는 가짜 STX(3바이트) —
        // 재조립기가 "완성 대기" 상태에 들어가게 만든다.
        val stalledLength = 64_000
        val fakeStx = byteArrayOf(
            SpeedGateProtocolConstants.STX,
            ((stalledLength ushr 8) and 0xFF).toByte(),
            (stalledLength and 0xFF).toByte(),
        )

        // 가짜 STX 바로 뒤에 정상 패킷이 이미 도착해 있다 — 재조립기는 아직 이걸 정상 패킷으로
        // 인식하지 못한 채(가짜 STX의 길이 대기 중) 그냥 누적만 한다.
        assertEquals(0, reassembler.append(fakeStx + realPacket).size)
        assertTrue(reassembler.pendingByteCount() <= maxBufferSize, "이 시점에는 아직 하드 캡을 넘지 않아야 한다.")

        // 잡음을 더 흘려보내 하드 캡을 넘긴다.
        val filler = ByteArray(maxBufferSize) { 0x01 }
        reassembler.append(filler)
        // 정확히 가짜 STX의 3바이트 헤더만 버려지고, 그 뒤에 파묻혀 있던 정상 패킷과 잡음은 그대로
        // 남아야 한다(전량 폐기도, 무제한 누적도 아님) — 복구된 나머지가 이 테스트의 작은 cap(100)보다
        // 커질 수 있다는 것 자체는 정상이다(cap은 "복구 후 크기 보장"이 아니라 "무한 누적 방지"가 목적).
        assertEquals(
            realPacket.size + filler.size,
            reassembler.pendingByteCount(),
            "가짜 STX 헤더(3바이트)만 버려지고 나머지는 보존되어야 합니다.",
        )

        // 다음 append에서 파묻혀 있던 정상 패킷이 정상적으로 추출되어야 한다.
        val recovered = reassembler.append(ByteArray(0))
        assertEquals(1, recovered.size)
        assertTrue(realPacket.contentEquals(recovered[0]), "하드 캡 초과 이후에도 파묻힌 정상 패킷을 복구해야 합니다.")
    }

    @Test
    fun `재동기화할 STX가 전혀 없는 순수 잡음은 하드 캡 초과 시 전량 폐기된다`() {
        val maxBufferSize = 50
        val reassembler = SpeedGatePacketReassembler(maxBufferSize = maxBufferSize)

        // STX(0x02)가 단 하나도 섞이지 않은 순수 잡음.
        reassembler.append(ByteArray(maxBufferSize + 10) { 0x01 })

        assertEquals(0, reassembler.pendingByteCount(), "복구할 STX가 없으면 전량 폐기해야 합니다.")
    }

    @Test
    fun `빈 chunk는 안전하게 무시된다`() {
        val reassembler = SpeedGatePacketReassembler()

        assertEquals(0, reassembler.append(ByteArray(0)).size)
        assertEquals(0, reassembler.pendingByteCount())

        // 버퍼에 미완성 데이터가 있는 상태에서 빈 chunk가 와도 기존 상태를 그대로 유지해야 한다.
        val packet = samplePacket()
        reassembler.append(packet.copyOfRange(0, 10))
        assertEquals(0, reassembler.append(ByteArray(0)).size)
        assertEquals(10, reassembler.pendingByteCount())
    }

    @Test
    fun `1바이트씩 스트리밍되어도 결국 재조립된다`() {
        val reassembler = SpeedGatePacketReassembler()
        val packet = samplePacket()

        val extracted = mutableListOf<ByteArray>()
        for (b in packet) {
            extracted += reassembler.append(byteArrayOf(b))
        }

        assertEquals(1, extracted.size)
        assertTrue(packet.contentEquals(extracted[0]))
    }

    @Test
    fun `append에 넘긴 배열을 이후에 변형해도 재조립기 내부 상태에 영향을 주지 않는다`() {
        // 적대적 리뷰 지적(aliasing): 버퍼가 비어있을 때 chunk를 복사 없이 그대로 참조하면, 호출자가
        // (예: 풀링된 배열을 재사용해) 그 배열을 나중에 덮어쓸 때 재조립기 내부 상태까지 조용히
        // 오염된다. copyOf()로 방어했는지 검증한다.
        val reassembler = SpeedGatePacketReassembler()
        val packet = samplePacket()
        val mutableChunk = packet.copyOfRange(0, 10)

        assertEquals(0, reassembler.append(mutableChunk).size)
        mutableChunk.fill(0x00) // 호출자가 청크 버퍼를 재사용/변형.

        val extracted = reassembler.append(packet.copyOfRange(10, packet.size))
        assertEquals(1, extracted.size)
        assertTrue(packet.contentEquals(extracted[0]), "호출자의 배열 변형이 재조립기 내부 상태를 오염시켰습니다.")
    }
}
