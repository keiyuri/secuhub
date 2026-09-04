package kr.co.securance.secuhub.protocol

/**
 * 상태 패킷(Object Code `GATE_STATUS` 0x4D)의 레인 상태 블록(74바이트)을 분석해
 * `tb_data_rcv_anal` 적재용 값으로 변환한다.
 *
 * ## 이식 원본
 * 레거시에서 이 분석은 **애플리케이션이 아니라 DB 트리거**가 수행했다
 * (`utrg_data_rcv_anlz` — `tb_data_rcv` AFTER INSERT 트리거가 `SUBSTR`/`CONV`로 hex 문자열을
 * 잘라 `tb_data_rcv_anal`에 INSERT). 트리거 방식은 (1) 단위 테스트가 불가능하고,
 * (2) 고정 오프셋만 사용해 **1번 레인만 분석**되며(다중 레인 장비의 2번 이상 레인 장애가
 * 영원히 기록되지 않음), (3) 수신 INSERT 트랜잭션 안에서 동기 실행되어 쓰기 지연을 키웠다.
 *
 * 이 클래스는 같은 규칙을 순수 함수로 옮기되 **레인 블록마다 반복 적용**해 다중 레인을
 * 정상 처리한다. 트리거의 hex 문자 위치(`SUBSTR(vRcvData, n, len)`)와 여기의 바이트 오프셋은
 * `byteOffset = (n - 1) / 2` 관계로 1:1 대응한다.
 *
 * ## 레인 상태 블록(74바이트) 구조
 * ```
 *  +0        레인 번호           +1        게이트 타입
 *  +2        운영 모드           +3        보안 등급
 *  +4        통행 시간           +5        사용자 수
 *  +6..9     누적 통행 수(4)
 *  +10..13   운영 센서1(4)       +14..17  안전 센서(4)
 *  +18..21   운영 센서2(4)       +22..41  광 센서(20, 마지막 바이트 +41이 ERROR CHECK)
 *  +42..49   출력 상태(8)        +50..53  모터 카운트(4)
 *  +54..57   Master-In 카운트(4) +58..73  게이트 동작 상태(16)
 * ```
 */
object GateStatusAnalyzer {

    /** 레인 상태 블록 안의 필드 오프셋(블록 시작 기준). */
    object StatusOffset {
        const val LANE_NUMBER = 0
        const val GATE_TYPE = 1
        const val USER_MODE = 2
        const val SECURITY_MODE = 3
        const val INOUT_TIME = 4
        const val USER_COUNT = 5
        const val TOTAL_COUNT = 6 // 4 byte
        const val OPERATION_SENSOR1 = 10 // 4 byte
        const val SAFETY_SENSOR = 14 // 4 byte
        const val OPERATION_SENSOR2 = 18 // 4 byte
        const val OPTICAL_SENSOR = 22 // 20 byte

        /**
         * ERROR CHECK 바이트(광 센서 블록의 마지막). 트리거 `SUBSTR(vRcvData,227,2)`와 동일 위치이며
         * `ClsPacketAnalyzer.IsNotConnected`가 "에러 코드"로 읽던 `data[blockOffset + 41]`과도 같다.
         */
        const val ERROR_CHECK = 41
        const val OUTPUT_STATUS = 42 // 8 byte
        const val MOTOR_COUNT = 50 // 4 byte
        const val MASTER_IN_COUNT = 54 // 4 byte
        const val OPERATION_STATUS = 58 // 16 byte

        // 게이트 동작 상태(16바이트) 내부 — 모두 OPERATION_STATUS 기준 절대 오프셋으로 표기한다.
        const val BACK_RUSH = 58
        const val TAIL_GATING = 59
        const val WAITING = 60
        const val ANTI_PASS = 61
        const val OPEN_CLOSE = 62
        const val REMOCON = 63
        const val FIRE_ALARM = 64
        const val AC_POWER_RESET = 65
        const val SUB_BOARD_ERROR = 66
        const val MAIN_MOTOR_ERROR = 67
        const val SUB_MOTOR_ERROR = 68
        const val EMERGENCY = 69
    }

