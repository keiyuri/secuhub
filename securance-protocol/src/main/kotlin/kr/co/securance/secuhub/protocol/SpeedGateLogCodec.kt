package kr.co.securance.secuhub.protocol

import kr.co.securance.secuhub.protocol.SpeedGateProtocolConstants.LogEntryOffset
import java.time.DateTimeException
import java.time.LocalDateTime

/**
 * 게이트 로그(Object Code `0x61 GATE_LOG`) 엔트리 1건 — 고정 36바이트 구조
 * (`SpeedGate_Log_protocol_20260728_01.md`).
 *
 * @property eventType 이벤트 대분류 — [SpeedGateProtocolConstants.LogEventType].
 * @property objectCode 이벤트 세부 객체(Granted/Denied 등) — 대분류에 따라 의미가 달라진다.
 * @property code 이벤트 코드(대분류 하위 세부, 문서 표 참고).
 * @property errCode 오류 코드.
 * @property operationMode 운영 모드.
 * @property readerType 문서상 "security mode/Reader Type" — Not Used로 명시되어 있으나 원본 값은 보존한다.
 * @property moduleNumber 게이트 레인 번호(0~14).
 * @property readerNumber 리더 번호(0~254).
 * @property doorStatus 도어 상태 — [SpeedGateProtocolConstants.LogDoorStatus].
 * @property functionCode 기능 코드(0~254).
 * @property eventTime 장비가 기록한 발생 시각(6바이트 BCD 디코딩). BCD 값이 달력 범위를 벗어나면 null.
 * @property userData1 User ID(8)+User Revision(4) 또는 Card ID(8) — 12바이트 원본.
 * @property userData2 Old User ID(8) — 8바이트 원본.
 * @property raw 엔트리 원본 36바이트(사후 재해석/디버깅용).
 */
data class GateLogEntry(
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
    val eventTime: LocalDateTime?,
    val userData1: ByteArray,
    val userData2: ByteArray,
    val raw: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is GateLogEntry && raw.contentEquals(other.raw)

    override fun hashCode(): Int = raw.contentHashCode()
}

/**
 * 게이트 로그(0x61) 패킷의 Data 구간을 36바이트 고정 엔트리 목록으로 디코딩한다.
 *
 * 패킷 전체 프레이밍(Header/DataInfo/Data/Tail)은 다른 Object Code와 동일한 공통 봉투를 쓰므로
 * [GateProtocolCodec.decode]가 이미 파싱한 `dataCount`/`dataLength`를 그대로 신뢰해 호출한다
 * (계획서 3.4절 — Log 전용 봉투 규격은 문서에 별도로 없다).
 */
object SpeedGateLogCodec {

    /**
     * [data] 구간(Header/DataInfo를 제외한 순수 로그 엔트리 나열)을 [entryCount]개의 [GateLogEntry]로
     * 디코딩한다. 패킷이 손상/절단되어 마지막 엔트리가 36바이트를 채우지 못하면 그 엔트리부터는
     * 버리고 그 앞까지만 반환한다(부분 파싱으로 잘못된 값을 만드는 것보다 안전).
     */
    fun decodeEntries(data: ByteArray, entryCount: Int): List<GateLogEntry> {
        val entries = ArrayList<GateLogEntry>(entryCount)
        for (i in 0 until entryCount) {
            val start = i * SpeedGateProtocolConstants.LOG_ENTRY_LENGTH
            val end = start + SpeedGateProtocolConstants.LOG_ENTRY_LENGTH
            if (end > data.size) break
            entries += decodeEntry(data.copyOfRange(start, end))
        }
        return entries
    }

    /** 36바이트 엔트리 1건을 디코딩한다. */
    fun decodeEntry(entry: ByteArray): GateLogEntry {
        require(entry.size == SpeedGateProtocolConstants.LOG_ENTRY_LENGTH) {
            "로그 엔트리는 ${SpeedGateProtocolConstants.LOG_ENTRY_LENGTH}바이트여야 합니다: ${entry.size}"
        }
        fun u8(offset: Int) = entry[offset].toInt() and 0xFF

        return GateLogEntry(
            eventType = entry[LogEntryOffset.EVENT_TYPE],
            objectCode = entry[LogEntryOffset.OBJECT_CODE],
            code = entry[LogEntryOffset.CODE],
            errCode = entry[LogEntryOffset.ERR_CODE],
            operationMode = entry[LogEntryOffset.OPERATION_MODE],
            readerType = entry[LogEntryOffset.READER_TYPE],
            moduleNumber = u8(LogEntryOffset.MODULE_NUMBER),
            readerNumber = u8(LogEntryOffset.READER_NUMBER),
            doorStatus = entry[LogEntryOffset.DOOR_STATUS],
            functionCode = u8(LogEntryOffset.FUNCTION_CODE),
            eventTime = decodeEventTime(entry, LogEntryOffset.EVENT_TIME),
            userData1 = entry.copyOfRange(LogEntryOffset.USER_DATA1, LogEntryOffset.USER_DATA1 + LogEntryOffset.USER_DATA1_LENGTH),
            userData2 = entry.copyOfRange(LogEntryOffset.USER_DATA2, LogEntryOffset.USER_DATA2 + LogEntryOffset.USER_DATA2_LENGTH),
            raw = entry,
        )
    }

    /**
     * 6바이트 BCD(Year,Month,Day,Hour,Min,Sec)를 [LocalDateTime]으로 디코딩한다.
     * 문서 예시(`0x20`→2020)대로 Year는 2000을 더한다. Weekday 바이트는 없다(TimeSync 7바이트 구조와
     * 다른 점 — [SpeedGatePacketCodec.encodeDateTime] 참고).
     *
     * 노이즈/미기록 구간이 0x00~0x99 BCD 범위를 벗어나 달력으로 성립하지 않으면(예: 월=0) 예외
     * 대신 null을 반환한다 — 장비 개별 이벤트 하나의 시각 파싱 실패로 전체 배치 적재가 막히면 안 된다.
     */
    private fun decodeEventTime(entry: ByteArray, offset: Int): LocalDateTime? = try {
        val year = 2000 + SpeedGatePacketCodec.fromBcd(entry[offset])
        val month = SpeedGatePacketCodec.fromBcd(entry[offset + 1])
        val day = SpeedGatePacketCodec.fromBcd(entry[offset + 2])
        val hour = SpeedGatePacketCodec.fromBcd(entry[offset + 3])
        val minute = SpeedGatePacketCodec.fromBcd(entry[offset + 4])
        val second = SpeedGatePacketCodec.fromBcd(entry[offset + 5])
        LocalDateTime.of(year, month, day, hour, minute, second)
    } catch (ex: DateTimeException) {
        null
    }
}
