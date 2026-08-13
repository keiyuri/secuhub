package kr.co.securance.secuhub.protocol

/**
 * #12 SR_P_GateModeChange / #13 SR_P_GateSetupMotor 제어 명령 패킷 빌더.
 *
 * 레거시 `SR_C_DataHandler.BuildPacket`/`GenerateCmdBody`/`SetControlMotor`(Brian 보드 분기)를
 * 바이트 단위로 그대로 이식한다 — 계획서 Phase 4, 2026-08-06 사용자 확인: "레거시 그대로 재현".
 * 구형(Moon) 보드 분기는 포팅하지 않는다. 이 시스템의 `tb_gate_dtl`에는 보드 타입 컬럼 자체가 없고
 * (`GateDetail` 엔티티 참고), [SpeedGatePacketCodec] 역시 신형(Brian) 포맷만 다루므로 실 배치 대상이
 * 전부 Brian 보드라는 전제와 일치한다.
 *
 * [주의 — 의도적으로 "결함"까지 재현함] 레거시 원본은 다음을 하지 않는다:
 * - Header의 Address(offset 6~18, 13바이트)를 전혀 채우지 않는다(0으로 남음). 게이트 라우팅은
 *   TCP 접속(IP)과 [SpeedGateProtocolConstants] 기준 레인 비트마스크가 아니라, 이 웹의
 *   `GateConnectionRegistry.sendToLane(ip, laneNo, packet)`가 소켓 자체로 처리하므로 문제되지 않는다.
 * - Data Info Length(offset 22), Data Count 상위바이트(offset 23)를 설정하지 않는다(0으로 남음).
 * - Data Length(offset 25~26)를 실제로 계산하지 않고 offset 26에 `0x5D` 고정값을 넣는다(원본 버그로
 *   보이지만, 실기기가 이미 이 형식을 그대로 받아 처리 중이므로 "고쳐서" 보내면 오히려 호환성이
 *   깨질 위험이 있다 — 사용자 확인 하에 그대로 재현).
 * 즉 [SpeedGatePacketCodec.buildPacket]과는 헤더 조립 방식이 다르다. 두 빌더를 섞어 쓰지 않는다.
 */
object GateControlCommandBuilder {

    private const val CMD1_SEND_DATA: Byte = 0x05
    private const val CMD2_WRITE: Byte = 0x03
    private const val OBJECT_MODE: Byte = 0x4C // Speed Gate Data('L')
    private const val OBJECT_MOTOR: Byte = 0x4B // Speed Gate Motor Data('K')
    private const val OBJECT_TIME: Byte = 0x54 // Time Zone('T') — #7/#15(Phase 5) SetTimeData 대응

    /** 레거시 `ControlMode` enum 대응(운영모드 바이트 값). */
    object ControlMode {
        const val NORMAL: Byte = 0x01
        const val CARD_FREE: Byte = 0x02
        const val FREE_CARD: Byte = 0x03
        const val FREE_FREE: Byte = 0x04
        const val OPEN: Byte = 0x05
        const val CLOSURE: Byte = 0x06
        const val CARD_CLOSURE: Byte = 0x07
        const val CLOSURE_CARD: Byte = 0x08
        const val FREE_CLOSURE: Byte = 0x09
        const val CLOSURE_FREE: Byte = 0x0A
        const val REVERSE_OPEN: Byte = 0x15
    }

    /** #13 모터 파라미터(메인/서브 각각). 값은 0~255(byte 범위)만 허용 — 레거시 NumericUpDown과 동일 전제. */
    data class MotorParams(
        val initSpeed: Int = 0,
        val initCount: Int = 0,
        val openSpeed: Int = 0,
        val openCount: Int = 0,
        val closeSpeed: Int = 0,
        val closeCount: Int = 0,
    ) {
        init {
            for (v in listOf(initSpeed, initCount, openSpeed, openCount, closeSpeed, closeCount)) {
                require(v in 0..255) { "모터 파라미터 값은 0~255 범위여야 합니다: $v" }
            }
        }
    }

    /**
     * 레거시 `BuildPacket`(Brian 분기)을 그대로 포팅 — Header(27) + payload + Tail(4).
     * Address(offset 6~18)는 레거시와 동일하게 전부 0으로 둔다.
     */
    private fun buildPacket(command1: Byte, command2: Byte, objectCode: Byte, payload: ByteArray): ByteArray {
        val header = ByteArray(27)
        header[0] = 0x02 // STX
        header[3] = 0x04 // Version
        header[4] = 0x80.toByte() // Frame Option1
        header[5] = 0x40 // Frame Option2
        header[19] = command1
        header[20] = command2
        header[21] = objectCode
        header[24] = 0x01 // Data Count(low) — 레거시 원본 그대로, 상위바이트/DataInfoLength는 미설정
        header[26] = 0x5D // Data Length(low) — 레거시 원본 그대로(고정값, 실제 길이 계산 없음)

        val body = header + payload
        val totalLength = body.size + 4
        body[1] = ((totalLength ushr 8) and 0xFF).toByte()
        body[2] = (totalLength and 0xFF).toByte()

        var xor = 0
        var sum = 0
        for (b in body) {
            val v = b.toInt() and 0xFF
            xor = xor xor v
            sum = (sum + v) and 0xFF
        }
        val tail = byteArrayOf(xor.toByte(), sum.toByte(), 0x08, 0x03)
        return body + tail
    }

