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
import kr.co.securance.secuhub.domain.repository.GateDetailRepository
import kr.co.securance.secuhub.domain.repository.GateLaneInfo
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
 * `loc_id`/`grp_id`/`dtl_id`는 원칙적으로 커넥션 수립 시 캐시한 [GateConnectionState.laneInfo]에서
 * 읽는다 — 패킷마다 `tb_gate_dtl`을 재조회하던 레거시 N+1 패턴(M-8)을 재현하지 않기 위함이다.
 * 유일한 예외는 [persistStatusAnalysis]가 `tb_data_rcv_anal`에 실제로 새 행을 INSERT하는 순간
 * ([resolveLaneIdentity]) — 캐시 값이 설정 변경을 반영하지 못하는 문제(2026-08-14 확인)를 막기
 * 위해 그 순간만 `tb_gate_dtl`을 다시 조회한다. INSERT는 상태 변경/신규 하루 단위로만 일어나므로
 * 상태 패킷(최고빈도) 자체의 N+1로 이어지지는 않는다.
 */
@Component
class GatePacketPersister(
    private val dbWriteQueue: GateDbWriteQueue,
    private val dataReceiveRepository: DataReceiveRepository,
    private val dataReceiveAckRepository: DataReceiveAckRepository,
    private val dataReceiveFailRepository: DataReceiveFailRepository,
    private val dataReceiveAnalysisRepository: DataReceiveAnalysisRepository,
    private val gateDetailRepository: GateDetailRepository,
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
     * 실행되어 쓰기 지연을 키웠다. 여기서는 [GateStatusAnalyzer]가 레인 수만큼(패킷의 `LOCAL GATE
     * LANE COUNT` 필드, 헤더+DataInfo 72번째 바이트) 반복 분석하고, DB 쓰기는 [GateDbWriteQueue]에
     * 위임한다.
     *
     * ### 적재 조건(2026-08-14 변경 — "당일 전체 상태 데이터 저장" 요구사항)
     * 원래는 레거시 트리거의 바깥 조건(`obj='4D' AND ERROR CHECK='03'`)을 그대로 지켜 ERROR
     * CHECK가 3(장애)인 레인만 행을 만들었다(정상 상태까지 전부 적재하면 초당 수십 건 × 게이트
     * 수만큼 행이 쌓여 테이블이 감당 못 하기 때문). 지금은 **레인 수만큼 모든 레인의 당일 상태를
     * 저장**하되, 무한정 쌓이지 않도록 [enqueueStatusUpsert]가 "직전 저장과 분석 결과 전체 필드가
     * 동일하면 새 행 대신 `rcv_date`만 갱신"하는 방식으로 정상 상태 레인의 증가를 막는다.
     * `err_type=3`(장애)만은 예외로, 요구사항대로 매번 새 행을 INSERT해 장애 이력을 전부 보존한다
     * (레거시의 "장애는 append-only 로그" 원칙을 그대로 유지).
     */
    fun persistStatusAnalysis(state: GateConnectionState, raw: ByteArray) {
        val analyses = GateStatusAnalyzer.analyze(raw)
        if (analyses.isEmpty()) return

        val now = LocalDateTime.now()
        val analDate = now.format(RCV_DATE_FORMAT)
        val rawHex = HexCodec.toHex(raw)
        val laneCount = analyses.size

        // `anal_header`/`anal_tail` — `tb_data_rcv.rcv_header`/`rcv_tail`([enqueueReceiveInsert])와
        // 동일한 Header/Tail 구간 분할을 이 원시 패킷에도 적용한다(2026-08-14 확인: 엔티티에 컬럼
        // 매핑은 있었지만 이 값을 실제로 채우는 코드가 없어 항상 NULL로 저장되고 있었다).
        val headerEnd = minOf(SpeedGateProtocolConstants.HEADER_LENGTH, raw.size)
        val tailStart = maxOf(raw.size - SpeedGateProtocolConstants.TAIL_LENGTH, headerEnd)
        val headerHex = HexCodec.toHex(raw.copyOfRange(0, headerEnd))
        val tailHex = HexCodec.toHex(raw.copyOfRange(tailStart, raw.size))
        // `anal_data_*` 33개 컬럼 중 헤더 바이트로 채울 수 있는 11개(2026-08-14, [DataReceiveAnalysis]
        // KDoc "레거시 anal_data_* 33개 컬럼" 참고) — 헤더가 27바이트 전부 도착하지 않은 손상 패킷이면
        // 필드별로 안전하게 빈 값으로 남긴다.
        val headerFields = AnalDataHeaderFields.from(raw)

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
                enqueueAnalysisInsert(state, analysis, info, analDate, rawHex, headerHex, tailHex, headerFields, laneCount)
            } else {
                enqueueStatusUpsert(state, analysis, info, analDate, rawHex, headerHex, tailHex, headerFields, laneCount)
                enqueueRecovery(state, analysis)
            }
        }
    }

    /**
     * `tb_data_rcv_anal.anal_data_*` 중 패킷 헤더(27바이트)에서 그대로 뽑아낼 수 있는 11개 필드.
     * [DataReceiveAnalysis]의 "레거시 `anal_data_*` 33개 컬럼" KDoc에 이 필드들이 무엇이고 왜
     * 이만큼만 채우는지 배경이 있다. 각 필드는 [SpeedGateProtocolConstants.HeaderOffset]과 1:1
     * 대응하며, 값은 원본 바이트의 hex 표현이다(레거시 프로시저가 정확히 hex로 저장했는지는
     * 확인할 수 없으나, 이 코드베이스의 다른 원본 보존 컬럼(`rcv_header`/`anal_header` 등)과
     * 동일한 규약을 따른다).
     */
    private data class AnalDataHeaderFields(
        val stx: String,
        val packetLen: String,
        val protocolVer: String,
        val frameOption: String,
        val address: String,
        val command: String,
        val subcommand: String,
        val objectCode: String,
        val infoLength: String,
        val count: String,
        val length: String,
    ) {
        companion object {
            val EMPTY = AnalDataHeaderFields("", "", "", "", "", "", "", "", "", "", "")

            fun from(raw: ByteArray): AnalDataHeaderFields {
                if (raw.size < SpeedGateProtocolConstants.HEADER_LENGTH) return EMPTY
                fun hex(start: Int, len: Int) = HexCodec.toHex(raw.copyOfRange(start, start + len))
                val o = SpeedGateProtocolConstants.HeaderOffset
                return AnalDataHeaderFields(
                    stx = hex(o.STX, 1),
                    packetLen = hex(o.PACKET_LENGTH, 2),
                    protocolVer = hex(o.PROTOCOL_VERSION, 1),
                    frameOption = hex(o.FRAME_OPTION, 2),
                    address = hex(o.ADDRESS, SpeedGateProtocolConstants.ADDRESS_LENGTH),
                    command = hex(o.COMMAND1, 1),
                    subcommand = hex(o.COMMAND2, 1),
                    objectCode = hex(o.OBJECT_CODE, 1),
                    infoLength = hex(o.DATA_INFO_LENGTH, 1),
                    count = hex(o.DATA_COUNT, 2),
                    length = hex(o.DATA_LENGTH, 2),
                )
            }
        }
    }

    /** 장애(`err_type=3`) 분석 1건을 `tb_data_rcv_anal`에 매번 새로 INSERT한다(장애 이력 전량 보존). */
    private fun enqueueAnalysisInsert(
        state: GateConnectionState,
        analysis: GateStatusAnalyzer.LaneStatusAnalysis,
        info: GateLaneInfo,
        analDate: String,
        rawHex: String,
        headerHex: String,
        tailHex: String,
        headerFields: AnalDataHeaderFields,
        laneCount: Int,
    ) {
        dbWriteQueue.enqueue(
            GateDbWriteTask(
                partitionKey = state.dtlIp,
                operationName = "InsertReceiveAnal(${state.dtlIp},${analysis.laneNumber},${analysis.analysisType})",
            ) {
                val identity = resolveLaneIdentity(state.dtlIp, analysis.laneNumber, info)
                val rcvId = resolveRcvId(state.dtlIp)
                dataReceiveAnalysisRepository.save(
                    buildAnalysisEntity(state, analysis, identity, analDate, rawHex, headerHex, tailHex, rcvId, headerFields, laneCount),
                )
                Unit
            },
        )
    }

    /**
     * 정상/이벤트/상태변경(`err_type != 3`) 레인의 "당일 전체 상태 데이터" upsert(2026-08-14
     * 신규 기능). 이 레인의 최신 행이 오늘 날짜이고 분석 결과 전체 필드가 동일하면 새 행 대신
     * `rcv_date`만 갱신하고, 그렇지 않으면(날짜가 바뀌었거나 데이터가 바뀌었으면) 새로 INSERT한다.
     *
     * [GateDbWriteQueue]의 멱등성 요구(클래스 KDoc "주의(멱등성)" — execute는 재시도로 두 번
     * 실행돼도 안전해야 한다)를 지키기 위해, "동일한지" 판단을 execute 블록 **안에서** 최신 행을
     * 다시 조회해 수행한다. 재시도로 이 작업이 중복 실행돼도 두 번째 실행은 첫 번째가 이미 반영한
     * 최신 행을 그대로 다시 보고 "동일하다"고 판단해 `rcv_date`만 한 번 더 갱신할 뿐, 중복 행을
     * 만들지 않는다.
     *
     * `latest.analDate`는 **갱신하지 않는다**(2026-08-14 Codex 리뷰 P2 지적) — 요구사항이 "동일한
     * 데이터는 수신일자만 갱신"이며, `anal_date`까지 매번 지금 시각으로 덮어쓰면 이 상태가 최초로
     * 기록된 시각이 사라져 D5 보존 삭제([DataReceiveAnalysisRepository.deleteBatchOlderThan])나
     * 분석 조회에서 "언제부터 이 상태였는지"가 아니라 "마지막으로 반복 수신한 시각"만 남게 된다.
     *
     * 반대로 `rcv_id`/`rcv_raw`/`anal_header`/`anal_tail`(이 수신을 식별하는 원본 패킷 출처 필드)은
     * `rcv_date`와 **함께 원자적으로 갱신한다**(Codex 적대적 리뷰 지적, 2026-08-14) — `rcv_date`만
     * 최신 시각으로 바꾸고 이 필드들을 최초 INSERT 시점 값에 그대로 두면, "이 행은 최신 시각에
     * 수신됐다"는 `rcv_date`와 실제로 가리키는 원본 패킷(`rcv_id`)이 서로 다른 수신 이벤트를
     * 가리키는 모순이 생겨 감사/장애 분석에서 원본 패킷을 잘못 역추적하게 된다.
     *
     * ### 식별정보(`dtl_type`/`dtl_name`/`loc_id`/`grp_id`) 최신화 주기 = 최대 1일(Codex 적대적
     * 리뷰 지적 대응)
     * [isSameContent]는 식별정보를 비교하지 않으므로([resolveLaneIdentity] KDoc 참고), 상태가
     * 정말로 하루 종일 한 번도 안 바뀌면 그 사이 [resolveLaneIdentity]도 호출되지 않는다. 하지만
     * 위에서 `analDate`를 절대 갱신하지 않기 때문에, 다음 날 첫 패킷에서는 `latest.analDate`가
     * 더 이상 그날의 `today`로 시작하지 않아 이 if 조건 자체가 거짓이 되고 else 분기(새 INSERT +
     * [resolveLaneIdentity])로 빠진다 — 즉 상태가 아무리 안 바뀌어도 **자정을 넘기면 무조건 한 번은
     * 식별정보가 재조회**되며, "무기한" 정체는 발생하지 않는다(worst-case staleness ≤ 하루).
     * 이 경계는 [GatePacketPersisterTest]의 "날짜가 바뀌면..." 테스트로 고정돼 있다 — 이 상한을
     * 더 줄이려면(예: 매 패킷 재조회) 최고빈도 상태 패킷 경로에 다시 N+1을 들이는 트레이드오프가
     * 필요하므로, 현재는 관리자의 설정 변경이 반영되는 지연을 최대 1일까지 허용하는 쪽을 택했다.
     */
    private fun enqueueStatusUpsert(
        state: GateConnectionState,
        analysis: GateStatusAnalyzer.LaneStatusAnalysis,
        info: GateLaneInfo,
        analDate: String,
        rawHex: String,
        headerHex: String,
        tailHex: String,
        headerFields: AnalDataHeaderFields,
        laneCount: Int,
    ) {
        val today = analDate.substring(0, 8) // yyyyMMdd

        dbWriteQueue.enqueue(
            GateDbWriteTask(
                partitionKey = state.dtlIp,
                operationName = "UpsertReceiveAnal(${state.dtlIp},${analysis.laneNumber},${analysis.analysisType})",
            ) {
                val latest = dataReceiveAnalysisRepository.findTopByDtlIpAndDtlLaneNoOrderByAnalIdDesc(
                    state.dtlIp,
                    analysis.laneNumber,
                )
                if (latest != null && latest.analDate.startsWith(today) && isSameContent(latest, analysis, laneCount)) {
                    // 동일 데이터 반복 — 새 행 없이 수신일자(rcv_date)만 갱신한다. anal_date는
                    // 이 상태가 최초로 기록된 시각을 보존하기 위해 건드리지 않는다(P2 지적).
                    //
                    // rcv_id/rcv_raw/anal_header/anal_tail은 rcv_date와 함께 원자적으로 갱신한다
                    // (Codex 적대적 리뷰 지적, 2026-08-14) — 이전에는 rcv_date만 최신 시각으로 바꾸고
                    // 이 필드들은 최초 INSERT 시점 값(과거 tb_data_rcv 행/원본 바이트)에 그대로
                    // 머물러 있어, "이 행은 최신 시각에 수신됐다"는 rcv_date와 실제로 가리키는 원본
                    // 패킷(rcv_id)이 서로 다른 시점을 가리키는 모순이 생겼다 — 운영자가 감사/장애
                    // 분석에서 rcv_date로 원본 패킷을 역추적하면 엉뚱한 과거 행과 대조하게 된다.
                    // rcvId는 enqueueReceiveInsert가 먼저 큐잉한 원시 INSERT를 가리키므로
                    // resolveRcvId로 다시 조회해야 이번 수신의 원본 행을 가리킨다(resolveRcvId KDoc 참고).
                    latest.rcvDate = analDate
                    latest.rcvId = resolveRcvId(state.dtlIp)
                    latest.rcvRaw = rawHex
                    latest.analHeader = headerHex
                    latest.analTail = tailHex
                    dataReceiveAnalysisRepository.save(latest)
                } else {
                    val identity = resolveLaneIdentity(state.dtlIp, analysis.laneNumber, info)
                    val rcvId = resolveRcvId(state.dtlIp)
                    dataReceiveAnalysisRepository.save(
                        buildAnalysisEntity(
                            state, analysis, identity, analDate, rawHex, headerHex, tailHex, rcvId, headerFields, laneCount,
                        ),
                    )
                }
                Unit
            },
        )
    }

    /**
     * [buildAnalysisEntity]가 채우는 필드 중 "분석 결과"에 해당하는 값만 비교한다 — `anal_id`/`anal_date`/
     * `rcv_date`/`rcv_raw`/`anal_data`(패킷 원본 — 헤더/체크섬 때문에 상태가 같아도 패킷마다
     * 달라진다)는 의도적으로 비교에서 제외한다(요구사항: "분석 결과 전체 필드 동일").
     *
     * `dtl_type`/`dtl_name`/`loc_id`/`grp_id`(식별정보)도 비교에서 제외한다(2026-08-14 Codex 리뷰
     * P1 지적 대응). 애초에는 이 필드들을 [resolveLaneIdentity] 재조회 없이 캐시된 [info]와
     * 비교했는데, `existing`(마지막으로 실제 저장된 행)의 식별정보는 그 저장 시점에
     * [resolveLaneIdentity]가 채운 **최신** 값인 반면 이 비교에 쓰는 [info]는 여전히 커넥션 수립
     * 시점의 **캐시** 값이라, 둘이 한 번이라도 어긋나면(=설정이 바뀌어 최신값이 캐시와 달라지면)
     * 그 뒤로는 내용이 완전히 같아도 이 필드들 때문에 영원히 불일치로 판정되어 매 패킷마다 새 행이
     * INSERT되는 문제가 있었다 — "동일 데이터 반복 시 날짜만 갱신"이라는 이번 기능의 목적 자체가
     * 무력화됨. 식별정보는 상태 그 자체(센서/모터/카운터 등)가 아니라 게이트 설정에 속하므로
     * 애초에 "분석 결과" 동일성 판정에 넣을 필요가 없고, 새 행이 INSERT될 때마다
     * [resolveLaneIdentity]가 최신값을 반영하므로 여기서 비교하지 않아도 최신화 자체는 계속된다.
     */
    private fun isSameContent(
        existing: DataReceiveAnalysis,
        analysis: GateStatusAnalyzer.LaneStatusAnalysis,
        laneCount: Int,
    ): Boolean =
        existing.analTp == analysis.analysisType.name &&
            existing.descGateLaneCount == laneCount.toString() &&
            existing.descGateLaneNumber == analysis.laneNumber.toString() &&
            existing.descGateType == GateStatusAnalyzer.describeGateType(analysis.gateType) &&
            existing.descUserMode == GateStatusAnalyzer.describeUserMode(analysis.userMode) &&
            existing.descSecurityMode == GateStatusAnalyzer.describeSecurityMode(analysis.securityMode) &&
            existing.descInoutTime == analysis.inoutTime.toString() &&
            existing.descUserCount == analysis.userCount.toString() &&
            existing.descTotalCount == analysis.totalCount.toString() &&
            existing.descOperation01 == analysis.descOperation[0] &&
            existing.descOperation02 == analysis.descOperation[1] &&
            existing.descOperation03 == analysis.descOperation[2] &&
            existing.descOperation04 == analysis.descOperation[3] &&
            existing.descSafety01 == analysis.descSafety[0] &&
            existing.descSafety02 == analysis.descSafety[1] &&
            existing.descSafety03 == analysis.descSafety[2] &&
            existing.descSafety04 == analysis.descSafety[3] &&
            existing.descOperation05 == analysis.descOperation2[0] &&
            existing.descOperation06 == analysis.descOperation2[1] &&
            existing.descOperation07 == analysis.descOperation2[2] &&
            existing.descOperation08 == analysis.descOperation2[3] &&
            existing.descMotorCount == analysis.motorCount &&
            existing.descMasterInTotal == analysis.masterInTotal &&
            existing.descGateStatus01 == analysis.descGateStatus[0] &&
            existing.descGateStatus02 == analysis.descGateStatus[1] &&
            existing.descGateStatus03 == analysis.descGateStatus[2] &&
            existing.descGateStatus04 == analysis.descGateStatus[3] &&
            existing.descGateStatus05 == analysis.descGateStatus[4] &&
            existing.descGateStatus06 == analysis.descGateStatus[5] &&
            existing.descGateStatus07 == analysis.descGateStatus[6] &&
            existing.descGateStatus08 == analysis.descGateStatus[7] &&
            existing.descGateStatus09 == analysis.descGateStatus[8] &&
            existing.descGateStatus10 == analysis.descGateStatus[9] &&
            existing.descGateStatus11 == analysis.descGateStatus[10] &&
            existing.descGateStatus12 == analysis.descGateStatus[11] &&
            existing.errType == analysis.errType &&
            existing.resolveYn == analysis.resolveYn

    /**
     * `dtl_type`/`dtl_name`/`loc_id`/`grp_id`를 실제 행을 저장(INSERT)하는 순간에 `tb_gate_dtl`에서
     * 다시 조회한다(2026-08-14 코드 리뷰 지적 대응).
     *
     * [GateConnectionState.laneInfo]는 커넥션 수립 시 1회만 캐시하므로(고빈도 원시 수신 경로의
     * N+1 회피), 그 사이 관리자가 `tb_gate_dtl.dtl_type` 등을 바꿔도 게이트가 재접속하기 전까지
     * 반영되지 않는다 — 실 DB 조회로 확인된 사례: `dtl_id=159`의 `dtl_type`이 2026-03-31에 1로
     * 바뀌었는데, 그 이후(8월)에 커넥션이 재접속 없이 계속 살아있던 탓에 분석 행에는 여전히
     * 접속 당시 캐시값(2)이 저장되고 있었다.
     *
     * 이 메서드는 실제로 새 행을 INSERT하는 시점([enqueueAnalysisInsert]의 장애 경로, 또는
     * [enqueueStatusUpsert]가 "데이터가 바뀌었다"고 판단한 경우)에만 호출된다 — 정상 상태가
     * 반복되는 대부분의 패킷은 [isSameContent]가 캐시값만으로 비교해 `rcv_date`만 갱신하고 여기
     * 도달하지 않으므로, 상태 패킷(최고빈도 objectCode)마다 `tb_gate_dtl`을 재조회하는 N+1 패턴을
     * 재현하지 않는다. 조회 결과가 없으면(레인이 그 사이 삭제된 경우 등) 캐시값으로 안전하게
     * 폴백한다.
     */
    private fun resolveLaneIdentity(dtlIp: String, laneNo: Int, cached: GateLaneInfo): GateLaneInfo {
        val live = gateDetailRepository.findByDtlIpAndDtlLaneNo(dtlIp, laneNo) ?: return cached
        return cached.copy(
            locId = live.location.locId ?: cached.locId,
            grpId = live.group.grpId ?: cached.grpId,
            dtlId = live.dtlId,
            dtlType = live.dtlType,
            dtlName = live.dtlName,
        )
    }

    /**
     * `tb_data_rcv_anal.rcv_id` — 이 상태 패킷이 [enqueueReceiveInsert]로 함께 적재한 원본
     * `tb_data_rcv` 행의 PK를 찾아 채운다(2026-08-14 코드 리뷰 지적: 이전에는 항상 0으로 고정돼
     * 두 테이블 간 추적이 불가능했다). 같은 파티션 키(dtlIp)로 큐잉되는 원시 INSERT 작업이 이
     * 분석 INSERT 작업보다 먼저 enqueue되고, [GateDbWriteQueue]가 파티션 내 실행 순서를
     * 보장하므로 정상 경로에서는 이 조회가 방금 저장된 원시 행을 찾는다. 못 찾으면(레코드가
     * 아직 없거나 드문 재시도 경합) 0으로 폴백한다 — 엔티티 KDoc이 이미 0을 허용값으로 규정한다.
     *
     * **레인으로 필터링하지 않는다**(2026-08-14 재검토로 발견한 버그 수정 — [DataReceiveRepository]
     * KDoc 참고) — `tb_data_rcv`는 원시 패킷 1건당 대표 레인 하나로만 태그된 행 1건을 만드는 반면,
     * [persistStatusAnalysis]는 같은 원시 패킷에서 레인 수만큼 여러 분석 행을 만든다. 레인 번호로
     * 필터링하면 대표 레인이 아닌 레인들은 방금 저장된 원시 행을 절대 찾지 못하고 무관한 과거
     * 값을 잘못 가져왔다 — 같은 패킷에서 나온 분석 행은 전부 같은 원시 행을 가리켜야 한다.
     */
    private fun resolveRcvId(dtlIp: String): Long =
        dataReceiveRepository.findTopByDtlIpOrderByRcvIdDesc(dtlIp)?.rcvId ?: 0

    /** [analysis]/[identity]로부터 `tb_data_rcv_anal` 1행(엔티티)을 만든다 — INSERT 경로 전용 공통 로직. */
    private fun buildAnalysisEntity(
        state: GateConnectionState,
        analysis: GateStatusAnalyzer.LaneStatusAnalysis,
        identity: GateLaneInfo,
        analDate: String,
        rawHex: String,
        headerHex: String,
        tailHex: String,
        rcvId: Long,
        headerFields: AnalDataHeaderFields,
        laneCount: Int,
    ): DataReceiveAnalysis = DataReceiveAnalysis(
        analDate = analDate,
        analTp = analysis.analysisType.name,
        dtlIp = state.dtlIp,
        dtlLaneNo = analysis.laneNumber,
        dtlType = identity.dtlType,
        dtlId = identity.dtlId ?: 0,
        locId = identity.locId,
        grpId = identity.grpId,
        rcvDate = analDate,
        rcvId = rcvId,
        rcvRaw = rawHex,
        analHeader = headerHex,
        analData = analysis.operationStatusHex,
        analTail = tailHex,
        objCd = "%02X".format(SpeedGateProtocolConstants.ObjectCode.GATE_STATUS),
        analDataStx = headerFields.stx,
        analDataPacketLen = headerFields.packetLen,
        analDataProtocolVer = headerFields.protocolVer,
        analDataFrameOption = headerFields.frameOption,
        analDataAddress = headerFields.address,
        analDataCommand = headerFields.command,
        analDataSubcommand = headerFields.subcommand,
        analDataObjectCode = headerFields.objectCode,
        analDataInfoLength = headerFields.infoLength,
        analDataCount = headerFields.count,
        analDataLength = headerFields.length,
        analDataGateName = identity.dtlName ?: "",
        analDataIp = state.dtlIp,
        // desc_data_info_length/desc_gate_name/desc_gate_ip는 레거시 트리거가 항상 채우던 필드인데
        // 이 엔티티 도입 초기에는 매핑이 누락돼 빈 문자열로만 저장되고 있었다(2026-08-14 실 DB
        // 조회로 확인 — anal_id=856773 등 secuhub가 쓴 행만 이 세 컬럼이 비어 있었다).
        descDataInfoLength = SpeedGateProtocolConstants.DATA_INFO_LENGTH.toString(),
        descGateName = identity.dtlName ?: "",
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
