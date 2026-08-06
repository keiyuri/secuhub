package kr.co.securance.secuhub.protocol

/**
 * 이전/현재 상태 패킷(Object Code = `GATE_STATUS` 0x4D)을 영역별로 비교해 변경 여부를 판단한다.
 *
 * 레거시 `ClsPacketAnalyzer.AnalyzePacketChanges`에 대응한다. 변경이 없으면 ACK만 보내고
 * DB 쓰기를 생략하는 최적화(계획서 3.4/3.5절)의 기반이 되는 순수 함수 — 부작용 없음, 테스트 용이.
 *
 * 레인(Lane) 상태 블록은 74바이트이며, 문서/레거시 분석에서 확인된 하위 구간은 다음과 같다
 * (블록 시작 오프셋 기준 상대 위치):
 * `[0..1]` 레인번호/타입, `[2..5]` 모드/보안/시간/유저카운트, `[6..9]` 총 카운트,
 * `[10..41]` 센서, `[42..49]` 출력, `[50..53]` 모터, `[54..57]` Master-In,
 * `[58..69]` 게이트 동작상태1, `[70..71]` 모터 부하, `[72..73]` 게이트 동작상태2.
 */
object PacketDiffer {

    /** 레인 1개(74바이트 상태 블록) 안에서 어떤 하위 구간이 바뀌었는지 나타낸다. */
    data class LaneStatusChange(
        val laneNumber: Int,
        val basicChanged: Boolean,
        val sensorChanged: Boolean,
        val outputChanged: Boolean,
        val motorChanged: Boolean,
        val masterInChanged: Boolean,
        val operationStatusChanged: Boolean,
    ) {
        val anyChanged: Boolean
            get() = basicChanged || sensorChanged || outputChanged || motorChanged || masterInChanged || operationStatusChanged
    }

    /** 패킷 전체 비교 결과. */
    data class PacketChangeState(
        val isFirstPacket: Boolean,
        val headerChanged: Boolean,
        val infoChanged: Boolean,
        val laneChanges: List<LaneStatusChange>,
        val tailChanged: Boolean,
    ) {
        /** 하나라도 바뀐 부분이 있으면 true — DB 쓰기 여부 판단에 사용. */
        val anyChanged: Boolean
            get() = isFirstPacket || headerChanged || infoChanged || tailChanged || laneChanges.any { it.anyChanged }
    }

    private const val LANE_COUNT_OFFSET = SpeedGateProtocolConstants.HEADER_LENGTH + 44 // Header(27)+DataInfo(45)-1
    private const val STATUS_BLOCK_START = SpeedGateProtocolConstants.HEADER_LENGTH + SpeedGateProtocolConstants.DATA_INFO_LENGTH

    /**
     * [previous]가 null이면(최초 수신) 전체를 "변경됨"으로 간주한다.
     * 두 패킷 모두 `GATE_STATUS`(0x4D) 응답 패킷이라고 가정한다.
     */
    fun diff(previous: ByteArray?, current: ByteArray): PacketChangeState {
        if (previous == null) {
            val laneCount = laneCountOf(current)
            return PacketChangeState(
                isFirstPacket = true,
                headerChanged = true,
                infoChanged = true,
                laneChanges = (1..laneCount).map { lane ->
                    LaneStatusChange(lane, true, true, true, true, true, true)
                },
                tailChanged = true,
            )
        }

        val headerChanged = !regionEquals(previous, current, 0, SpeedGateProtocolConstants.HEADER_LENGTH)
        val infoChanged = !regionEquals(
            previous, current,
            SpeedGateProtocolConstants.HEADER_LENGTH,
            SpeedGateProtocolConstants.DATA_INFO_LENGTH,
        )

        val laneCount = minOf(laneCountOf(previous), laneCountOf(current))
        val laneChanges = (0 until laneCount).map { idx ->
            val offset = STATUS_BLOCK_START + idx * SpeedGateProtocolConstants.STATUS_DATA_LENGTH
            diffLane(previous, current, offset, laneNumber = idx + 1)
        }

        val tailChanged = !regionEquals(
            previous, current,
            previous.size - SpeedGateProtocolConstants.TAIL_LENGTH,
            SpeedGateProtocolConstants.TAIL_LENGTH,
            currentOffset = current.size - SpeedGateProtocolConstants.TAIL_LENGTH,
        )

        return PacketChangeState(
            isFirstPacket = false,
            headerChanged = headerChanged,
            infoChanged = infoChanged,
            laneChanges = laneChanges,
            tailChanged = tailChanged,
        )
    }

