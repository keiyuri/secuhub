package kr.co.securance.secuhub.protocol

/**
 * SpeedGate 계열 게이트로 보내는 제어 명령 종류.
 *
 * 레거시 `SR_C_DataHandler.GenerateCmdBody`가 문자열 코드(`"OP"`, `"CL"`, `"AC"` ...)를
 * `switch`로 분기하며 본문 바이트를 채우던 것을 열거형으로 옮겼다. 문자열 분기는 오타가
 * 조용히 "기본값(Normal)"으로 흘러가 잘못된 명령이 나가는 원인이었으므로, 호출 측이
 * 열거형만 쓰도록 강제한다.
 *
 * **2차 스프린트**에서 레거시 `ControlMode` 열거형(SR_C_DataHandler.cs:9)의 나머지 5종
 * (역방향 개방/카드-폐쇄 조합 4종)을 추가해 운영 모드 분기를 레거시와 1:1로 맞췄다.
 *
 * @property legacyCode 레거시 `sControlType` 문자열 코드(2자) — DB `tb_data_snd.snd_type_cd`
 *   호환 및 로그 추적을 위해 보존한다.
 * @property modeByte 본문 오프셋 [SpeedGateProtocolConstants.ControlBodyOffset.CONTROL_MODE]에 채울 값.
 * @property resetByte 본문 오프셋 [SpeedGateProtocolConstants.ControlBodyOffset.RESET_CODE]에 채울 값.
 *   0이면 리셋 명령이 아니다.
 */
enum class SpeedGateControlCommand(
    val legacyCode: String,
    val modeByte: Byte,
    val resetByte: Byte = 0x00,
) {
    // ── 운영 모드 변경 ───────────────────────────────────────────────
    /** 기본(정상) 모드로 복귀. */
    NORMAL("NM", 0x01),

    /** 입장=카드, 퇴장=자유. */
    CARD_FREE("CF", 0x02),

    /** 입장=자유, 퇴장=카드. */
    FREE_CARD("FC", 0x03),

    /** 입출입 모두 자유. */
    FREE_FREE("FF", 0x04),

    /** 게이트 개방(상시 열림). */
    OPEN("OP", 0x05),

    /** 게이트 폐쇄(상시 닫힘). */
    CLOSE("CL", 0x06),

    /** 입장=카드, 퇴장=폐쇄(레거시 `CX` / `ControlMode.CardClosure`). */
    CARD_CLOSE("CX", 0x07),

    /** 입장=폐쇄, 퇴장=카드(레거시 `XC` / `ControlMode.ClosureCard`). */
    CLOSE_CARD("XC", 0x08),

    /** 입장=자유, 퇴장=폐쇄(레거시 `FX` / `ControlMode.FreeClosure`). */
    FREE_CLOSE("FX", 0x09),

    /** 입장=폐쇄, 퇴장=자유(레거시 `XF` / `ControlMode.ClosureFree`). */
    CLOSE_FREE("XF", 0x0A),

    /** 역방향 개방(레거시 `RP` / `ControlMode.ReverseOpen` = 0x15). */
    REVERSE_OPEN("RP", 0x15),

    // ── 리셋/장애 해제 ───────────────────────────────────────────────
    //
    // 레거시에서 리셋 계열은 mode 분기(`sCtrlTp1`)에 해당 코드가 없어 항상 기본값 Normal(0x01)이
    // 채워지고, 리셋 코드만 오프셋 20에 따로 들어갔다. 그 동작을 그대로 재현한다.

    /** 게이트 시스템 전체 리셋(레거시 `AC` — SR_F_GateReset/btnSystemReset). */
    RESET_SYSTEM("AC", 0x01, 0x01),

    /** 운영 센서 장애 해제(레거시 `OS`). */
    RESET_OPERATION_SENSOR("OS", 0x01, 0x02),

    /** 안전 센서 장애 해제(레거시 `SS`). */
    RESET_SAFETY_SENSOR("SS", 0x01, 0x03),

    /** 모터 장애 해제(레거시 `MT`). */
    RESET_MOTOR("MT", 0x01, 0x04),

    /** 메인보드 리셋(레거시 `MB`). */
    RESET_BOARD("MB", 0x01, 0x05),

    // ── Fast Gate 전용 (3차 스프린트, `FastGate Protocol Ver1_2020102601_01.md` 기준) ──────
    //
    // 레거시 SR_Speed_Client/Server는 Fast Gate를 다루지 않았으므로 대응하는 2자 코드가 없다.
    // 여기서는 신규로 부여했다("PS"/"SO"/"SC") — DB 호환을 위한 레거시 값이 아니라는 점에
    // 주의(다른 항목들과 달리 레거시 문자열을 그대로 옮긴 게 아니다).

    /** 일시 정지(레거시 없음, Fast Gate 전용 — Object 0x4C 제어모드 0x41). */
    PAUSE("PS", 0x41),

    /** 슬라이드 도어 개방(Fast Gate 전용 — 제어모드 0x42). */
    SLIDE_OPEN("SO", 0x42),

    /** 슬라이드 도어 폐쇄(Fast Gate 전용 — 제어모드 0x43). */
    SLIDE_CLOSE("SC", 0x43),
    ;

    /** 리셋/장애 해제 계열 명령인지 여부. */
    val isReset: Boolean
        get() = resetByte != ZERO

    companion object {
        private const val ZERO: Byte = 0x00

        /** 레거시 문자열 코드(대소문자 무시)로 명령을 찾는다. 알 수 없으면 null. */
        fun ofLegacyCode(code: String?): SpeedGateControlCommand? {
            val normalized = code?.trim()?.uppercase() ?: return null
            return entries.firstOrNull { it.legacyCode == normalized }
        }
    }
}

