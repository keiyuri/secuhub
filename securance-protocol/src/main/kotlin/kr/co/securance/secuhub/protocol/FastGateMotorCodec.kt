package kr.co.securance.secuhub.protocol

/**
 * Fast Gate 전용 모터 설정/조회 코덱 — Object Code `FAST_GATE_MOTOR`(0x50)
 * ([SpeedGateProtocolConstants.ObjectCode.FAST_GATE_MOTOR] 참고).
 *
 * `FastGate Protocol Ver1_2020102601_01.md`("■ Set/Request FAST GATE MOTOR" 절) 기준으로
 * 구현했다. 이 Object Code는 레거시 `SR_Speed_Client`/`SR_Speed_Server`(C#) 어디에도 대응하는
 * 코드가 없다 — Fast Gate 자체가 레거시 GUI에 없던 신규 게이트 타입이라 "레거시 그대로 재현"
 * 원칙을 적용할 대상이 없다. 따라서 [GateControlCommandBuilder]의 레거시(Brian 보드) 헤더
 * 조립 방식이 아니라, 문서 스펙을 정확히 따르는 [SpeedGatePacketCodec.buildPacket]을 그대로 쓴다
 * (다른 신규 명령인 [SpeedGatePacketCodec.buildStatusRequestWithTimeSync]와 동일한 선택).
 *
 * **미검증 — 실장비로 확인되지 않았다.** 아래 인코딩은 전부 문서 서술을 그대로 옮긴 것이며, 문서
 * 자체에 애매한 부분이 있다:
 * - 3단계(Position/Rpm/Compensation) 필드는 "Length 2 (byte)"로 명시돼 있으면서 값 설명은
 *   `0xFF : 변화 없음`(1바이트 표기)으로 적혀 있어, "변화 없음" 센티널이 실제로는 2바이트
 *   `0xFFFF`인지 문서 오기인지 판단할 근거가 없다 — 이 코덱은 필드 길이(2바이트) 쪽을 정본으로
 *   삼아 값을 그대로 big-endian UInt16으로 인코딩하고, 센티널 판단은 호출자에게 맡긴다(강제하지
 *   않음).
 * - Turn/Slide `INIT SPEED`는 "+:CW -:CCW"로 부호가 있는 값이라 명시돼 있어 [Int]를 그대로
 *   부호 있는 16비트로 인코딩한다(다른 필드와 다르게 부호 있음에 주의).
 */
object FastGateMotorCodec {

    /** 모터 3단계(Open/Zero-Close/추가단) 파라미터 — Turn/Slide 모터 각각 3세트. */
    data class MotorStage(
        val position: Int,
        val rpm: Int,
        val compensation: Int,
    ) {
        init {
            for (v in listOf(position, rpm, compensation)) {
                require(v in 0..0xFFFF) { "모터 단계 값은 0~65535 범위여야 합니다: $v" }
            }
        }

        internal fun encodeTo(body: ByteArray, offset: Int) {
            writeU16(body, offset, position)
            writeU16(body, offset + 2, rpm)
            writeU16(body, offset + 4, compensation)
        }
    }

    /** Turn 또는 Slide 모터 하나(3단계 + 초기속도)의 전체 파라미터. */
    data class MotorAxis(
        val stage1: MotorStage,
        val stage2: MotorStage,
        val stage3: MotorStage,
        /** 초기속도(부호 있음) — "+:CW(시계방향) -:CCW(반시계방향)". */
        val initSpeed: Int = 0,
    ) {
        init {
            require(initSpeed in -0x8000..0x7FFF) { "initSpeed는 부호 있는 16비트 범위여야 합니다: $initSpeed" }
        }
    }

    /** Master/Slave 구분(문서 "Master / Slave 구분" 필드). */
    enum class MasterSlave(val code: Byte) {
        MASTER(0x01),
        SLAVE(0x02),
    }

    /** [buildSetCommand]의 전체 파라미터. */
    data class Params(
        val masterSlave: MasterSlave,
        val turn: MotorAxis,
        val slide: MotorAxis,
        /** Auto Close Time( x 10ms). 0=Disable. */
        val autoCloseTimeMs10: Int = 0,
        /** Auto Test Time( x 10ms). 0=Disable. */
        val autoTestTimeMs10: Int = 0,
        val loofExitUsed: Boolean = false,
        val openTurnDelayTimeMs10: Int = 0,
        val closedSlideDelayTimeMs10: Int = 0,
    ) {
        init {
            for (v in listOf(autoCloseTimeMs10, autoTestTimeMs10, openTurnDelayTimeMs10, closedSlideDelayTimeMs10)) {
                require(v in 0..0xFFFF) { "시간 값은 0~65535 범위여야 합니다: $v" }
            }
        }
    }

    private const val BODY_LENGTH = 72

    // 오프셋(본문 시작 기준) — 문서 "■ Set FAST GATE MOTOR" 표 순서 그대로.
    private const val OFF_LANE_NUMBER = 0
    private const val OFF_MASTER_SLAVE = 1
    private const val OFF_TURN_STAGE1 = 2
    private const val OFF_TURN_STAGE2 = 8
    private const val OFF_TURN_STAGE3 = 14
    // OFF_TURN_STAGE3(14) + 6바이트 = 20, 이후 6바이트 Turn Reserved → Slide 시작은 26.
    private const val OFF_SLIDE_STAGE1 = 26
    private const val OFF_SLIDE_STAGE2 = 32
    private const val OFF_SLIDE_STAGE3 = 38
    // Slide Reserved(6) → 50.
    private const val OFF_TURN_INIT_SPEED = 50
    private const val OFF_SLIDE_INIT_SPEED = 52
    private const val OFF_AUTO_CLOSE_TIME = 54
    private const val OFF_AUTO_TEST_TIME = 56
    private const val OFF_LOOF_EXIT_USED = 58
    private const val OFF_OPEN_TURN_DELAY = 59
    private const val OFF_CLOSED_SLIDE_DELAY = 61
    // Motor Reserved(9) → 63..71, 합계 72바이트.

