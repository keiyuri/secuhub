package kr.co.securance.secuhub.protocol

import kr.co.securance.secuhub.common.util.HexCodec
import java.time.LocalDateTime

/**
 * `GATE_LOG`(ObjectCode `0x61`) 로그 엔트리(36바이트) 디코더.
 *
 * 계획서 3.8절에 명시된 대로 이 영역은 레거시(`SR_Speed_Server`/`SR_Speed_Client`) 양쪽 모두
 * 미완성 상태라 "이식"이 아닌 신규 설계 대상이다. 필드 순서/길이는 사용자가 제공한 프로토콜
 * 문서(`SpeedGate_Log_protocol_20260728_01.md`, "Log(Event) Structure(36-Byte)")를 1차 소스로
 * 삼아 그대로 반영했다.
 *
 * 필드 레이아웃(총 36바이트):
 * ```
 * offset  0: Event Type          (1)
 * offset  1: Object Code         (1)
 * offset  2: Code                (1)
 * offset  3: Err Code            (1)
 * offset  4: Operation Mode      (1)
 * offset  5: Security Mode/Reader Type (1) — 문서상 "Not Used"
 * offset  6: Module Number(레인 번호, 0~14) (1)
 * offset  7: Reader Number(0~254) (1)
 * offset  8: Door Status         (1)
 * offset  9: Function Code(0~254) (1)
 * offset 10: Event Time(BCD, Year/Month/Day/Hour/Min/Sec) (6)
 * offset 16: User Data1(User ID(8)+Revision(4) 또는 Card ID(8)) (12)
 * offset 28: User Data2(Old User ID(8)) (8)
 * ```
 *
 * Event Time은 [SpeedGatePacketCodec.encodeDateTime]과 동일한 BCD 인코딩이다(문서 예시
 * "0x20 → 2020"). 다만 헤더의 7바이트 타임스탬프와 달리 요일 바이트가 없는 6바이트 구성이다.
 */
object LogEventCodec {

    const val ENTRY_LENGTH = SpeedGateProtocolConstants.LOG_ENTRY_LENGTH

    private const val EVENT_TIME_LENGTH = 6
    private const val USER_DATA1_LENGTH = 12
    private const val USER_DATA2_LENGTH = 8

    private object Offset {
        const val EVENT_TYPE = 0
        const val OBJECT_CODE = 1
        const val CODE = 2
        const val ERR_CODE = 3
        const val OPERATION_MODE = 4
        const val READER_TYPE = 5
        const val MODULE_NUMBER = 6
        const val READER_NUMBER = 7
        const val DOOR_STATUS = 8
        const val FUNCTION_CODE = 9
        const val EVENT_TIME = 10
        const val USER_DATA1 = EVENT_TIME + EVENT_TIME_LENGTH // 16
        const val USER_DATA2 = USER_DATA1 + USER_DATA1_LENGTH // 28
    }

    /** 문서 "Event Type" 값 목록. */
    object EventType {
        const val ACCESS: Byte = 0x01
        const val PARKING: Byte = 0x08
        const val DATA_OBJECT: Byte = 0x10
        const val SYSTEM: Byte = 0x18
        const val COMMUNICATION: Byte = 0x20
    }

    /** 문서 "Door Status" 값. */
    object DoorStatus {
        const val ACTIVE_OPEN: Byte = 0x01
        const val INACTIVE_CLOSE: Byte = 0x02
    }

    /**
     * 디코딩된 로그 엔트리 1건.
     * [userData1]/[userData2]는 원시 바이트 그대로의 의미가 다중이라(User ID+Revision 또는 Card ID
     * 등, 문서상 하위 필드 경계 불명) 파싱하지 않고 [HexCodec] 16진 문자열로 보관한다 — DB 원시
     * 패킷 저장 관례([HexCodec] 문서 참고)와 동일한 방식이다.
     */
    data class LogEvent(
        val eventType: Byte,
        val objectCode: Byte,
        val code: Byte,
        val errCode: Byte,
        val operationMode: Byte,
        val readerType: Byte,
        val moduleNumber: Int,
        val readerNumber: Int,
        val doorStatus: Byte,
        val functionCode: Int,
        val eventTime: LocalDateTime,
        val userData1Hex: String,
        val userData2Hex: String,
    )

    /** 로그 엔트리 1건(36바이트)을 디코딩한다. */
    fun decode(entry: ByteArray): LogEvent {
        require(entry.size == ENTRY_LENGTH) {
            "로그 엔트리는 ${ENTRY_LENGTH}바이트여야 합니다: ${entry.size}"
        }

        val year = SpeedGatePacketCodec.fromBcd(entry[Offset.EVENT_TIME])
        val month = SpeedGatePacketCodec.fromBcd(entry[Offset.EVENT_TIME + 1])
        val day = SpeedGatePacketCodec.fromBcd(entry[Offset.EVENT_TIME + 2])
        val hour = SpeedGatePacketCodec.fromBcd(entry[Offset.EVENT_TIME + 3])
        val minute = SpeedGatePacketCodec.fromBcd(entry[Offset.EVENT_TIME + 4])
        val second = SpeedGatePacketCodec.fromBcd(entry[Offset.EVENT_TIME + 5])

        return LogEvent(
            eventType = entry[Offset.EVENT_TYPE],
            objectCode = entry[Offset.OBJECT_CODE],
            code = entry[Offset.CODE],
            errCode = entry[Offset.ERR_CODE],
            operationMode = entry[Offset.OPERATION_MODE],
            readerType = entry[Offset.READER_TYPE],
            moduleNumber = entry[Offset.MODULE_NUMBER].toInt() and 0xFF,
            readerNumber = entry[Offset.READER_NUMBER].toInt() and 0xFF,
            doorStatus = entry[Offset.DOOR_STATUS],
            functionCode = entry[Offset.FUNCTION_CODE].toInt() and 0xFF,
            // 문서 예시("0x20 → 2020")와 동일하게 두 자리 BCD 연도에 2000을 더한다.
            eventTime = LocalDateTime.of(2000 + year, month, day, hour, minute, second),
            userData1Hex = HexCodec.toHex(entry.copyOfRange(Offset.USER_DATA1, Offset.USER_DATA1 + USER_DATA1_LENGTH)),
            userData2Hex = HexCodec.toHex(entry.copyOfRange(Offset.USER_DATA2, Offset.USER_DATA2 + USER_DATA2_LENGTH)),
        )
    }

    /**
     * `GATE_LOG` 패킷의 Data 영역에서 [entryCount]개(헤더 `DATA_COUNT` 필드)의 로그 엔트리를
     * 순서대로 디코딩한다.
     */
    fun decodeAll(data: ByteArray, entryCount: Int): List<LogEvent> {
        require(data.size >= entryCount * ENTRY_LENGTH) {
            "데이터 길이(${data.size})가 로그 ${entryCount}건(${entryCount * ENTRY_LENGTH}바이트)에 부족합니다"
        }
        return (0 until entryCount).map { idx ->
            val start = idx * ENTRY_LENGTH
            decode(data.copyOfRange(start, start + ENTRY_LENGTH))
        }
    }
}
