package kr.co.securance.secuhub.protocol

/**
 * SpeedGate 프로토콜(Ver 1.0F, `SpeedGate Protocol Ver1_20250813_01.md`) 상수 모음.
 *
 * 레거시 SR_Speed_Server의 `ClsConst.cs`를 포팅했다. Speed Gate/Flap Gate가 공유하는
 * 프로토콜이며(계획서 3.4절), Turn Gate/Fast Gate는 별도 규격이라 이 상수를 쓰지 않는다.
 *
 * 패킷 구조: `Header(27) + Data(N) + Tail(4)`
 */
object SpeedGateProtocolConstants {

    // ── 프레임 구분 바이트 ────────────────────────────────────────────
    /** 패킷 시작(Start of Text). 고정값. */
    const val STX: Byte = 0x02

    /** 프로토콜 버전. 고정값. */
    const val PROTOCOL_VERSION: Byte = 0x04

    /** 패킷 체크섬(고정, 계산하지 않음) — Tail의 3번째 바이트. */
    const val PACKET_CHECKSUM_FIXED: Byte = 0x08

    /** 패킷 종료(End of Text) — Tail의 마지막 바이트. */
    const val ETX: Byte = 0x03

    // ── 섹션 길이(byte) ──────────────────────────────────────────────
    const val HEADER_LENGTH = 27
    const val TAIL_LENGTH = 4
    const val DATA_INFO_LENGTH = 45
    const val STATUS_DATA_LENGTH = 74
    const val LOG_ENTRY_LENGTH = 36
    const val ADDRESS_LENGTH = 13

    /** 레인당 최대 32개(프로토콜 `LOCAL GATE LANE COUNT 0~32`). */
    const val MAX_LANE_COUNT = 32

    // ── 패킷 전체 길이 유효 범위(재조립 시 손상 패킷 판별용) ──────────
    const val MIN_PACKET_LENGTH = HEADER_LENGTH + 0 + TAIL_LENGTH // 최소: 헤더+테일(31)
    const val MAX_PACKET_LENGTH = 65536

    /** 프레임 재조립 누적 버퍼 하드 캡(레거시와 동일 — 초과 시 폐기 후 재동기화). */
    const val MAX_REASSEMBLY_BUFFER_SIZE = 65536

    // ── 헤더 내 필드 오프셋(0-based, 패킷 시작 기준) ───────────────────
    object HeaderOffset {
        const val STX = 0
        const val PACKET_LENGTH = 1 // 2 byte, big-endian
        const val PROTOCOL_VERSION = 3
        const val FRAME_OPTION = 4 // 2 byte
        const val ADDRESS = 6 // 13 byte
        const val COMMAND1 = 19
        const val COMMAND2 = 20
        const val OBJECT_CODE = 21
        const val DATA_INFO_LENGTH = 22
        const val DATA_COUNT = 23 // 2 byte
        const val DATA_LENGTH = 25 // 2 byte
        // HEADER_LENGTH(27)까지 — 이후 DataInfo/Data/Log, 마지막 4바이트가 Tail(XOR,SUM,0x08,ETX)
    }

    // ── CMD1: 명령 대분류 ────────────────────────────────────────────
    object Command1 {
        const val SEND_DATA: Byte = 0x05
        const val REQUEST_DATA: Byte = 0x06
        const val SEND_ACK: Byte = 0x07
        const val REQUEST_ACK: Byte = 0x08
    }

    // ── CMD2: 명령 세부(Sub Command) ────────────────────────────────
    object Command2 {
        const val READ: Byte = 0x02
        const val WRITE: Byte = 0x03
        const val DELETE: Byte = 0x04
    }

    // ── Object Code: 제어 대상 ───────────────────────────────────────
    object ObjectCode {
        /** Speed Gate Data — 게이트 설정값. 'L' */
        const val GATE_SETTING: Byte = 0x4C
        /** Speed Gate Status — 게이트 상태. 'M' */
        const val GATE_STATUS: Byte = 0x4D
        /** Speed Gate Data None. 'N' */
        const val GATE_STATUS_NONE: Byte = 0x4E
        /** Speed Gate Motor Data. 'K' */
        const val GATE_MOTOR: Byte = 0x4B
        /** Servo Motor Data. 'F' */
        const val SERVO_MOTOR: Byte = 0x46
        /** Function Data. 'G' */
        const val GATE_FUNCTION: Byte = 0x47
        /** Gate NC/NO Mode. 'O' */
        const val NC_NO_MODE: Byte = 0x4F
        /** Speed Gate Status Re-request. 'R' */
        const val GATE_STATUS_RE_REQUEST: Byte = 0x52
        /** Time Zone. 'T' */
        const val TIME_ZONE: Byte = 0x54
        /** Time Zone Mode. 'U' */
        const val TIME_ZONE_MODE: Byte = 0x55
        /** Holiday. 'H' */
        const val HOLIDAY: Byte = 0x48
        /** Time Zone Mode(Multi). 'W' */
        const val TIME_ZONE_MODE_MULTI: Byte = 0x57
        /** Speed Gate Log — 로그 데이터 전용(계획서 3.8절, 1차 스캐폴드 미구현). 'a' */
        const val GATE_LOG: Byte = 0x61
    }

    /** ACK 응답 결과 값(다수의 오브젝트 응답에서 공통으로 쓰인다). */
    object AckResult {
        const val SUCCESS: Byte = 0x01
        const val RESEND: Byte = 0x02
        const val FAIL: Byte = 0x03
    }

    /** 요일 코드(BCD 아님, 단순 값) — Sunday=1 ... Saturday=7. */
    object Weekday {
        const val SUNDAY: Byte = 0x01
        const val MONDAY: Byte = 0x02
        const val TUESDAY: Byte = 0x03
        const val WEDNESDAY: Byte = 0x04
        const val THURSDAY: Byte = 0x05
        const val FRIDAY: Byte = 0x06
        const val SATURDAY: Byte = 0x07
    }
}