    private fun writeU16(body: ByteArray, offset: Int, value: Int) {
        body[offset] = ((value ushr 8) and 0xFF).toByte()
        body[offset + 1] = (value and 0xFF).toByte()
    }

    private fun encodeAxis(body: ByteArray, stageBase1: Int, stageBase2: Int, stageBase3: Int, initSpeedOffset: Int, axis: MotorAxis) {
        axis.stage1.encodeTo(body, stageBase1)
        axis.stage2.encodeTo(body, stageBase2)
        axis.stage3.encodeTo(body, stageBase3)
        writeU16(body, initSpeedOffset, axis.initSpeed and 0xFFFF)
    }

    /**
     * Fast Gate 모터 설정 명령(72바이트 본문)을 만든다.
     * `Command=SendData(0x05)/Write(0x03)`, `ObjectCode=FAST_GATE_MOTOR(0x50)`, `DataCount=1`,
     * `DataLength=72`.
     *
     * @param laneNo 대상 레인 번호(1~32).
     */
    fun buildSetCommand(laneNo: Int, params: Params): ByteArray {
        require(laneNo in 1..SpeedGateProtocolConstants.MAX_LANE_COUNT) { "laneNo 범위 오류: $laneNo" }

        val body = ByteArray(BODY_LENGTH)
        body[OFF_LANE_NUMBER] = laneNo.toByte()
        body[OFF_MASTER_SLAVE] = params.masterSlave.code
        encodeAxis(body, OFF_TURN_STAGE1, OFF_TURN_STAGE2, OFF_TURN_STAGE3, OFF_TURN_INIT_SPEED, params.turn)
        encodeAxis(body, OFF_SLIDE_STAGE1, OFF_SLIDE_STAGE2, OFF_SLIDE_STAGE3, OFF_SLIDE_INIT_SPEED, params.slide)
        writeU16(body, OFF_AUTO_CLOSE_TIME, params.autoCloseTimeMs10)
        writeU16(body, OFF_AUTO_TEST_TIME, params.autoTestTimeMs10)
        body[OFF_LOOF_EXIT_USED] = if (params.loofExitUsed) 0x01 else 0x00
        writeU16(body, OFF_OPEN_TURN_DELAY, params.openTurnDelayTimeMs10)
        writeU16(body, OFF_CLOSED_SLIDE_DELAY, params.closedSlideDelayTimeMs10)
        // Turn/Slide/Motor Reserved 구간은 body 초기값(0x00, 문서 "0x00 : Default")을 그대로 둔다.

        return SpeedGatePacketCodec.buildPacket(
            address = SpeedGatePacketCodec.ZERO_ADDRESS,
            command1 = SpeedGateProtocolConstants.Command1.SEND_DATA,
            command2 = SpeedGateProtocolConstants.Command2.WRITE,
            objectCode = SpeedGateProtocolConstants.ObjectCode.FAST_GATE_MOTOR,
            dataInfoLength = 0,
            dataCount = 1,
            dataLength = BODY_LENGTH,
            payload = body,
        )
    }

    /**
     * Fast Gate 모터 조회 명령을 만든다(문서 "■ REQUEST FAST GATE MOTOR").
     * `Command=RequestData(0x06)/Read(0x02)`, `ObjectCode=FAST_GATE_MOTOR(0x50)`, 본문은
     * 레인 번호 1바이트 × N개.
     */
    fun buildRequestCommand(laneNumbers: List<Int>): ByteArray {
        require(laneNumbers.isNotEmpty()) { "laneNumbers는 최소 1개 이상이어야 합니다" }
        // 적대적 리뷰 지적(2026-08-13): 장비는 레인을 최대 MAX_LANE_COUNT개만 가지므로, 그보다 긴
        // 목록(중복 포함)은 API 경계에서 거부한다 — 이전에는 범위(1~32) 검사만 하고 개수는 제한하지
        // 않아, 중복을 채운 대량 목록이 buildPacket의 16비트 dataCount/dataLength를 오버플로해
        // 헤더와 실제 본문 길이가 어긋나는 패킷을 만들 수 있었다.
        require(laneNumbers.size <= SpeedGateProtocolConstants.MAX_LANE_COUNT) {
            "laneNumbers는 최대 ${SpeedGateProtocolConstants.MAX_LANE_COUNT}개까지만 허용됩니다: ${laneNumbers.size}"
        }
        require(laneNumbers.toSet().size == laneNumbers.size) { "laneNumbers에 중복된 레인 번호가 있습니다: $laneNumbers" }
        for (lane in laneNumbers) {
            require(lane in 1..SpeedGateProtocolConstants.MAX_LANE_COUNT) { "laneNo 범위 오류: $lane" }
        }
        val body = ByteArray(laneNumbers.size) { laneNumbers[it].toByte() }

        return SpeedGatePacketCodec.buildPacket(
            address = SpeedGatePacketCodec.ZERO_ADDRESS,
            command1 = SpeedGateProtocolConstants.Command1.REQUEST_DATA,
            command2 = SpeedGateProtocolConstants.Command2.READ,
            objectCode = SpeedGateProtocolConstants.ObjectCode.FAST_GATE_MOTOR,
            dataInfoLength = 0,
            dataCount = laneNumbers.size,
            dataLength = 1,
            payload = body,
        )
    }
}
