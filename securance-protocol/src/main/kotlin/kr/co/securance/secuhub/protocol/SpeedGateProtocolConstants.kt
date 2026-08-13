package kr.co.securance.secuhub.protocol

/**
 * SpeedGate 프로토콜(Ver 1.0F, `SpeedGate Protocol Ver1_20250813_01.md`) 상수 모음.
 *
 * 레거시 SR_Speed_Server의 `ClsConst.cs`를 포팅했다. Speed Gate/Flap Gate/Turn Gate/Fast Gate가
 * 모두 공유하는 프로토콜이다(계획서 3.4절, `FastGate Protocol Ver1_2020102601_01.md` 확보 후 확인 —
 * [SpeedFlapGateProtocolCodec] KDoc 참고).
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

    /**
     * ACK 응답 패킷의 전체 길이 — Header(27) + DataInfo(7, TimeSync) + Tail(4).
     * 레거시 `ClsConst.PROTOCOL_ACK_DATE_LENGTH`(39)는 실제 생성 코드
     * (`ClsCommon.MakeACKDataAddTime`의 `new byte[38]`)와 1바이트 어긋나 있었다 —
     * 실제 생성값 38을 정본으로 삼는다.
     */
    const val ACK_PACKET_LENGTH = 31 + 7

    /**
     * 제어 명령(Object Code `GATE_SETTING` 0x4C)의 Data 본문 길이.
     * 레거시 `SR_C_DataHandler.GenerateCmdBody`의 `new byte[93]`에 대응한다(= 0x5D).
     */
    const val CONTROL_BODY_LENGTH = 93

    /** 레인당 최대 32개(프로토콜 `LOCAL GATE LANE COUNT 0~32`). */
    const val MAX_LANE_COUNT = 32

    // ── 패킷 전체 길이 유효 범위(재조립 시 손상 패킷 판별용) ──────────
    const val MIN_PACKET_LENGTH = HEADER_LENGTH + 0 + TAIL_LENGTH // 최소: 헤더+테일(31)
    const val MAX_PACKET_LENGTH = 65536

    /**
     * 프레임 재조립 누적 버퍼 하드 캡 — 초과 시 재동기화(적대적 리뷰 지적).
     * [MAX_PACKET_LENGTH]와 같은 값이면 최대 크기 패킷을 기다리는 도중 다음 청크의 앞부분이 함께
     * 누적되는 경계 상황에서 여유가 전혀 없어진다 — 여유분을 두기 위해 [MAX_PACKET_LENGTH]의 2배로 둔다.
     */
    const val MAX_REASSEMBLY_BUFFER_SIZE = MAX_PACKET_LENGTH * 2

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

    /**
     * 제어 명령 Data 본문(93바이트) 안의 필드 오프셋(본문 시작 기준).
     * 레거시 `SR_C_DataHandler.GenerateCmdBody`의 `bCmdData[...]` 인덱스와 1:1 대응한다.
     */
    object ControlBodyOffset {
        /** 레인 번호 — 16진(바이트 값 그대로, 레인 10 → 0x0A). 레거시 2026-07-28 확정 규칙. */
        const val LANE_NUMBER = 0

        /** 운영 모드(Open/Close/Card-Free 등). */
        const val CONTROL_MODE = 1

        /** 보안 등급(LOW=1/MIDDLE=2/HIGH=3) — 1차 스프린트 범위 밖(0 고정). */
        const val SECURITY_MODE = 2

        /** 사용자 시간 데이터(3바이트) — 스케줄 화면 전용, 1차 스프린트 범위 밖. */
        const val TIME_DATA_USER = 3

        /** 보안 시간 데이터(3바이트) — 스케줄 화면 전용, 1차 스프린트 범위 밖. */
        const val TIME_DATA_SECURITY = 6

        /** 리셋/장애해제 코드(시스템/운영센서/안전센서/모터/메인보드). */
        const val RESET_CODE = 20
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
        /** Speed Gate Log — 로그 데이터 전용(계획서 3.8절, 3차 스프린트에서 구조화 파싱 구현). 'a' */
        const val GATE_LOG: Byte = 0x61

        /**
         * Fast Gate Motor Data — Fast Gate 전용 모터 설정/조회(`FastGate Protocol Ver1_2020102601_01.md`,
         * Object Table 0x50 'P'). 이 상수만 정의해두고 Set/Request 페이로드(72바이트, Turn/Slide 모터
         * 파라미터) 인코딩은 아직 구현하지 않았다 — 실제 Fast Gate 모터 제어 화면/기능 요구가 생기면
         * 이 상수를 기준으로 전용 코덱을 추가한다.
         */
        const val FAST_GATE_MOTOR: Byte = 0x50
    }

    /**
     * 게이트 로그(0x61) 엔트리 이벤트 대분류/Door Status 값, 36바이트 필드 오프셋은
     * [LogEventCodec]가 실제 운영 경로에서 자체 상수(`LogEventCodec.EventType`,
     * `LogEventCodec.DoorStatus`, `LogEventCodec.Offset`)로 정의한다 — 과거 이 위치에 있던
     * 중복 정의(`LogEventType`/`LogDoorStatus`/`LogEntryOffset`, 미사용 `SpeedGateLogCodec`
     * 전용)는 죽은 코드라 제거했다(2026-08-13 코드 리뷰).
     */

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
