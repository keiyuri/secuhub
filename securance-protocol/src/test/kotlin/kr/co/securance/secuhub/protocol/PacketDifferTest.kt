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
}