    /**
     * #12 GateModeChange 명령을 만든다(레거시 `GenerateCmdBody`, 93바이트 바디).
     *
     * @param laneNo 레인 번호(0~255) — 2026-07-28 확정 규칙대로 바이트 값 그대로 인코딩한다.
     *   **주의**: [SpeedGatePacketCodec.buildControlCommand]는 같은 "레인 번호" 개념에 1~32
     *   ([SpeedGateProtocolConstants.MAX_LANE_COUNT])만 허용한다 — 이쪽(레거시 모드변경/모터설정
     *   화면 재현 경로)과는 범위가 다르니 두 빌더의 laneNo 검증 기대치를 섞지 않는다.
     * @param controlType 레거시 `sUserMode + sSecuMode` 조합 문자열(예: "CCLM"). 앞 2글자가 운영모드
     *   코드(CC/CF/FC/FF/OP/RP/CL/CS/CX/XC/FX/XF, 그 외는 Normal), 문자열 어디든 "LM"/"MM"/"HM"이
     *   포함되면 보안모드로 반영된다(레거시와 동일하게 `Contains` 기반 판정).
     * @param timeDataUser 사용자 시간대 3바이트(스케줄 미선택 시 null — Phase 5 TimeZone 엔티티
     *   도입 전까지는 항상 null로 호출된다).
     * @param timeDataSecu 보안 시간대 3바이트(위와 동일).
     */
    fun buildModeChangeCommand(
        laneNo: Int,
        controlType: String,
        timeDataUser: ByteArray? = null,
        timeDataSecu: ByteArray? = null,
    ): ByteArray {
        require(laneNo in 0..255) { "레인 번호는 0~255 범위여야 합니다: $laneNo" }
        val body = ByteArray(93)
        body[0] = laneNo.toByte()

        val ctrlTp1 = if (controlType.length >= 2) controlType.substring(0, 2).uppercase() else controlType.uppercase()
        body[1] = when (ctrlTp1) {
            "CF" -> ControlMode.CARD_FREE
            "FC" -> ControlMode.FREE_CARD
            "FF" -> ControlMode.FREE_FREE
            "OP" -> ControlMode.OPEN
            "RP" -> ControlMode.REVERSE_OPEN
            "CL", "CS" -> ControlMode.CLOSURE
            "CX" -> ControlMode.CARD_CLOSURE
            "XC" -> ControlMode.CLOSURE_CARD
            "FX" -> ControlMode.FREE_CLOSURE
            "XF" -> ControlMode.CLOSURE_FREE
            else -> ControlMode.NORMAL
        }

        when {
            controlType.contains("LM") -> body[2] = 0x01
            controlType.contains("MM") -> body[2] = 0x02
            controlType.contains("HM") -> body[2] = 0x03
        }

        timeDataUser?.let {
            require(it.size >= 3) { "timeDataUser는 최소 3바이트여야 합니다" }
            System.arraycopy(it, 0, body, 3, 3)
        }
        timeDataSecu?.let {
            require(it.size >= 3) { "timeDataSecu는 최소 3바이트여야 합니다" }
            System.arraycopy(it, 0, body, 6, 3)
        }

        when (ctrlTp1) {
            "AC" -> body[20] = 0x01
            "OS" -> body[20] = 0x02
            "SS" -> body[20] = 0x03
            "MT" -> body[20] = 0x04
            "MB" -> body[20] = 0x05
        }

        return buildPacket(CMD1_SEND_DATA, CMD2_WRITE, OBJECT_MODE, body)
    }

    /**
     * #13 GateSetupMotor 명령을 만든다(레거시 `bMotor` 17바이트 배열 레이아웃).
     * index 0=레인번호, 1~6=메인모터(초기속도/초기카운트/오픈속도/오픈카운트/클로즈속도/클로즈카운트),
     * 7~8=레거시 원본에서도 채워지지 않는 미사용 영역, 9~14=서브모터(동일 순서), 15~16=미사용.
     */
    fun buildMotorSetupCommand(laneNo: Int, main: MotorParams, sub: MotorParams): ByteArray {
        require(laneNo in 0..255) { "레인 번호는 0~255 범위여야 합니다: $laneNo" }
        val body = ByteArray(17)
        body[0] = laneNo.toByte()
        body[1] = main.initSpeed.toByte()
        body[2] = main.initCount.toByte()
        body[3] = main.openSpeed.toByte()
        body[4] = main.openCount.toByte()
        body[5] = main.closeSpeed.toByte()
        body[6] = main.closeCount.toByte()
        body[9] = sub.initSpeed.toByte()
        body[10] = sub.initCount.toByte()
        body[11] = sub.openSpeed.toByte()
        body[12] = sub.openCount.toByte()
        body[13] = sub.closeSpeed.toByte()
        body[14] = sub.closeCount.toByte()

        return buildPacket(CMD1_SEND_DATA, CMD2_WRITE, OBJECT_MOTOR, body)
    }

    /**
     * #7/#15(Phase 5) 타임존 동기화 명령을 만든다(레거시 `SetTimeData`, Object Code 0x54).
     * @param timezoneData [TimeZoneCommandBuilder.buildTimezoneHexData]가 만든 26바이트 페이로드.
     */
    fun buildTimeSyncCommand(timezoneData: ByteArray): ByteArray =
        buildPacket(CMD1_SEND_DATA, CMD2_WRITE, OBJECT_TIME, timezoneData)
}
