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
 * `GATE_LOG`(ObjectCode `0x61`) 패킷 재조립 → DB 저장(계획서 3.8절, 레거시 미구현 기능이라 신규 설계).
 *
 * **패킷 레이아웃 가정(주의)**: 다른 오브젝트 코드(예: `GATE_STATUS`)와 달리 `GATE_LOG`는 문서상
 * DataInfo(45바이트) 구간이 없는 일반 프레임 구조("Header(27)+Data(N)+Tail(4)")를 그대로 따른다고
 * 가정한다 — Data 영역이 헤더 직후([SpeedGateProtocolConstants.HEADER_LENGTH])부터 시작하고,
 * [GatePacket.dataCount]개의 36바이트 로그 엔트리가 이어진다. 사용자가 제공한 실수신 샘플은
 * "로그 엔트리 36바이트 자체"만 검증됐고(`LogEventCodecTest` 참고), 그 엔트리를 감싼 **완전한
 * GATE_LOG 패킷(헤더 포함)**의 실물 샘플로는 아직 검증되지 않았다 — 실제 장비의 완전한 0x61 패킷을
 * 확보하는 대로 이 가정을 재검증해야 한다(`docs/작업일지.md` 참고).
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

    /** `DefaultGatePacketHandler`가 `GATE_LOG` 패킷 수신 시 호출한다. */
    fun handle(dtlIp: String, packet: GatePacket) {
        val entries = decodeEntries(dtlIp, packet)
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

    private fun decodeEntries(dtlIp: String, packet: GatePacket): List<LogEventCodec.LogEvent> {
        val dataStart = SpeedGateProtocolConstants.HEADER_LENGTH
        val availableForData = packet.raw.size - dataStart - SpeedGateProtocolConstants.TAIL_LENGTH
        if (availableForData < SpeedGateProtocolConstants.LOG_ENTRY_LENGTH) {
            logger.warn("커넥션[{}] GATE_LOG 패킷이 로그 엔트리 1건보다 짧습니다: size={}", dtlIp, packet.raw.size)
            return emptyList()
        }

        // dataCount가 실제 수신 바이트로 커버 가능한 엔트리 수보다 크면(손상/잘린 패킷), 안전하게
        // 커버 가능한 만큼만 디코딩한다 — PacketDiffer.laneOffsetsOf가 GATE_STATUS에서 취하는 것과
        // 동일한 방어적 태도.
        val maxEntriesByLength = availableForData / SpeedGateProtocolConstants.LOG_ENTRY_LENGTH
        val entryCount = packet.dataCount.coerceIn(0, maxEntriesByLength)
        if (entryCount < packet.dataCount) {
            logger.warn(
                "커넥션[{}] GATE_LOG dataCount({})가 실제 수신 바이트로 커버 가능한 엔트리 수({})보다 큽니다 — {}건만 디코딩합니다.",
                dtlIp, packet.dataCount, maxEntriesByLength, entryCount,
            )
        }
        if (entryCount <= 0) return emptyList()

        val data = packet.raw.copyOfRange(dataStart, dataStart + entryCount * SpeedGateProtocolConstants.LOG_ENTRY_LENGTH)
        return try {
            LogEventCodec.decodeAll(data, entryCount)
        } catch (ex: IllegalArgumentException) {
            logger.warn("커넥션[{}] GATE_LOG 엔트리 디코딩 실패", dtlIp, ex)
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