    /**
     * 각 레인 상태 블록의 `GATE LANE NUMBER` 필드(블록 오프셋 0)를 읽어 실제 레인 번호 목록을 만든다.
     * `GateConnectionState.replaceLaneNumbers`(계획서 3.2절, `0x4D` 패킷의 authoritative 갱신)에 쓰인다.
     *
     * **경계/유효성 검증(적대적 리뷰 지적)**: 예전에는 `offset >= packet.size`만 확인해 블록의
     * 첫 바이트만 packet 범위 안이면 통과시켰고, 읽은 레인 번호 값 자체도 검증하지 않았다. `LOCAL
     * GATE LANE COUNT` 필드가 실제로 도착한 데이터보다 큰 값을 선언한 잘린/조작된 패킷이 오면
     * 블록 전체가 아니라 일부만(혹은 다른 레인 블록과 겹쳐) 읽혀 쓰레기 레인 번호가 만들어질 수
     * 있었고, 그 값이 그대로 [GateConnectionState.replaceLaneNumbers]로 들어가 레인 라우팅이 통째로
     * 망가질 수 있었다. 이제는 블록 전체(74바이트)가 실제로 packet 안에 있는지, 그리고 읽은 값이
     * 유효한 레인 번호 범위(1..MAX_LANE_COUNT) 안인지까지 확인한다.
     */
    fun laneNumbersOf(packet: ByteArray): List<Int> {
        val count = laneCountOf(packet)
        return (0 until count).mapNotNull { idx ->
            val offset = STATUS_BLOCK_START + idx * SpeedGateProtocolConstants.STATUS_DATA_LENGTH
            if (offset + SpeedGateProtocolConstants.STATUS_DATA_LENGTH > packet.size) return@mapNotNull null
            val lane = packet[offset].toInt() and 0xFF
            if (lane !in 1..SpeedGateProtocolConstants.MAX_LANE_COUNT) null else lane
        }
    }

    /** DataInfo의 `LOCAL GATE LANE COUNT` 필드(오프셋 71, 즉 Header+DataInfo의 마지막 바이트)를 읽는다. */
    fun laneCountOf(packet: ByteArray): Int {
        if (packet.size <= LANE_COUNT_OFFSET) return 0
        return (packet[LANE_COUNT_OFFSET].toInt() and 0xFF).coerceAtMost(SpeedGateProtocolConstants.MAX_LANE_COUNT)
    }

    private fun diffLane(previous: ByteArray, current: ByteArray, offset: Int, laneNumber: Int): LaneStatusChange {
        fun changed(start: Int, length: Int) = !regionEquals(previous, current, offset + start, length)

        return LaneStatusChange(
            laneNumber = laneNumber,
            basicChanged = changed(0, 10), // 레인번호/타입/모드/보안/시간/유저카운트/총카운트
            sensorChanged = changed(10, 32),
            outputChanged = changed(42, 8),
            motorChanged = changed(50, 4),
            masterInChanged = changed(54, 4),
            operationStatusChanged = changed(58, 16), // 게이트 동작상태1(12)+모터부하(2)+동작상태2(2)
        )
    }

    private fun regionEquals(
        previous: ByteArray,
        current: ByteArray,
        previousOffset: Int,
        length: Int,
        currentOffset: Int = previousOffset,
    ): Boolean {
        if (previousOffset < 0 || currentOffset < 0) return false
        if (previousOffset + length > previous.size || currentOffset + length > current.size) return false
        for (i in 0 until length) {
            if (previous[previousOffset + i] != current[currentOffset + i]) return false
        }
        return true
    }
}
