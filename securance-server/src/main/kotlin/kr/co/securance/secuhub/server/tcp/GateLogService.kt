package kr.co.securance.secuhub.server.tcp

import kr.co.securance.secuhub.domain.entity.GateLog
import kr.co.securance.secuhub.domain.repository.GateLogRepository
import kr.co.securance.secuhub.protocol.GatePacket
import kr.co.securance.secuhub.protocol.LogEventCodec
import kr.co.securance.secuhub.protocol.SpeedGateProtocolConstants
import kr.co.securance.secuhub.server.db.GateDbWriteQueue
import kr.co.securance.secuhub.server.db.GateDbWriteTask
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * `GATE_LOG` 로그 엔트리(36바이트, [LogEventCodec]) 재조립 → DB 저장(계획서 3.8절, 레거시
 * 미구현 기능이라 신규 설계).
 *
 * **레이아웃 정정(레거시 재검증 결과 — 2026.08.07)**: 최초 구현 시엔 `GATE_LOG`가 문서상
 * ObjectCode `0x61`을 가진 *독립된* 패킷("Header(27)+Data(N)+Tail(4)", DataInfo 없음)으로
 * 온다고 가정했으나(`handle`), 레거시 SR_Speed_Server(`SpeedServer.cs` 1011~1064행,
 * `ClsPacketAnalyzer.cs`)를 재확인한 결과 실제로는 그렇지 않다:
 * - 레거시는 `objCode`와 무관하게 매 수신 패킷에서 로그 유무를 판단한다 — 로그 엔트리는 실제로
 *   `GATE_STATUS`(0x4D) 패킷 **끝에 이어 붙어** 온다: `Header(27)+DataInfo(45)+Status(laneCnt*74)+
 *   Log(logCnt*36)+Tail(4)`.
 * - `logCnt`는 헤더의 `DATA_COUNT` 필드(오프셋 23~24, [GatePacket.dataCount]와 동일)에서 읽고,
 *   로그 시작 오프셋은 `Header+DataInfo+(laneCnt*Status블록길이)`다(`laneCnt`는 DataInfo 마지막
 *   바이트, [PacketDiffer.laneCountOf] 참고) — Header 직후가 아니다.
 * - `GateLog_protocol_20260728.docx`(레거시 저장소 최상위)는 로그 엔트리 36바이트 "자체"의
 *   필드 구조만 정의할 뿐, 이를 감싼 패킷 프레이밍은 규정하지 않는다 — 프레이밍은 레거시
 *   구현이 유일한 실물 근거다.
 *
 * 따라서 [handleEmbedded]를 `DefaultGatePacketHandler`의 `GATE_STATUS` 분기에서 호출해 위
 * 레이아웃대로 로그를 추출한다. 문서상 정의된 독립 `GATE_LOG`(0x61) ObjectCode를 실제 장비가
 * 보낼 가능성도 완전히 배제할 수 없어(신형 펌웨어 등) [handle]은 하위 호환/향후 대비용으로
 * 남겨둔다 — 두 경로 모두 같은 저장 로직([saveIfAbsent])을 공유한다.
 *
 * **멱등성**: [GateDbWriteQueue]는 타임아웃 후 버려둔 실행이 뒤늦게 완료되면 같은 작업이 두 번
 * 실행될 수 있다고 명시한다(클래스 KDoc "주의(멱등성)"). 로그 저장은 NetState처럼 자연스러운
 * PK(복합키) UPSERT 대상이 아니라 매 수신마다 새 행을 추가하는 append-only 데이터라, 대신
 * (게이트, 레인, 이벤트 발생 시각, 이벤트유형, 코드, 에러코드, 기능코드)를 자연키로 삼아 저장 직전
 * 존재 여부를 확인하는 find-or-create 패턴으로 멱등성을 확보한다([GateLogRepository] 참고).
 */