/**
 * 게이트 보안 등급 — 제어 본문 오프셋
 * [SpeedGateProtocolConstants.ControlBodyOffset.SECURITY_MODE]에 채운다.
 *
 * 레거시는 `sControlType.Contains("LM"/"MM"/"HM")`이라는 **부분 문자열 검사**로 등급을
 * 판별했다(`SR_C_DataHandler.GenerateCmdBody`). 제어 코드 문자열에 우연히 같은 두 글자가
 * 섞이면 의도치 않은 등급이 실리는 구조였으므로, 명령과 분리된 별도 파라미터로 옮긴다.
 *
 * 값 정의는 수신 분석(`utrg_data_rcv_anlz` 트리거의 `desc_security_mode`)과 동일하다.
 */
enum class SpeedGateSecurityMode(val legacyCode: String, val value: Byte, val description: String) {
    LOW("LM", 0x01, "LOW MODE"),
    MIDDLE("MM", 0x02, "MIDDLE MODE"),
    HIGH("HM", 0x03, "HIGH MODE"),
    ;

    companion object {
        fun ofLegacyCode(code: String?): SpeedGateSecurityMode? {
            val normalized = code?.trim()?.uppercase() ?: return null
            return entries.firstOrNull { it.legacyCode == normalized }
        }

        fun ofValue(value: Int): SpeedGateSecurityMode? = entries.firstOrNull { it.value.toInt() == value }
    }
}

/**
 * 제어 명령 1건의 전체 페이로드 파라미터.
 *
 * 레거시 `SetControlCmd(boardType, ip, laneNo, controlType, timeDataUser, timeDataSecu)`의
 * 인자 묶음에 대응한다. 파라미터가 늘어날 때마다 코덱 인터페이스 시그니처가 바뀌지 않도록
 * 하나의 값 객체로 묶었다.
 *
 * @property command 운영 모드/리셋 명령.
 * @property securityMode 보안 등급. null이면 본문 오프셋 2를 0으로 두어 "변경 없음"을 의미한다.
 * @property userTime 사용자 통행 시간 데이터 3바이트(스케줄 화면). null이면 0으로 둔다.
 * @property securityTime 보안 시간 데이터 3바이트(스케줄 화면). null이면 0으로 둔다.
 */
data class SpeedGateControlPayload(
    val command: SpeedGateControlCommand,
    val securityMode: SpeedGateSecurityMode? = null,
    val userTime: ByteArray? = null,
    val securityTime: ByteArray? = null,
) {
    init {
        require(userTime == null || userTime.size == TIME_DATA_LENGTH) {
            "userTime은 ${TIME_DATA_LENGTH}바이트여야 합니다: ${userTime?.size}"
        }
        require(securityTime == null || securityTime.size == TIME_DATA_LENGTH) {
            "securityTime은 ${TIME_DATA_LENGTH}바이트여야 합니다: ${securityTime?.size}"
        }
    }

    override fun equals(other: Any?): Boolean =
        other is SpeedGateControlPayload &&
            command == other.command &&
            securityMode == other.securityMode &&
            (userTime?.contentEquals(other.userTime) ?: (other.userTime == null)) &&
            (securityTime?.contentEquals(other.securityTime) ?: (other.securityTime == null))

    override fun hashCode(): Int {
        var result = command.hashCode()
        result = 31 * result + (securityMode?.hashCode() ?: 0)
        result = 31 * result + (userTime?.contentHashCode() ?: 0)
        result = 31 * result + (securityTime?.contentHashCode() ?: 0)
        return result
    }

    companion object {
        /** 레거시 `Array.Copy(timeBytes, 0, bCmdData, 3, 3)` — 시간 데이터는 3바이트 고정. */
        const val TIME_DATA_LENGTH = 3
    }
}