    /** ERROR CHECK 바이트 값의 의미(트리거 주석: "3이면 Error, 1이면 Active, 2면 inactive, 9는 event"). */
    object ErrorCheck {
        const val NORMAL = 0
        const val ACTIVE = 1
        const val INACTIVE = 2
        const val ERROR = 3
        const val EVENT = 9
    }

    /** 센서/모터 계열 필드는 값 3이 "장애"를 뜻한다. */
    private const val FAULT = 3

    /** 이벤트 계열 필드는 값 1이 "발생"을 뜻한다. */
    private const val OCCURRED = 1

    /** 값이 없는 항목은 공백 1칸으로 채운다 — 레거시 트리거의 `ELSE ' '`와 동일(생성 컬럼 계산 호환). */
    private const val NONE = " "

    /** `tb_data_rcv_anal.anal_tp` 분류값. */
    enum class AnalysisType {
        /** 장애(센서/모터/서브보드 오류). */
        PLM,

        /** 이벤트(역주행/꼬리물기/화재/비상 등). */
        EVT,

        /** 상태 변경(개폐). */
        STA,

        /** 정상. */
        NOR,
    }

    /** 레인 1개의 분석 결과 — `tb_data_rcv_anal` 한 행에 대응한다. */
    data class LaneStatusAnalysis(
        val laneNumber: Int,
        val gateType: Int,
        val userMode: Int,
        val securityMode: Int,
        val inoutTime: Int,
        val userCount: Int,
        val totalCount: Long,
        val motorCount: Long,
        val masterInTotal: Long,
        val errType: Int,
        val analysisType: AnalysisType,
        /** 운영 센서1 4채널 장애(S00~S03). */
        val descOperation: List<String>,
        /** 안전 센서 4채널 장애(S04~S07). */
        val descSafety: List<String>,
        /** 운영 센서2 4채널 장애(S08~S11). */
        val descOperation2: List<String>,
        /** `desc_gate_status01`~`12`에 1:1 대응(인덱스 0 = status01). */
        val descGateStatus: List<String>,
        /** 게이트 동작 상태 블록(16바이트) 원본 hex — `anal_data` 보존용. */
        val operationStatusHex: String,
        /**
         * `tb_data_rcv_anal.anal_data_*` 중 레인 상태 블록(74바이트)에서 그대로 뽑아낸 원시 hex
         * 필드(2026-08-18 — dev DB information_schema 조회로 발견한 19개 미매핑 컬럼 대응).
         * [descOperation] 등 decoded 값과 별개로, 레거시 `usp_process_analysis`가 raw byte를
         * hex로 저장하던 규약을 그대로 따른다(anal_data_stx 등 헤더 파생 필드와 동일한 규약).
         */
        val rawFields: AnalDataLaneRawFields,
    ) {
        /** 장애가 미해결 상태인지(레거시: `err_type = 3`이면 `resolve_yn='N'`). */
        val resolveYn: String get() = if (errType == ErrorCheck.ERROR) "N" else "Y"

        /** 이 레인에 기록할 만한 장애/이벤트가 있는지 — `has_status_event`/`has_error_event` 생성 컬럼과 같은 의미. */
        val hasAnyDescription: Boolean
            get() = (descOperation + descSafety + descOperation2 + descGateStatus).any { it.isNotBlank() }

        val descFireAlarm: String get() = descGateStatus[6] // desc_gate_status07
        val descMainMotorError: String get() = descGateStatus[9] // desc_gate_status10
        val descSlaveMotorError: String get() = descGateStatus[10] // desc_gate_status11
    }

    /** [LaneStatusAnalysis.rawFields] — 필드별 원본 hex. [StatusOffset]과 1:1 대응한다. */
    data class AnalDataLaneRawFields(
        val laneNumber: String,
        val gateType: String,
        val userMode: String,
        val securityMode: String,
        val inoutTime: String,
        val userCount: String,
        val totalCount: String,
        val operationSensor1: String,
        val safetySensor: String,
        val operationSensor2: String,
        val opticalSensor: String,
        val outputStatus: String,
        val motorCount: String,
        val masterInCount: String,
        val operationStatus: String,
    )