@Component
class GateLogService(
    private val dbWriteQueue: GateDbWriteQueue,
    private val gateLogRepository: GateLogRepository,
) {
    private val logger = LoggerFactory.getLogger(GateLogService::class.java)

    /**
     * `DefaultGatePacketHandler`가 문서상 독립 `GATE_LOG`(0x61) 패킷 수신 시 호출한다(하위 호환/
     * 향후 대비 경로 — 클래스 KDoc "레이아웃 정정" 참고). Data 영역이 헤더 직후부터 시작한다고
     * 가정한다.
     */
    fun handle(dtlIp: String, packet: GatePacket) {
        handleEntries(dtlIp, packet.raw, SpeedGateProtocolConstants.HEADER_LENGTH, packet.dataCount, "GATE_LOG")
    }

    /**
     * `DefaultGatePacketHandler`가 `GATE_STATUS`(0x4D) 패킷 끝에 이어 붙은 로그 구간을 발견했을 때
     * 호출한다(클래스 KDoc "레이아웃 정정" 참고 — 레거시 검증된 실제 경로).
     *
     * @param dataStart 로그 엔트리가 시작하는 [raw] 내 오프셋(`Header+DataInfo+laneCnt*Status블록길이`).
     * @param entryCount 로그 엔트리 개수(헤더 `DATA_COUNT` 필드, [GatePacket.dataCount]와 동일).
     */
    fun handleEmbedded(dtlIp: String, raw: ByteArray, dataStart: Int, entryCount: Int) {
        handleEntries(dtlIp, raw, dataStart, entryCount, "GATE_STATUS 내장 로그")
    }

    private fun handleEntries(dtlIp: String, raw: ByteArray, dataStart: Int, entryCount: Int, sourceLabel: String) {
        val entries = decodeEntries(dtlIp, raw, dataStart, entryCount, sourceLabel)
        if (entries.isEmpty()) return

        dbWriteQueue.enqueue(
            GateDbWriteTask(
                partitionKey = dtlIp,
                operationName = "SaveGateLog($dtlIp, ${entries.size}건)",
            ) {
                entries.forEach { entry -> saveIfAbsent(dtlIp, entry) }
            },
        )
    }

    private fun decodeEntries(
        dtlIp: String,
        raw: ByteArray,
        dataStart: Int,
        requestedEntryCount: Int,
        sourceLabel: String,
    ): List<LogEventCodec.LogEvent> {
        if (requestedEntryCount <= 0 || dataStart < 0) return emptyList()

        val availableForData = raw.size - dataStart - SpeedGateProtocolConstants.TAIL_LENGTH
        if (availableForData < SpeedGateProtocolConstants.LOG_ENTRY_LENGTH) {
            logger.warn("커넥션[{}] {} 로그 영역이 엔트리 1건보다 짧습니다: size={}, dataStart={}", dtlIp, sourceLabel, raw.size, dataStart)
            return emptyList()
        }

        // 엔트리 수가 실제 수신 바이트로 커버 가능한 개수보다 크면(손상/잘린 패킷), 안전하게
        // 커버 가능한 만큼만 디코딩한다 — PacketDiffer.laneOffsetsOf가 GATE_STATUS에서 취하는 것과
        // 동일한 방어적 태도.
        val maxEntriesByLength = availableForData / SpeedGateProtocolConstants.LOG_ENTRY_LENGTH
        val entryCount = requestedEntryCount.coerceIn(0, maxEntriesByLength)
        if (entryCount < requestedEntryCount) {
            logger.warn(
                "커넥션[{}] {} 엔트리 수({})가 실제 수신 바이트로 커버 가능한 개수({})보다 큽니다 — {}건만 디코딩합니다.",
                dtlIp, sourceLabel, requestedEntryCount, maxEntriesByLength, entryCount,
            )
        }
        if (entryCount <= 0) return emptyList()

        val data = raw.copyOfRange(dataStart, dataStart + entryCount * SpeedGateProtocolConstants.LOG_ENTRY_LENGTH)
        return try {
            LogEventCodec.decodeAll(data, entryCount)
        } catch (ex: IllegalArgumentException) {
            logger.warn("커넥션[{}] {} 엔트리 디코딩 실패", dtlIp, sourceLabel, ex)
            emptyList()
        }
    }

    private fun saveIfAbsent(dtlIp: String, entry: LogEventCodec.LogEvent) {
        val eventType = entry.eventType.toInt() and 0xFF
        val code = entry.code.toInt() and 0xFF
        val errCode = entry.errCode.toInt() and 0xFF

        val exists = gateLogRepository.existsByDtlIpAndDtlLaneNoAndEventTimeAndEventTypeAndCodeAndErrCodeAndFunctionCode(
            dtlIp, entry.moduleNumber, entry.eventTime, eventType, code, errCode, entry.functionCode,
        )
        if (exists) {
            logger.debug("커넥션[{}] 이미 저장된 로그 엔트리라 건너뜁니다: {}", dtlIp, entry)
            return
        }

        gateLogRepository.save(
            GateLog(
                dtlIp = dtlIp,
                dtlLaneNo = entry.moduleNumber,
                eventType = eventType,
                objectCode = entry.objectCode.toInt() and 0xFF,
                code = code,
                errCode = errCode,
                operationMode = entry.operationMode.toInt() and 0xFF,
                readerType = entry.readerType.toInt() and 0xFF,
                readerNumber = entry.readerNumber,
                doorStatus = entry.doorStatus.toInt() and 0xFF,
                functionCode = entry.functionCode,
                eventTime = entry.eventTime,
                userData1 = entry.userData1Hex,
                userData2 = entry.userData2Hex,
            ),
        )
    }
}
