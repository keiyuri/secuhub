package kr.co.securance.secuhub.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PacketDifferTest {

    /** DataInfo(45) + laneCount*Status(74)로 구성된 가상의 GATE_STATUS 응답 패킷을 만든다(Tail 포함). */
    private fun fakeStatusPacket(
        laneCount: Int,
        laneNumberAt: (lane: Int) -> Int = { it }, // 기본은 위치와 동일(1,2,3...); 실제 레인 번호를 다르게 주고 싶을 때 오버라이드
        sensorByteAt: (lane: Int) -> Byte,
    ): ByteArray {
        val address = SpeedGatePacketCodec.buildAddress(comSlot = 1, controller = 1, deviceNumber = 1)
        val dataInfo = ByteArray(SpeedGateProtocolConstants.DATA_INFO_LENGTH)
        dataInfo[dataInfo.size - 1] = laneCount.toByte() // LOCAL GATE LANE COUNT (DataInfo 마지막 바이트)

        val laneBlocks = ByteArray(laneCount * SpeedGateProtocolConstants.STATUS_DATA_LENGTH)
        for (lane in 1..laneCount) {
            val offset = (lane - 1) * SpeedGateProtocolConstants.STATUS_DATA_LENGTH
            laneBlocks[offset] = laneNumberAt(lane).toByte() // GATE LANE NUMBER
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
    fun `레인 번호가 블록 위치와 다르면(예 2,4번 레인) 실제 GATE LANE NUMBER 필드값을 사용한다`() {
        val actualLaneNumbers = listOf(2, 4)
        val previous = fakeStatusPacket(laneCount = 2, sensorByteAt = { 0x01 }, laneNumberAt = { actualLaneNumbers[it - 1] })
        val current = fakeStatusPacket(
            laneCount = 2,
            sensorByteAt = { pos -> if (pos == 2) 0x02 else 0x01 }, // 두 번째 블록(실제 레인 4)만 변경
            laneNumberAt = { actualLaneNumbers[it - 1] },
        )

        val result = PacketDiffer.diff(previous, current)

        assertEquals(listOf(2, 4), result.laneChanges.map { it.laneNumber })
        assertFalse(result.laneChanges[0].anyChanged) // 실제 레인 2
        assertTrue(result.laneChanges[1].sensorChanged) // 실제 레인 4
    }

    @Test
    fun `최초 패킷도 위치 인덱스가 아닌 실제 GATE LANE NUMBER 필드값을 laneNumber로 사용한다`() {
        val actualLaneNumbers = listOf(3, 1)
        val current = fakeStatusPacket(laneCount = 2, sensorByteAt = { 0x01 }, laneNumberAt = { actualLaneNumbers[it - 1] })

        val result = PacketDiffer.diff(previous = null, current = current)

        assertEquals(listOf(3, 1), result.laneChanges.map { it.laneNumber })
    }

    @Test
    fun `레인 순서가 이전 패킷과 다르게 와도 위치가 아닌 실제 레인 번호로 매칭해서 비교한다`() {
        // previous: 위치1=레인4, 위치2=레인2   /   current: 위치1=레인2, 위치2=레인4(센서만 변경)
        val previous = fakeStatusPacket(laneCount = 2, sensorByteAt = { 0x01 }, laneNumberAt = { pos -> listOf(4, 2)[pos - 1] })
        val current = fakeStatusPacket(
            laneCount = 2,
            sensorByteAt = { pos -> if (pos == 2) 0x02 else 0x01 }, // 위치2(=레인4)만 실제로 값이 바뀜
            laneNumberAt = { pos -> listOf(2, 4)[pos - 1] },
        )

        val result = PacketDiffer.diff(previous, current)
        val byLane = result.laneChanges.associateBy { it.laneNumber }

        // 위치 기준으로 비교했다면 위치1(레인2 vs 레인4)이 다른 것으로 오탐되었을 것이다.
        assertFalse(byLane.getValue(2).anyChanged)
        assertTrue(byLane.getValue(4).sensorChanged)
    }

    @Test
    fun `이전 패킷에 없던 레인이 새로 나타나면 매칭 대상이 없으므로 전체 변경으로 간주한다`() {
        val previous = fakeStatusPacket(laneCount = 1, sensorByteAt = { 0x01 }, laneNumberAt = { 1 })
        val current = fakeStatusPacket(laneCount = 2, sensorByteAt = { 0x01 }, laneNumberAt = { it })

        val result = PacketDiffer.diff(previous, current)
        val byLane = result.laneChanges.associateBy { it.laneNumber }

        assertFalse(byLane.getValue(1).anyChanged) // 기존 레인은 그대로
        assertTrue(byLane.getValue(2).anyChanged) // 신규 레인은 전체 변경
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

    @Test
    fun `레인 동작상태 블록의 모터 부하 바이트(70,71)만 바뀌면 변경으로 취급하지 않는다`() {
        val previous = fakeStatusPacket(laneCount = 1) { 0x01 }
        val current = previous.copyOf()

        // STATUS_BLOCK_START(레인 블록 시작) + 70, +71 = 모터 부하값 2바이트.
        // 레거시 ClsPacketAnalyzer.CheckStatusSubSections와 동일하게 비교 대상에서 제외되어야 한다.
        val statusBlockStart = SpeedGateProtocolConstants.HEADER_LENGTH + SpeedGateProtocolConstants.DATA_INFO_LENGTH
        current[statusBlockStart + 70] = (current[statusBlockStart + 70] + 1).toByte()
        current[statusBlockStart + 71] = (current[statusBlockStart + 71] + 1).toByte()

        val result = PacketDiffer.diff(previous, current)

        assertFalse(result.anyChanged)
        assertFalse(result.laneChanges[0].operationStatusChanged)
    }

    @Test
    fun `레인 동작상태1 12바이트가 바뀌면 operationStatusChanged로 표시한다`() {
        val previous = fakeStatusPacket(laneCount = 1) { 0x01 }
        val current = previous.copyOf()

        val statusBlockStart = SpeedGateProtocolConstants.HEADER_LENGTH + SpeedGateProtocolConstants.DATA_INFO_LENGTH
        current[statusBlockStart + 58] = (current[statusBlockStart + 58] + 1).toByte()

        val result = PacketDiffer.diff(previous, current)

        assertTrue(result.laneChanges[0].operationStatusChanged)
    }

    @Test
    fun `Tail의 체크섬 바이트(앞 2바이트)만 바뀌면 tailChanged로 취급하지 않는다`() {
        val previous = fakeStatusPacket(laneCount = 1) { 0x01 }
        val current = previous.copyOf()

        // Tail 4바이트 중 앞 2바이트는 체크섬. 레거시와 동일하게 비교에서 제외되어야 한다.
        val checksumOffset = current.size - SpeedGateProtocolConstants.TAIL_LENGTH
        current[checksumOffset] = (current[checksumOffset] + 1).toByte()

        val result = PacketDiffer.diff(previous, current)

        assertFalse(result.tailChanged)
    }

    @Test
    fun `Tail의 뒤 2바이트(고정 ETX)가 바뀌면 tailChanged로 표시한다`() {
        val previous = fakeStatusPacket(laneCount = 1) { 0x01 }
        val current = previous.copyOf()

        val lastByteOffset = current.size - 1
        current[lastByteOffset] = (current[lastByteOffset] + 1).toByte()

        val result = PacketDiffer.diff(previous, current)

        assertTrue(result.tailChanged)
    }
}
