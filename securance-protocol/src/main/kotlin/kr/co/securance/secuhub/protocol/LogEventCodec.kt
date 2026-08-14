package kr.co.securance.secuhub.protocol

import kr.co.securance.secuhub.common.util.HexCodec
import java.time.DateTimeException
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
        /** 장비가 기록한 발생 시각. BCD 값이 달력 범위를 벗어나면(손상/노이즈) null. */
        val eventTime: LocalDateTime?,
        val userData1Hex: String,
        val userData2Hex: String,
    )

    /** 로그 엔트리 1건(36바이트)을 디코딩한다. */
    fun decode(entry: ByteArray): LogEvent {
        require(entry.size == ENTRY_LENGTH) {
            "로그 엔트리는 ${ENTRY_LENGTH}바이트여야 합니다: ${entry.size}"
        }

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
            eventTime = decodeEventTime(entry),
            userData1Hex = HexCodec.toHex(entry.copyOfRange(Offset.USER_DATA1, Offset.USER_DATA1 + USER_DATA1_LENGTH)),
            userData2Hex = HexCodec.toHex(entry.copyOfRange(Offset.USER_DATA2, Offset.USER_DATA2 + USER_DATA2_LENGTH)),
        )
    }

    /**
     * 6바이트 BCD(Year,Month,Day,Hour,Min,Sec)를 [LocalDateTime]으로 디코딩한다. 문서 예시
     * ("0x20 → 2020")대로 Year는 2000을 더한다. BCD 값이 달력으로 성립하지 않으면(노이즈/손상
     * 엔트리, 예: 월=13) 예외 대신 null을 반환한다 — 엔트리 1건의 시각 파싱 실패로 전체 배치
     * 디코딩이 예외로 중단되면 안 된다([SpeedGateLogCodec.decodeEventTime]과 동일한 방어).
     */
    private fun decodeEventTime(entry: ByteArray): LocalDateTime? = try {
        val year = 2000 + SpeedGatePacketCodec.fromBcd(entry[Offset.EVENT_TIME])
        val month = SpeedGatePacketCodec.fromBcd(entry[Offset.EVENT_TIME + 1])
        val day = SpeedGatePacketCodec.fromBcd(entry[Offset.EVENT_TIME + 2])
        val hour = SpeedGatePacketCodec.fromBcd(entry[Offset.EVENT_TIME + 3])
        val minute = SpeedGatePacketCodec.fromBcd(entry[Offset.EVENT_TIME + 4])
        val second = SpeedGatePacketCodec.fromBcd(entry[Offset.EVENT_TIME + 5])
        LocalDateTime.of(year, month, day, hour, minute, second)
    } catch (ex: DateTimeException) {
        null
    }

    /**
     * `GATE_LOG` 패킷의 Data 영역에서 [entryCount]개(헤더 `DATA_COUNT` 필드)의 로그 엔트리를
     * 순서대로 디코딩한다.
     *
     * 코드 리뷰 지적(2026-08-14): [entryCount]는 장비가 보낸 헤더 필드(신뢰할 수 없는 입력)라,
     * 실제 [data] 길이와 맞지 않는(손상/절단된 패킷) 경우가 있을 수 있다. 예전에는 `require`로
     * 예외를 던졌는데 — 현재 유일한 호출부(`GateLogService`)가 호출 전 미리 커버 가능한 개수로
     * 잘라주고 있어 실제로는 발동하지 않지만, 이 함수 자체를 직접 호출할 다른 경로가 생기면
     * 여전히 위험하다. [SpeedGateLogCodec.decodeEntries]와 동일하게, 커버 가능한 만큼만 디코딩하고
     * 마지막 미완성 엔트리부터는 조용히 버린다(부분 파싱으로 잘못된 값을 만드는 것보다 안전).
     */
    fun decodeAll(data: ByteArray, entryCount: Int): List<LogEvent> {
        val maxEntries = (data.size / ENTRY_LENGTH).coerceAtMost(entryCount.coerceAtLeast(0))
        return (0 until maxEntries).map { idx ->
            val start = idx * ENTRY_LENGTH
            decode(data.copyOfRange(start, start + ENTRY_LENGTH))
        }
    }
}
