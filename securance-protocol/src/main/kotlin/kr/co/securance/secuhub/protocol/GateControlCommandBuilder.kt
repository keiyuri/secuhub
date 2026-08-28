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
 * - (#12 모드변경에 한함) Data Length(offset 25~26)를 실제로 계산하지 않고 offset 26에 `0x5D`(93)
 *   고정값을 넣는다 — 실기기가 이미 이 형식을 그대로 받아 처리 중이므로 "고쳐서" 보내면 호환성이
 *   깨질 위험이 있어 사용자 확인 하에 그대로 재현한다. 다만 93은 모드변경 페이로드가 정확히
 *   93바이트인 것과 정확히 일치한다 — 즉 "정체불명 버그"가 아니라 실제 길이를 그대로 담고 있을
 *   가능성이 높다(코드 리뷰 재검토, 2026-08-28). #13(모터설정, 17바이트 페이로드)과
 *   [buildTimeSyncCommand](#7/#15, 26바이트)는 이 고정값을 쓰지 않고 각자의 실제 payload 크기를
 *   계산해 넣는다 — 헤더 Data Length가 실제 payload와 다르면 이를 신뢰하는 수신측의 패킷 경계
 *   파싱이 어긋날 수 있기 때문이다(`buildPacket`의 `dataLength` 파라미터 참고).
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

    /** #12(모드변경) 레거시가 실제로 채워 보낸 고정값(93) — 아래 [buildPacket] `dataLength` 기본값. */
    private const val LEGACY_FIXED_DATA_LENGTH = 0x5D

    /**
     * 레거시 `BuildPacket`(Brian 분기)을 그대로 포팅 — Header(27) + payload + Tail(4).
     * Address(offset 6~18)는 레거시와 동일하게 전부 0으로 둔다.
     *
     * @param dataLength Data Length(offset 26)에 넣을 값. 기본값은 #12(모드변경)에서 사용자 확인
     *   하에 재현하기로 한 레거시 고정값(93=0x5D, `LEGACY_FIXED_DATA_LENGTH`)이다 — 대응 legacy
     *   원본(`GenerateCmdBody`)이 실측으로 이 고정값을 그대로 내보내는 것이 확인됐고, 공교롭게도
     *   93은 모드변경 페이로드의 실제 크기와 정확히 같다. #13(모터설정)/[buildTimeSyncCommand](#7/#15)는
     *   각자 실제 payload 크기를 명시적으로 넘긴다(코드 리뷰 재검토, 2026-08-28) — 그렇지 않으면
     *   Data Length를 신뢰하는 수신측의 패킷 경계 파싱이 어긋날 수 있다.
     */
    private fun buildPacket(
        command1: Byte,
        command2: Byte,
        objectCode: Byte,
        payload: ByteArray,
        dataLength: Int = LEGACY_FIXED_DATA_LENGTH,
    ): ByteArray {
        // Opus 재검증 지적(2026-08-28): Data Length는 offset 25~26의 2바이트 필드인데 기존 코드는
        // header[26](하위 바이트)만 채우고 header[25](상위 바이트)는 항상 0으로 남겨뒀다 — 256 이상
        // payload가 생기면 상위 바이트 누락으로 값이 어긋나고, and 0xFF로 인해 초과분이 조용히
        // 잘렸다. SpeedGatePacketCodec.buildPacket이 이미 같은 필드를 require로 막고 있어 두
        // 빌더의 안전장치를 맞춘다.
        require(dataLength in 0..0xFFFF) { "dataLength는 0..65535 범위여야 합니다: $dataLength" }
        val header = ByteArray(27)
        header[0] = 0x02 // STX
        header[3] = 0x04 // Version
        header[4] = 0x80.toByte() // Frame Option1
        header[5] = 0x40 // Frame Option2
        header[19] = command1
        header[20] = command2
        header[21] = objectCode
        header[24] = 0x01 // Data Count(low) — 레거시 원본 그대로, 상위바이트/DataInfoLength는 미설정
        header[25] = ((dataLength ushr 8) and 0xFF).toByte() // Data Length(high)
        header[26] = (dataLength and 0xFF).toByte() // Data Length(low)

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

        // 코드 리뷰 지적(2026-08-28): 대소문자 무관("Contains 기반 판정")을 계약으로 문서화해놓고
        // 정작 여기서는 정규화 전 원본 controlType을 검사했다 — 위 ctrlTp1(운영모드)은 uppercase()를
        // 거치는데 보안등급 판정만 빠져 있어, 소문자 controlType으로 호출하면 운영모드는 정상
        // 반영되고 보안등급만 조용히 누락되는 불일치가 생겼다. 유일한 현재 호출부(GateControlController)가
        // 호출 전 이미 uppercase()를 적용해 우연히 가려져 있었을 뿐이다.
        val normalizedControlType = controlType.uppercase()
        when {
            normalizedControlType.contains("LM") -> body[2] = 0x01
            normalizedControlType.contains("MM") -> body[2] = 0x02
            normalizedControlType.contains("HM") -> body[2] = 0x03
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
     *
     * Data Length(offset 26)에는 #12(모드변경)의 레거시 고정값(93) 대신 실제 payload 크기(17)를
     * 담는다(코드 리뷰 재검토, 2026-08-28 사용자 확인) — 93은 모드변경 페이로드가 정확히 93바이트인
     * 것과 정확히 일치해, "정체불명 고정값"이 아니라 실제로는 모드변경용 길이가 모터설정에 그대로
     * 복붙된 것으로 보인다. 헤더 Data Length가 실제 payload와 다르면 이를 신뢰하는 수신측의 패킷
     * 경계 파싱이 어긋날 수 있다는 논리(원래 타임싱크 수정과 동일)가 여기에도 적용되므로, 사용자
     * 확인을 거쳐 실제 길이를 쓰도록 바꾼다. 클래스 KDoc의 "#12/#13 모두 실측 확인된 고정값 재현"
     * 문구는 #12(모드변경)에 대해서만 유효한 것으로 정정한다.
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

        return buildPacket(CMD1_SEND_DATA, CMD2_WRITE, OBJECT_MOTOR, body, dataLength = body.size)
    }

    /**
     * #7/#15(Phase 5) 타임존 동기화 명령을 만든다(레거시 `SetTimeData`, Object Code 0x54).
     * @param timezoneData [TimeZoneCommandBuilder.buildTimezoneHexData]가 만든 26바이트 페이로드.
     *
     * Data Length(offset 26)에는 #12/#13의 레거시 고정값(93) 대신 실제 payload 크기를 담는다 —
     * `SetTimeData`는 `GenerateCmdBody`/`SetControlMotor`와 별개 legacy 함수라 93 고정값이 이
     * 경로에도 적용됐는지 검증된 바 없고, 26바이트 페이로드에 93을 채우면 Data Length를 신뢰하는
     * 수신측이 패킷 경계를 잘못 파싱할 수 있다(코드 리뷰 지적).
     */
    fun buildTimeSyncCommand(timezoneData: ByteArray): ByteArray =
        buildPacket(CMD1_SEND_DATA, CMD2_WRITE, OBJECT_TIME, timezoneData, dataLength = timezoneData.size)
}