    /**
     * 상태 패킷에서 모든 레인 블록을 분석한다.
     *
     * 레인 수는 DataInfo의 `LOCAL GATE LANE COUNT`(오프셋 71)에서 읽으며, 패킷이 잘려 블록을
     * 온전히 읽을 수 없으면 그 레인부터는 건너뛴다(예외를 던지지 않는다 — 수신 경로에서
     * 예외가 나면 정상 레인의 분석까지 통째로 유실되기 때문).
     */
    fun analyze(packet: ByteArray): List<LaneStatusAnalysis> {
        val laneCount = PacketDiffer.laneCountOf(packet)
        if (laneCount <= 0) return emptyList()

        val blockStart = SpeedGateProtocolConstants.HEADER_LENGTH + SpeedGateProtocolConstants.DATA_INFO_LENGTH
        return (0 until laneCount).mapNotNull { index ->
            val offset = blockStart + index * SpeedGateProtocolConstants.STATUS_DATA_LENGTH
            analyzeLane(packet, offset)
        }
    }

    /** 지정한 오프셋의 레인 블록 1개를 분석한다. 범위를 벗어나면 null. */
    fun analyzeLane(packet: ByteArray, blockOffset: Int): LaneStatusAnalysis? {
        // [Codex 리뷰 지적] blockOffset이 Int.MAX_VALUE 근처면 `blockOffset + STATUS_DATA_LENGTH`가
        // 오버플로되어 음수가 되고, 검증을 통과해버린다. 뺄셈 형태로 바꿔 오버플로 없이 검증한다.
        if (blockOffset < 0 || blockOffset > packet.size - SpeedGateProtocolConstants.STATUS_DATA_LENGTH) return null

        fun u8(relative: Int): Int = packet[blockOffset + relative].toInt() and 0xFF
        fun u32(relative: Int): Long {
            var value = 0L
            for (i in 0 until 4) value = (value shl 8) or u8(relative + i).toLong()
            return value
        }
        fun hex(relative: Int, len: Int): String = buildString {
            for (i in 0 until len) append("%02X".format(u8(relative + i)))
        }

        val userMode = u8(StatusOffset.USER_MODE)

        // ── 센서 장애 12채널(S00~S11) ─────────────────────────────────
        // 트리거의 SUBSTR 165/167/.../187과 동일하게 4+4+4채널을 순서대로 읽는다.
        val descOperation = (0 until 4).map { sensorLabel(u8(StatusOffset.OPERATION_SENSOR1 + it), it) }
        val descSafety = (0 until 4).map { sensorLabel(u8(StatusOffset.SAFETY_SENSOR + it), 4 + it) }
        val descOperation2 = (0 until 4).map { sensorLabel(u8(StatusOffset.OPERATION_SENSOR2 + it), 8 + it) }

        // ── 게이트 동작 상태 12항목(desc_gate_status01~12) ─────────────
        val descGateStatus = listOf(
            flag(u8(StatusOffset.BACK_RUSH) == OCCURRED, "BACK RUSH(역방향 진입) 발생"),
            flag(u8(StatusOffset.TAIL_GATING) == OCCURRED, "TAIL GATING(뒤따름) 발생"),
            flag(u8(StatusOffset.WAITING) == OCCURRED, "WAITING(진입 후 장시간 대기) 발생"),
            flag(u8(StatusOffset.ANTI_PASS) == OCCURRED, "ANTI-PASS(인증 진입 후 되돌아나옴) 발생"),
            // status05(일반 개폐)는 레거시 통합 INSERT에서 주석 처리되어 항상 공백이 들어간다.
            // 상시 발생하는 개폐를 이벤트로 적재하면 분석 테이블이 폭증했기 때문으로 보이며,
            // 화면(uvw_anlz_event)도 공백을 전제로 동작하므로 그대로 둔다.
            NONE,
            remoconLabel(userMode, u8(StatusOffset.REMOCON)),
            flag(u8(StatusOffset.FIRE_ALARM) == OCCURRED, "FIRE ALARM"),
            flag(u8(StatusOffset.AC_POWER_RESET) == OCCURRED, "AC POWER RESET"),
            flag(u8(StatusOffset.SUB_BOARD_ERROR) == FAULT, "SUB BOARD COMMUNICATION ERROR"),
            flag(u8(StatusOffset.MAIN_MOTOR_ERROR) == FAULT, "MAIN MOTOR ERROR"),
            flag(u8(StatusOffset.SUB_MOTOR_ERROR) == FAULT, "SUB MOTOR ERROR"),
            flag(u8(StatusOffset.EMERGENCY) == OCCURRED, "EMERGENCY"),
        )

        val operationStatusHex = hex(StatusOffset.OPERATION_STATUS, 16)

        val rawFields = AnalDataLaneRawFields(
            laneNumber = hex(StatusOffset.LANE_NUMBER, 1),
            gateType = hex(StatusOffset.GATE_TYPE, 1),
            userMode = hex(StatusOffset.USER_MODE, 1),
            securityMode = hex(StatusOffset.SECURITY_MODE, 1),
            inoutTime = hex(StatusOffset.INOUT_TIME, 1),
            userCount = hex(StatusOffset.USER_COUNT, 1),
            totalCount = hex(StatusOffset.TOTAL_COUNT, 4),
            operationSensor1 = hex(StatusOffset.OPERATION_SENSOR1, 4),
            safetySensor = hex(StatusOffset.SAFETY_SENSOR, 4),
            operationSensor2 = hex(StatusOffset.OPERATION_SENSOR2, 4),
            opticalSensor = hex(StatusOffset.OPTICAL_SENSOR, 20),
            outputStatus = hex(StatusOffset.OUTPUT_STATUS, 8),
            motorCount = hex(StatusOffset.MOTOR_COUNT, 4),
            masterInCount = hex(StatusOffset.MASTER_IN_COUNT, 4),
            operationStatus = operationStatusHex,
        )

        return LaneStatusAnalysis(
            laneNumber = u8(StatusOffset.LANE_NUMBER),
            gateType = u8(StatusOffset.GATE_TYPE),
            userMode = userMode,
            securityMode = u8(StatusOffset.SECURITY_MODE),
            inoutTime = u8(StatusOffset.INOUT_TIME),
            userCount = u8(StatusOffset.USER_COUNT),
            totalCount = u32(StatusOffset.TOTAL_COUNT),
            // unsigned 32비트 카운터(최대 4,294,967,295)라 Int로 좁히면 값이 음수로 래핑될 수
            // 있다(Codex 리뷰 P2, 2026-09-04) — u32()가 이미 Long이므로 그대로 보존한다.
            motorCount = u32(StatusOffset.MOTOR_COUNT),
            masterInTotal = u32(StatusOffset.MASTER_IN_COUNT),
            errType = u8(StatusOffset.ERROR_CHECK),
            analysisType = classify(packet, blockOffset),
            descOperation = descOperation,
            descSafety = descSafety,
            descOperation2 = descOperation2,
            descGateStatus = descGateStatus,
            operationStatusHex = operationStatusHex,
            rawFields = rawFields,
        )
    }

