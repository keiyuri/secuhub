package kr.co.securance.secuhub.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PacketDifferTest {

    /** DataInfo(45) + laneCount*Status(74)로 구성된 가상의 GATE_STATUS 응답 패킷을 만든다(Tail 포함). */
    private fun fakeStatusPacket(laneCount: Int, sensorByteAt: (lane: Int) -> Byte): ByteArray {
        val address = SpeedGatePacketCodec.buildAddress(comSlot = 1, controller = 1, deviceNumber = 1)
        val dataInfo = ByteArray(SpeedGateProtocolConstants.DATA_INFO_LENGTH)
        dataInfo[dataInfo.size - 1] = laneCount.toByte() // LOCAL GATE LANE COUNT (DataInfo 마지막 바이트)

        val laneBlocks = ByteArray(laneCount * SpeedGateProtocolConstants.STATUS_DATA_LENGTH)
        for (lane in 1..laneCount) {
            val offset = (lane - 1) * SpeedGateProtocolConstants.STATUS_DATA_LENGTH
            laneBlocks[offset] = lane.toByte() // GATE LANE NUMBER
            laneBlocks[offset + 10] = sensorByteAt(lane) // 센서 구간(오프셋 10부터) 첫 바이트
        }

        return SpeedGatePacketCodec.buildPacket(
            address = address,
            command1 = SpeedGateProtocolConstants.Command1.REQUEST_ACK,
            command2 = SpeedGateProtocolConstants.Command2.READ,
            objectCode = SpeedGateProtocolConstants.ObjectCode.GATE_STATUS,
            dataInfoLength = SpeedGateProtocolConstants.DATA_INFO_LENGTH,
            dataCount = laneCount,
            dataLength = SpeedGateProtocolConstants.STATUS_DATA_LENGTH,
            payload = dataInfo + laneBlocks,
        )
    }

    @Test
    fun `최초 패킷(previous=null)은 전체가 변경된 것으로 간주한다`() {
        val current = fakeStatusPacket(laneCount = 2) { 0x01 }
        val result = PacketDiffer.diff(previous = null, current = current)

        assertTrue(result.isFirstPacket)
        assertTrue(result.anyChanged)
        assertEquals(2, result.laneChanges.size)
        assertTrue(result.laneChanges.all { it.anyChanged })
    }

    @Test
    fun `동일한 패킷은 변경 없음으로 판단한다`() {
        val packet = fakeStatusPacket(laneCount = 3) { 0x01 }
        val result = PacketDiffer.diff(previous = packet, current = packet.copyOf())

        assertFalse(result.anyChanged)
        assertTrue(result.laneChanges.all { !it.anyChanged })
    }

    @Test
    fun `특정 레인의 센서 바이트만 바뀌면 해당 레인만 변경으로 표시한다`() {
        val previous = fakeStatusPacket(laneCount = 3) { 0x01 }
        val current = fakeStatusPacket(laneCount = 3) { lane -> if (lane == 2) 0x02 else 0x01 }

        val result = PacketDiffer.diff(previous, current)

        assertTrue(result.anyChanged)
        assertFalse(result.laneChanges[0].anyChanged) // lane 1
        assertTrue(result.laneChanges[1].sensorChanged) // lane 2
        assertFalse(result.laneChanges[2].anyChanged) // lane 3
    }

    @Test
    fun `laneNumbersOf는 정상 패킷에서 레인 번호를 순서대로 읽는다`() {
        val packet = fakeStatusPacket(laneCount = 3) { 0x01 }

        assertEquals(listOf(1, 2, 3), PacketDiffer.laneNumbersOf(packet))
    }

    @Test
    fun `laneNumbersOf는 레인 블록이 중간에 잘린 패킷에서 잘린 블록 이후는 무시한다`() {
        // 적대적 리뷰 지적 회귀 테스트: 예전에는 블록의 첫 바이트만 packet 범위 안이면 통과시켜,
        // LOCAL GATE LANE COUNT가 실제 도착한 데이터보다 큰 잘린 패킷에서 블록 일부만 읽힌 쓰레기
        // 레인 번호가 만들어질 수 있었다.
        val fullPacket = fakeStatusPacket(laneCount = 3) { 0x01 }
        // 마지막 레인 블록(74바이트)의 앞 10바이트만 남기고 나머지는 잘라낸다(실제로는 있을 수 없는,
        // 조작/손상된 패킷을 흉내낸다).
        val truncated = fullPacket.copyOfRange(0, fullPacket.size - SpeedGateProtocolConstants.STATUS_DATA_LENGTH + 10)

        assertEquals(listOf(1, 2), PacketDiffer.laneNumbersOf(truncated))
    }

    @Test
    fun `laneNumbersOf는 유효 범위(1부터 MAX_LANE_COUNT까지) 밖의 레인 번호를 걸러낸다`() {
        // 손상/조작된 GATE LANE NUMBER 필드(예: 0 또는 33 이상)가 그대로 replaceLaneNumbers로
        // 들어가면 레인 라우팅이 통째로 망가질 수 있다.
        val packet = fakeStatusPacket(laneCount = 2) { 0x01 }
        packet[SpeedGateProtocolConstants.HEADER_LENGTH + SpeedGateProtocolConstants.DATA_INFO_LENGTH] = 0 // lane 1 자리 -> 0
        val secondLaneOffset = SpeedGateProtocolConstants.HEADER_LENGTH + SpeedGateProtocolConstants.DATA_INFO_LENGTH +
            SpeedGateProtocolConstants.STATUS_DATA_LENGTH
        packet[secondLaneOffset] = (SpeedGateProtocolConstants.MAX_LANE_COUNT + 1).toByte() // lane 2 자리 -> 33

        assertEquals(emptyList(), PacketDiffer.laneNumbersOf(packet))
    }
}
