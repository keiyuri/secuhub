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
            return PacketChangeState(
                isFirstPacket = true,
                headerChanged = true,
                infoChanged = true,
                // 레인 번호는 위치 인덱스가 아니라 실제 GATE LANE NUMBER 필드값을 사용한다.
                laneChanges = laneNumbersOf(current).map { lane ->
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

        // 레인은 위치 인덱스가 아니라 실제 GATE LANE NUMBER 필드값으로 매칭한다 — 장비가 레인 순서를
        // 바꿔 보내거나(드롭 후 재등장 등) 레인 개수가 달라지는 경우, 같은 오프셋이라도 서로 다른
        // 물리 레인을 가리킬 수 있어 위치 기준 비교는 잘못된 변경 감지(오탐/누락)를 유발한다.
        val previousLaneOffsets = laneOffsetsOf(previous)
        val laneChanges = laneOffsetsOf(current).map { (laneNumber, currentOffset) ->
            val previousOffset = previousLaneOffsets[laneNumber]
            if (previousOffset == null) {
                // 이전 패킷에 없던 레인(신규 등장) — 비교 대상이 없으므로 전체 변경으로 간주한다.
                LaneStatusChange(laneNumber, true, true, true, true, true, true)
            } else {
                diffLane(previous, current, previousOffset, currentOffset, laneNumber)
            }
        }

        // Tail 4바이트 중 앞 2바이트는 체크섬(페이로드에 종속된 파생값)이라 레거시
        // `AnalyzePacketChanges`와 동일하게 비교 대상에서 제외하고, 뒤 2바이트(고정 ETX 값)만 비교한다.
        val tailChanged = !regionEquals(
            previous, current,
            previous.size - 2,
            2,
            currentOffset = current.size - 2,
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
    fun laneNumbersOf(packet: ByteArray): List<Int> = laneOffsetsOf(packet).keys.toList()

    /**
     * 실제 GATE LANE NUMBER 필드값 -> 해당 레인 블록의 오프셋(순서 보존).
     * [laneNumbersOf]와 `diff()`의 레인 매칭이 공유하는 단일 유효성 검증 기준이다.
     * [DefaultGatePacketHandler]가 레인별 `net_state` 판단(레인이 실제로 이 패킷에 유효하게
     * 보고됐는지)에도 재사용하므로 public이다.
     */
    fun laneOffsetsOf(packet: ByteArray): Map<Int, Int> {
        val count = laneCountOf(packet)
        return (0 until count).mapNotNull { idx ->
            val offset = STATUS_BLOCK_START + idx * SpeedGateProtocolConstants.STATUS_DATA_LENGTH
            if (offset + SpeedGateProtocolConstants.STATUS_DATA_LENGTH > packet.size) return@mapNotNull null
            val lane = packet[offset].toInt() and 0xFF
            if (lane !in 1..SpeedGateProtocolConstants.MAX_LANE_COUNT) null else lane to offset
        }.toMap()
    }

    /** DataInfo의 `LOCAL GATE LANE COUNT` 필드(오프셋 71, 즉 Header+DataInfo의 마지막 바이트)를 읽는다. */
    fun laneCountOf(packet: ByteArray): Int {
        if (packet.size <= LANE_COUNT_OFFSET) return 0
        return (packet[LANE_COUNT_OFFSET].toInt() and 0xFF).coerceAtMost(SpeedGateProtocolConstants.MAX_LANE_COUNT)
    }

    /**
     * 레인 상태 블록(74바이트) 안의 센서 값이 실제로 "연결"되어 있는지 판단한다.
     *
     * 레거시 `ClsPacketAnalyzer.IsNotConnected`에 대응하며, 부호만 뒤집었다(반환값이 "연결 여부"
     * 자체를 뜻하도록). 게이트가 레인 슬롯을 계속 보고해도([laneOffsetsOf]에 잡혀도) 물리 센서가
     * 분리되면 이 블록의 레인번호(byte 0)/에러코드(byte 41)를 제외한 나머지 72바이트가 전부 0으로
     * 온다 — 단순히 "레인이 패킷에 보고됐는지"만으로는 이런 경우를 놓쳐 `net_state`(대시보드
     * 온라인 여부)가 실제로는 끊긴 레인을 계속 온라인으로 표시하게 된다([DefaultGatePacketHandler] 참고).
     *
     * 판정 기준(레거시와 동일):
     * - 나머지 72바이트 중 하나라도 0이 아니면 → 연결.
     * - 나머지 72바이트가 모두 0이고 에러코드(byte 41)가 0이면 → 미연결.
     * - 나머지 72바이트가 모두 0이고 에러코드가 3 이상이면 → 미연결.
     * - 나머지 72바이트가 모두 0이고 에러코드가 1 또는 2면 → 연결(에러 상태만 있는 경우).
     *
     * 블록이 패킷 범위를 벗어나면(파싱 오류) 레거시와 동일하게 안전한 기본값인 "연결"을 반환한다
     * — 파싱 오류만으로 레인을 섣불리 오프라인 처리하지 않기 위함.
     */
    fun isLaneConnected(packet: ByteArray, blockOffset: Int): Boolean {
        if (blockOffset < 0 || blockOffset + SpeedGateProtocolConstants.STATUS_DATA_LENGTH > packet.size) return true

        var restAllZero = true
        for (i in 1..40) {
            if (packet[blockOffset + i] != 0.toByte()) {
                restAllZero = false
                break
            }
        }
        if (restAllZero) {
            for (i in 42..73) {
                if (packet[blockOffset + i] != 0.toByte()) {
                    restAllZero = false
                    break
                }
            }
        }
        if (!restAllZero) return true

        val errorCode = packet[blockOffset + 41].toInt() and 0xFF
        return errorCode in 1..2
    }

    private fun diffLane(
        previous: ByteArray,
        current: ByteArray,
        previousOffset: Int,
        currentOffset: Int,
        laneNumber: Int,
    ): LaneStatusChange {
        fun changed(start: Int, length: Int) =
            !regionEquals(previous, current, previousOffset + start, length, currentOffset + start)

        return LaneStatusChange(
            laneNumber = laneNumber,
            basicChanged = changed(0, 10), // 레인번호/타입/모드/보안/시간/유저카운트/총카운트
            sensorChanged = changed(10, 32),
            outputChanged = changed(42, 8),
            motorChanged = changed(50, 4),
            masterInChanged = changed(54, 4),
            // 게이트 동작상태1(12) + 동작상태2(2)만 비교한다. 모터 부하값(70..71)은 레거시
            // `ClsPacketAnalyzer.CheckStatusSubSections`와 동일하게 의도적으로 제외 — 부하값은
            // 실시간으로 자주 변동해 이를 포함하면 불필요한 DB 쓰기가 급증한다.
            operationStatusChanged = changed(58, 12) || changed(72, 2),
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