    /**
     * `anal_tp` 분류 — 레거시 통합 INSERT(`utrg_data_rcv_anlz`)의 `CASE` 식을 **평가 순서까지
     * 그대로** 옮겼다. 앞선 조건이 뒤 조건을 가리는 구조라 순서가 곧 우선순위다.
     *
     * 코드 리뷰 지적(2026-08-14): 현재 유일한 호출부인 [analyzeLane]은 호출 전에 이미
     * [blockOffset]을 검증하지만, 이 함수 자체는 모듈의 공개 API라 범위를 벗어난 오프셋이
     * 들어오면(짧거나 손상된 상태 패킷을 다른 경로에서 직접 넘기는 경우 등) 방어 없이
     * `ArrayIndexOutOfBoundsException`을 던졌다. [analyzeLane]과 동일한 기준으로 검증해,
     * 범위를 벗어나면 예외 대신 안전한 기본값([AnalysisType.NOR])을 반환한다.
     *
     * [Codex 리뷰 지적] `blockOffset + STATUS_DATA_LENGTH > packet.size` 형태의 덧셈 비교는
     * blockOffset이 Int.MAX_VALUE 근처인 극단값일 때 오버플로로 음수가 되어 검증을 우회한다.
     * 뺄셈 형태(`blockOffset > packet.size - STATUS_DATA_LENGTH`)로 바꿔 오버플로 없이 검증한다.
     */
    fun classify(packet: ByteArray, blockOffset: Int): AnalysisType {
        if (blockOffset < 0 || blockOffset > packet.size - SpeedGateProtocolConstants.STATUS_DATA_LENGTH) {
            return AnalysisType.NOR
        }

        fun u8(relative: Int): Int = packet[blockOffset + relative].toInt() and 0xFF

        // 1순위: 센서 12채널 중 하나라도 장애(3) → PLM
        for (i in StatusOffset.OPERATION_SENSOR1 until StatusOffset.OPTICAL_SENSOR) {
            if (u8(i) == FAULT) return AnalysisType.PLM
        }
        // 2순위: 통행 이벤트 4종
        if (u8(StatusOffset.BACK_RUSH) == OCCURRED) return AnalysisType.EVT
        if (u8(StatusOffset.TAIL_GATING) == OCCURRED) return AnalysisType.EVT
        if (u8(StatusOffset.WAITING) == OCCURRED) return AnalysisType.EVT
        if (u8(StatusOffset.ANTI_PASS) == OCCURRED) return AnalysisType.EVT
        // 3순위: 개폐 상태 변경 → STA, 리모컨 조작 → EVT
        if (u8(StatusOffset.OPEN_CLOSE) == OCCURRED) return AnalysisType.STA
        if (u8(StatusOffset.REMOCON) == OCCURRED) return AnalysisType.EVT
        // 4순위: 화재/전원/비상 이벤트와 보드·모터 장애
        if (u8(StatusOffset.FIRE_ALARM) == OCCURRED) return AnalysisType.EVT
        if (u8(StatusOffset.AC_POWER_RESET) == OCCURRED) return AnalysisType.EVT
        if (u8(StatusOffset.SUB_BOARD_ERROR) == FAULT) return AnalysisType.PLM
        if (u8(StatusOffset.MAIN_MOTOR_ERROR) == FAULT) return AnalysisType.PLM
        if (u8(StatusOffset.SUB_MOTOR_ERROR) == FAULT) return AnalysisType.PLM
        if (u8(StatusOffset.EMERGENCY) == OCCURRED) return AnalysisType.EVT
        return AnalysisType.NOR
    }

