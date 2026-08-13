package kr.co.securance.secuhub.server.db

import kr.co.securance.secuhub.common.util.HexCodec
import kr.co.securance.secuhub.domain.entity.DataReceive
import kr.co.securance.secuhub.domain.entity.DataReceiveAck
import kr.co.securance.secuhub.domain.entity.DataReceiveAnalysis
import kr.co.securance.secuhub.domain.entity.DataReceiveFail
import kr.co.securance.secuhub.domain.repository.DataReceiveAckRepository
import kr.co.securance.secuhub.domain.repository.DataReceiveAnalysisRepository
import kr.co.securance.secuhub.domain.repository.DataReceiveFailRepository
import kr.co.securance.secuhub.domain.repository.DataReceiveRepository
import kr.co.securance.secuhub.protocol.GatePacket
import kr.co.securance.secuhub.protocol.GateStatusAnalyzer
import kr.co.securance.secuhub.protocol.SpeedGateProtocolConstants
import kr.co.securance.secuhub.server.connection.GateConnectionState
import kr.co.securance.secuhub.server.control.GateFaultCategory
import kr.co.securance.secuhub.server.control.GateFaultResolutionService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 수신 패킷을 객체 코드별로 분기해 DB에 적재한다(계획서 3.5절 "DB 쓰기 파이프라인").
 *
 * 레거시 `SpeedServer.ProcessReceiveData`의 `switch (sObjCode)` 분기 + `ClsPacketAnalyzer`
 * 저장 경로에 대응한다. 실제 쓰기는 전부 [GateDbWriteQueue]에 위임하므로 이 클래스의 메서드는
 * **즉시 반환**하며, 커넥션의 이벤트루프/액터 스레드를 DB I/O로 막지 않는다.
 *
 * `loc_id`/`grp_id`/`dtl_id`는 커넥션 수립 시 캐시한 [GateConnectionState.laneInfo]에서만 읽는다 —
 * 패킷마다 `tb_gate_dtl`을 재조회하던 레거시 N+1 패턴(M-8)을 재현하지 않기 위함이다.
 */
@Component
class GatePacketPersister(
    private val dbWriteQueue: GateDbWriteQueue,
    private val dataReceiveRepository: DataReceiveRepository,
    private val dataReceiveAckRepository: DataReceiveAckRepository,
    private val dataReceiveFailRepository: DataReceiveFailRepository,
    private val dataReceiveAnalysisRepository: DataReceiveAnalysisRepository,
    private val faultResolutionService: GateFaultResolutionService,
) {
    private val logger = LoggerFactory.getLogger(GatePacketPersister::class.java)

    /**
     * 객체 코드별 상세 저장. 저장 대상이 없는(정보성) 패킷이면 false를 반환한다.
     *
     * @param laneNo 이 패킷의 대표 레인 번호(상태 블록 첫 바이트).
     */
    fun persistReceivedPacket(state: GateConnectionState, packet: GatePacket, laneNo: Int): Boolean {
        val objectCode = packet.objectCode
        val raw = packet.raw
        return when (objectCode) {
            // 게이트 상태(0x4D) — 가장 고빈도. 원시 패킷을 tb_data_rcv에 적재한다.
            SpeedGateProtocolConstants.ObjectCode.GATE_STATUS -> {
                enqueueReceiveInsert(state, raw, laneNo, "상태 수신 데이터 저장")
                true
            }

            // 설정/모터/스케줄/휴일 — 레거시 BUG-02(저장 호출 누락으로 콘텐츠 영구 유실) 대응 분기.
            // 현재 스키마에서는 네 종류 모두 tb_data_rcv 한 곳에 원시 패킷으로 적재한다
            // (레거시의 tb_data_rcv_motor/schedule/holiday 분리 테이블은 계획서 4.2절에서 통합됨).
            SpeedGateProtocolConstants.ObjectCode.GATE_SETTING,
            SpeedGateProtocolConstants.ObjectCode.GATE_MOTOR,
            SpeedGateProtocolConstants.ObjectCode.TIME_ZONE,
            SpeedGateProtocolConstants.ObjectCode.HOLIDAY,
            -> {
                enqueueReceiveInsert(state, raw, laneNo, "패킷 콘텐츠 저장(objectCode=0x%02X)".format(objectCode))
                true
            }

            // 게이트 로그(0x61)는 여기서 다루지 않는다 — DefaultGatePacketHandler가 GATE_STATUS에
            // 내장된 로그 구간(핵심 경로) 또는 독립 GATE_LOG 패킷(하위 호환 경로) 모두를
            // GateLogService로 직접 라우팅하고, 이 메서드(persistReceivedPacket)는 그 경우 아예
            // 호출되지 않는다(DefaultGatePacketHandler.handle의 objectCode 분기 참고). 예전에는 이
            // 클래스에도 별도의 GATE_LOG 처리 분기(persistLogEntries → tb_gate_log_event)가 있었지만,
            // GateLogService 도입(2026-08-07 레이아웃 정정) 이후 도달 불가능한 죽은 코드로 남아있었다
            // — 실사용되지 않는 중복 저장 경로였음을 2026-08-12에 확인하고 제거했다(작업일지 0011).

            else -> {
                // 레거시 default 분기와 동일하게, 최소한 원본을 로그에 남겨 사후 수동 복구가 가능하게 한다.
                logger.warn(
                    "커넥션[{}] 미매핑 objectCode=0x{} 패킷 — 원본(복구용): {}",
                    state.dtlIp, "%02X".format(objectCode), HexCodec.toHex(raw),
                )
                false
            }
        }
    }

    /**
     * 게이트가 보낸 ACK(CMD1 0x07/0x08) 저장 — `tb_data_rcv_ack`.
     * 레거시와 달리 커넥션 캐시를 쓰므로 ACK 패킷마다 발생하던 재조회가 없다.
     */
    fun persistAck(state: GateConnectionState, raw: ByteArray, laneNo: Int) {
        val info = state.laneInfoOf(laneNo) ?: state.primaryLaneInfo
        val hex = HexCodec.toHex(raw)
        val ackDate = LocalDateTime.now().format(TIMESTAMP_FORMAT)

        dbWriteQueue.enqueue(
            GateDbWriteTask(partitionKey = state.dtlIp, operationName = "InsertReceiveAck(${state.dtlIp},$laneNo)") {
                dataReceiveAckRepository.save(
                    DataReceiveAck(
                        ackDate = ackDate,
                        dtlIp = state.dtlIp,
                        dtlLaneNo = laneNo,
                        dtlId = info?.dtlId,
                        ackRaw = hex,
                    ),
                )
                Unit
            },
        )
    }

    /**
     * 체크섬 불일치 등으로 신뢰할 수 없는 패킷을 `tb_data_rcv_fail`(Dead-letter)에 남긴다.
     *
     * 레거시는 체크섬 불일치 패킷을 경고 로그만 남기고 폐기해 사후 분석이 불가능했다.
     * 통신선 노이즈/펌웨어 버그를 추적하려면 원본이 남아야 하므로 DB에 기록한다.
     * 레인 번호는 패킷 파싱 자체를 신뢰할 수 없으므로 기록하지 않는다(null).
     */
    fun persistChecksumFailure(dtlIp: String, raw: ByteArray) {
        val hex = HexCodec.toHex(raw)
        val failDate = LocalDateTime.now().format(TIMESTAMP_FORMAT)

        dbWriteQueue.enqueue(
            GateDbWriteTask(partitionKey = dtlIp, operationName = "InsertReceiveFail($dtlIp)") {
                dataReceiveFailRepository.save(
                    DataReceiveFail(failDate = failDate, dtlIp = dtlIp, dtlLaneNo = null, rcvRaw = hex),
                )
                Unit
            },
        )
    }

    /**
     * 상태 패킷(0x4D)을 레인별로 분석해 `tb_data_rcv_anal`에 적재한다(2차 스프린트 2번 항목).
     *
     * ### 레거시와의 차이
     * 레거시는 이 분석을 `tb_data_rcv` AFTER INSERT 트리거(`utrg_data_rcv_anlz`)로 수행했다.
     * 트리거는 고정 오프셋만 써서 **1번 레인만** 분석했고, 수신 INSERT 트랜잭션 안에서 동기
     * 실행되어 쓰기 지연을 키웠다. 여기서는 [GateStatusAnalyzer]가 레인 수만큼 반복 분석하고,
     * DB 쓰기는 [GateDbWriteQueue]에 위임한다.
     *
     * ### 적재 조건
     * 레거시 트리거의 바깥 조건(`obj='4D' AND ERROR CHECK='03'`)을 그대로 지킨다 — ERROR CHECK가
     * 3(장애)인 레인만 행을 만들고, 그 외에는 장비가 장애를 스스로 해제한 것으로 보고
     * `resolve_yn`을 갱신한다. 정상 상태까지 전부 적재하면 초당 수십 건 × 게이트 수만큼
     * 행이 쌓여 분석 테이블이 사용 불가능해지기 때문이다(레거시가 이 조건을 둔 이유).
     */
    fun persistStatusAnalysis(state: GateConnectionState, raw: ByteArray) {
        val analyses = GateStatusAnalyzer.analyze(raw)
        if (analyses.isEmpty()) return

        val now = LocalDateTime.now()
        val analDate = now.format(RCV_DATE_FORMAT)
        val rawHex = HexCodec.toHex(raw)
        val laneCount = analyses.size

        for (analysis in analyses) {
            val laneNo = analysis.laneNumber
            val info = state.laneInfoOf(laneNo)
            // tb_gate_dtl에 없는 레인은 화면 조인에서 보이지 않으므로 적재하지 않는다
            // (레거시도 loc_id/grp_id 조회 실패 시 NULL로 넣어 조회 불가 행을 만들었다).
            if (info == null) {
                logger.debug("커넥션[{}] 레인 {}이(가) tb_gate_dtl에 없어 분석 적재를 건너뜁니다.", state.dtlIp, laneNo)
                continue
            }
            // analysis_yn='N' 레인은 분석 대상에서 제외한다(레거시 SelectGateList 필터와 동일).
            if (!info.analysisYn) continue

            if (analysis.errType == GateStatusAnalyzer.ErrorCheck.ERROR) {
                enqueueAnalysisInsert(
                    state,
                    analysis,
                    info.locId,
                    info.grpId,
                    info.dtlId,
                    info.dtlType,
                    info.dtlName,
                    analDate,
                    rawHex,
                    laneCount,
                )
            } else {
                enqueueRecovery(state, analysis)
            }
        }
    }

    /** 장애 분석 1건을 `tb_data_rcv_anal`에 적재한다. */
    private fun enqueueAnalysisInsert(
        state: GateConnectionState,
        analysis: GateStatusAnalyzer.LaneStatusAnalysis,
        locId: Long,
        grpId: Long,
        dtlId: Long?,
        dtlType: Int,
        dtlName: String?,
        analDate: String,
        rawHex: String,
        laneCount: Int,
    ) {
        val entity = DataReceiveAnalysis(
            analDate = analDate,
            analTp = analysis.analysisType.name,
            dtlIp = state.dtlIp,
            dtlLaneNo = analysis.laneNumber,
            dtlType = dtlType,
            dtlId = dtlId ?: 0,
            locId = locId,
            grpId = grpId,
            rcvDate = analDate,
            rcvRaw = rawHex,
            analData = analysis.operationStatusHex,
            objCd = "%02X".format(SpeedGateProtocolConstants.ObjectCode.GATE_STATUS),
            // desc_data_info_length/desc_gate_name/desc_gate_ip는 레거시 트리거가 항상 채우던 필드인데
            // 이 엔티티 도입 초기에는 매핑이 누락돼 빈 문자열로만 저장되고 있었다(2026-08-14 실 DB
            // 조회로 확인 — anal_id=856773 등 secuhub가 쓴 행만 이 세 컬럼이 비어 있었다).
            descDataInfoLength = SpeedGateProtocolConstants.DATA_INFO_LENGTH.toString(),
            descGateName = dtlName ?: "",
            descGateIp = state.dtlIp,
            descGateLaneCount = laneCount.toString(),
            descGateLaneNumber = analysis.laneNumber.toString(),
            descGateType = GateStatusAnalyzer.describeGateType(analysis.gateType),
            descUserMode = GateStatusAnalyzer.describeUserMode(analysis.userMode),
            descSecurityMode = GateStatusAnalyzer.describeSecurityMode(analysis.securityMode),
            descInoutTime = analysis.inoutTime.toString(),
            descUserCount = analysis.userCount.toString(),
            descTotalCount = analysis.totalCount.toString(),
            descOperation01 = analysis.descOperation[0],
            descOperation02 = analysis.descOperation[1],
            descOperation03 = analysis.descOperation[2],
            descOperation04 = analysis.descOperation[3],
            descSafety01 = analysis.descSafety[0],
            descSafety02 = analysis.descSafety[1],
            descSafety03 = analysis.descSafety[2],
            descSafety04 = analysis.descSafety[3],
            descOperation05 = analysis.descOperation2[0],
            descOperation06 = analysis.descOperation2[1],
            descOperation07 = analysis.descOperation2[2],
            descOperation08 = analysis.descOperation2[3],
            descMotorCount = analysis.motorCount,
            descMasterInTotal = analysis.masterInTotal,
            descGateStatus01 = analysis.descGateStatus[0],
            descGateStatus02 = analysis.descGateStatus[1],
            descGateStatus03 = analysis.descGateStatus[2],
            descGateStatus04 = analysis.descGateStatus[3],
            descGateStatus05 = analysis.descGateStatus[4],
            descGateStatus06 = analysis.descGateStatus[5],
            descGateStatus07 = analysis.descGateStatus[6],
            descGateStatus08 = analysis.descGateStatus[7],
            descGateStatus09 = analysis.descGateStatus[8],
            descGateStatus10 = analysis.descGateStatus[9],
            descGateStatus11 = analysis.descGateStatus[10],
            descGateStatus12 = analysis.descGateStatus[11],
            errType = analysis.errType,
            resolveYn = analysis.resolveYn,
        )

        dbWriteQueue.enqueue(
            GateDbWriteTask(
                partitionKey = state.dtlIp,
                operationName = "InsertReceiveAnal(${state.dtlIp},${analysis.laneNumber},${analysis.analysisType})",
            ) {
                dataReceiveAnalysisRepository.save(entity)
                Unit
            },
        )
    }

    /**
     * 장비가 장애 비트를 내린 레인의 미해결 장애를 자동 해제한다 —
     * 레거시 트리거 `utrg_data_rcv_anlz`의 `ELSE` 분기(센서/화재/모터 복구)에 대응한다.
     *
     * 분류별로 "해당 장애 신호가 실제로 사라졌는지"를 각각 확인한다. 하나의 상태 패킷으로
     * 모든 장애를 뭉뚱그려 해제하면, 예컨대 센서만 복구되고 모터는 여전히 고장 난 게이트가
     * 화면에서 정상으로 보이게 된다.
     */
    private fun enqueueRecovery(state: GateConnectionState, analysis: GateStatusAnalyzer.LaneStatusAnalysis) {
        val categories = buildList {
            // ERROR CHECK가 0으로 내려갔다 = 센서 계열 장애 해소(레거시 SUBSTR(...,227,2)='00' 조건).
            if (analysis.errType == GateStatusAnalyzer.ErrorCheck.NORMAL) add(GateFaultCategory.SENSOR)
            if (analysis.descFireAlarm.isBlank()) add(GateFaultCategory.FIRE)
            if (analysis.descMainMotorError.isBlank() && analysis.descSlaveMotorError.isBlank()) {
                add(GateFaultCategory.MOTOR)
            }
        }
        if (categories.isEmpty()) return

        dbWriteQueue.enqueue(
            GateDbWriteTask(
                partitionKey = state.dtlIp,
                operationName = "ResolveRecovery(${state.dtlIp},${analysis.laneNumber})",
            ) {
                categories.forEach { category ->
                    faultResolutionService.resolveByRecovery(state.dtlIp, analysis.laneNumber, category)
                }
            },
        )
    }

    /** `tb_data_rcv` 적재 작업을 파티션 큐에 넣는다(Header/Data/Tail 구간을 나눠 저장). */
    private fun enqueueReceiveInsert(
        state: GateConnectionState,
        raw: ByteArray,
        laneNo: Int,
        operationName: String,
    ) {
        val info = state.laneInfoOf(laneNo) ?: state.primaryLaneInfo
        val rcvDate = LocalDateTime.now().format(RCV_DATE_FORMAT)

        val headerEnd = minOf(SpeedGateProtocolConstants.HEADER_LENGTH, raw.size)
        val tailStart = maxOf(raw.size - SpeedGateProtocolConstants.TAIL_LENGTH, headerEnd)
        val header = HexCodec.toHex(raw.copyOfRange(0, headerEnd))
        val body = HexCodec.toHex(raw.copyOfRange(headerEnd, tailStart))
        val tail = HexCodec.toHex(raw.copyOfRange(tailStart, raw.size))

        dbWriteQueue.enqueue(
            GateDbWriteTask(partitionKey = state.dtlIp, operationName = "$operationName(${state.dtlIp},$laneNo)") {
                dataReceiveRepository.save(
                    DataReceive(
                        rcvDate = rcvDate,
                        dtlIp = state.dtlIp,
                        dtlLaneNo = laneNo,
                        dtlType = info?.dtlType ?: state.gateTypeCode,
                        dtlId = info?.dtlId,
                        locId = info?.locId,
                        grpId = info?.grpId,
                        rcvHeader = header,
                        rcvData = body,
                        rcvTail = tail,
                    ),
                )
                Unit
            },
        )
    }

    companion object {
        /** `tb_data_rcv.rcv_date` — 스키마 주석 규정 포맷(분 단위). */
        private val RCV_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmm")

        /** ACK/실패 기록은 동일 분 내 다건이 흔하므로 초 단위까지 남긴다. */
        private val TIMESTAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
    }
}