    /** 게이트 타입 코드 → 표시 문자열(트리거 `desc_gate_type`). */
    fun describeGateType(code: Int): String = when (code) {
        1 -> "SR-1400"
        2 -> "Flap"
        3 -> "Turn"
        4 -> "Fast"
        else -> code.toString()
    }

    /** 운영 모드 코드 → 표시 문자열(트리거 `desc_user_mode`). */
    fun describeUserMode(code: Int): String = when (code) {
        1 -> "IN(CARD)/OUT(CARD)"
        2 -> "IN(CARD)/OUT(FREE)"
        3 -> "IN(FREE)/OUT(CARD)"
        4 -> "IN(FREE)/OUT(FREE)"
        5 -> "OPEN"
        6 -> "CLOSED"
        7 -> "IN(CARD)/OUT(CLOSED)"
        8 -> "IN(CLOSED)/OUT(CARD)"
        9 -> "IN(FREE)/OUT(CLOSED)"
        10 -> "IN(CLOSED)/OUT(FREE)"
        else -> code.toString()
    }

    /** 보안 등급 코드 → 표시 문자열(트리거 `desc_security_mode`). */
    fun describeSecurityMode(code: Int): String =
        SpeedGateSecurityMode.ofValue(code)?.description ?: code.toString()

    /** 센서 채널 장애 라벨 — 트리거와 동일하게 `S00`~`S11` 형식. */
    private fun sensorLabel(value: Int, channelIndex: Int): String =
        if (value == FAULT) "S%02d".format(channelIndex) else NONE

    /**
     * 리모컨 조작 라벨(`desc_gate_status06`).
     * 트리거는 운영 모드가 OPEN(5)/CLOSED(6)일 때만 리모컨 조작으로 해석한다.
     */
    private fun remoconLabel(userMode: Int, remoconValue: Int): String {
        val lowNibble = remoconValue and 0x0F
        return when {
            userMode == 5 && lowNibble == 1 -> "REMOCON OPEN"
            userMode == 6 && lowNibble == 0 -> "REMOCON CLOSE"
            else -> NONE
        }
    }

    private fun flag(condition: Boolean, label: String): String = if (condition) label else NONE
}
