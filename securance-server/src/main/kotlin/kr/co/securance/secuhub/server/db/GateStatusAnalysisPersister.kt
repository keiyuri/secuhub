package kr.co.securance.secuhub.server.db

import kotlinx.coroutines.Deferred
import kr.co.securance.secuhub.common.util.HexCodec
import kr.co.securance.secuhub.domain.entity.DataReceiveAnalysis
import kr.co.securance.secuhub.domain.repository.DataReceiveAnalysisRepository
import kr.co.securance.secuhub.domain.repository.DataReceiveRepository
import kr.co.securance.secuhub.domain.repository.GateDetailRepository
import kr.co.securance.secuhub.domain.repository.GateLaneInfo
import kr.co.securance.secuhub.protocol.GateStatusAnalyzer
import kr.co.securance.secuhub.protocol.SpeedGateProtocolConstants
import kr.co.securance.secuhub.server.connection.GateConnectionState
import kr.co.securance.secuhub.server.control.GateFaultCategory
import kr.co.securance.secuhub.server.control.GateFaultResolutionService
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong

/**
 * 상태 패킷(0x4D)의 레인별 분석 결과를 `tb_data_rcv_anal`에 적재하는 로직 전담(2026-08-25 소스
 * 전수 검토 지적 대응 — [GatePacketPersister] 907줄 중 이 부분(구 159~824행)만 6~7개 관심사 중
 * 하나를 이미 500줄 가까이 차지하고 있어, 수신/ACK/실패 저장과 분리해도 그 자체로 응집도 있는
 * 하나의 클래스가 된다).
 *
 * [GatePacketPersister]가 생성자에서 이 클래스를 직접 만들어 [GatePacketPersister.persistStatusAnalysis]에서
 * 위임하는 구조다 — Spring 빈으로 별도 등록/주입하지 않는 이유는, 아래 [sharedRcvId] 관련 로직처럼
 * 패킷 1건 처리 범위에서만 의미 있는 상태를 감춰야 하고, 기존 테스트(`GatePacketPersisterTest`,
 * `DefaultGatePacketHandlerAckTest.CountingPersister`)가 [GatePacketPersister]의 7개 인자 생성자와
 * `persistStatusAnalysis`를 오버라이드하는 공개 API로만 접근하므로 이 클래스는 내부 구현
 * 세부사항으로 남겨 호출부/테스트 변경 없이 순수하게 코드만 옮길 수 있기 때문이다.
 *
 * 이하 메서드들의 동작·주석은 분리 전 [GatePacketPersister]에 있던 내용을 그대로 옮긴 것이며,
 * 여러 차례의 코드 리뷰/Codex 적대적 리뷰 지적(D-1, M-1, P1, P2 등)이 이미 반영된 결과이므로
 * 로직을 바꾸지 않았다.
 */
internal class GateStatusAnalysisPersister(
    private val dbWriteQueue: GateDbWriteQueue,
    private val dataReceiveRepository: DataReceiveRepository,
    private val dataReceiveAnalysisRepository: DataReceiveAnalysisRepository,
    private val gateDetailRepository: GateDetailRepository,
    private val faultResolutionService: GateFaultResolutionService,
) {
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
     *
     * @param rcvIdDeferred (코드 리뷰 지적 D-1) 이 원시 패킷을 `enqueueReceiveInsert`가 적재한
     *   `tb_data_rcv` 행의 `rcv_id` — [DefaultGatePacketHandler]가 [GatePacketPersister.persistReceivedPacket]의
     *   반환값을 그대로 넘겨준다. 넘기면 [resolveRcvId]의 "가장 최신 행 재조회"(다른 패킷이
     *   끼어들면 엉뚱한 원본을 가리킬 수 있다) 대신 이번 수신의 원본 행을 정확히 가리킨다. null이면
     *   기존처럼 [resolveRcvId]로 폴백한다(테스트 등 이 흐름 밖에서 직접 호출하는 경우).
     */
    fun persist(state: GateConnectionState, raw: ByteArray, rcvIdDeferred: Deferred<Long?>? = null) {
        val analyses = GateStatusAnalyzer.analyze(raw)
        if (analyses.isEmpty()) return

        val now = LocalDateTime.now()
        val analDate = now.format(RCV_DATE_FORMAT)
        val rawHex = HexCodec.toHex(raw)
        val laneCount = analyses.size

        // `anal_header`/`anal_tail` — `tb_data_rcv.rcv_header`/`rcv_tail`(GatePacketPersister.enqueueReceiveInsert)와
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
        // anal_data_gate_lane_count — DataInfo의 `LOCAL GATE LANE COUNT` 원시 바이트(오프셋
        // Header(27)+DataInfo(45)-1=71, PacketDiffer.LANE_COUNT_OFFSET과 동일 위치)를 hex로 보존한다.
        // laneCount(위 laneAnalyses.size, 디코딩된 정수값 → desc_gate_lane_count)와는 별개로,
        // 이 필드는 usp_process_analysis 규약을 따라 raw byte 그대로 담는다.
        val laneCountOffset = SpeedGateProtocolConstants.HEADER_LENGTH + SpeedGateProtocolConstants.DATA_INFO_LENGTH - 1
        val laneCountHex = if (raw.size > laneCountOffset) "%02X".format(raw[laneCountOffset].toInt() and 0xFF) else ""

        // 이 패킷에서 나온 모든 레인의 분석 행은 같은 tb_data_rcv 원본 행(rcv_id)을 가리켜야 한다
        // ([resolveRcvId] KDoc 참고). 레인마다 독립적으로 재조회하면 레인 수만큼 동일한 SELECT가
        // 그대로 반복된다(2026-08-20 Opus 전체 리뷰 지적 — 상태 upsert 경로의 쿼리 증폭, 최고빈도
        // 경로에서 레인당 findTopByDtlIpAndDtlLaneNoOrderByAnalIdDesc + resolveRcvId 2회 SELECT).
        // 같은 partitionKey(dtlIp)로 큐잉되는 레인 태스크들은 [GateDbWriteQueue] 설계상 같은 샤드
        // 워커가 순서대로 처리하므로, 먼저 실행된 레인이 계산한 값을 뒤 레인들이 그대로 재사용하면
        // 된다. AtomicLong으로 감싸는 이유는 오직 하나 — 타임아웃으로 "버려진" 시도가 백그라운드에서
        // 뒤늦게 완료되는 사이 다음 레인 태스크가 이미 시작되는 드문 경합([GateDbWriteQueue] KDoc
        // "타임아웃 판정 이후 버려둔 호출" 참고)에서도 대입 자체는 원자적이도록 하기 위함이다 —
        // 이 경우에도 최악은 동일한 조회가 한두 번 더 도는 것뿐, 서로 다른 값이 섞이지는 않는다.
        val sharedRcvId = AtomicLong(UNRESOLVED_RCV_ID)

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
                    state, analysis, info, analDate, rawHex, headerHex, tailHex, headerFields, laneCount, laneCountHex,
                    rcvIdDeferred, sharedRcvId,
                )
            } else {
                enqueueStatusUpsert(
                    state, analysis, info, analDate, rawHex, headerHex, tailHex, headerFields, laneCount, laneCountHex,
                    rcvIdDeferred, sharedRcvId,
                )
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
        laneCountHex: String,
        rcvIdDeferred: Deferred<Long?>?,
        sharedRcvId: AtomicLong,
    ) {
        dbWriteQueue.enqueue(
            GateDbWriteTask(
                partitionKey = state.dtlIp,
                operationName = "InsertReceiveAnal(${state.dtlIp},${analysis.laneNumber},${analysis.analysisType})",
            ) {
                val identity = resolveLaneIdentity(state.dtlIp, analysis.laneNumber, info)
                val rcvId = awaitRcvId(state.dtlIp, rcvIdDeferred, sharedRcvId)
                dataReceiveAnalysisRepository.save(
                    buildAnalysisEntity(
                        state, analysis, identity, analDate, rawHex, headerHex, tailHex, rcvId, headerFields, laneCount, laneCountHex,
                    ),
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
     * 이 경계는 `GatePacketPersisterTest`의 "날짜가 바뀌면..." 테스트로 고정돼 있다 — 이 상한을
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
        laneCountHex: String,
        rcvIdDeferred: Deferred<Long?>?,
        sharedRcvId: AtomicLong,
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
                    // rcvId는 enqueueReceiveInsert가 먼저 큐잉한 원시 INSERT의 결과다 — rcvIdDeferred가
                    // 있으면 그 결과를 그대로 쓰고(실패해 null이면 resolveRcvId로 폴백하지 않고 0으로
                    // 남긴다 — awaitRcvId KDoc의 D-1 재지적 참고), 없으면(테스트 등) 기존처럼
                    // resolveRcvId로 최신 행을 재조회한다.
                    latest.rcvDate = analDate
                    latest.rcvId = awaitRcvId(state.dtlIp, rcvIdDeferred, sharedRcvId)
                    latest.rcvRaw = rawHex
                    latest.analHeader = headerHex
                    latest.analTail = tailHex
                    dataReceiveAnalysisRepository.save(latest)
                } else {
                    val identity = resolveLaneIdentity(state.dtlIp, analysis.laneNumber, info)
                    val rcvId = awaitRcvId(state.dtlIp, rcvIdDeferred, sharedRcvId)
                    dataReceiveAnalysisRepository.save(
                        buildAnalysisEntity(
                            state, analysis, identity, analDate, rawHex, headerHex, tailHex, rcvId, headerFields, laneCount, laneCountHex,
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
    ): Boolean = AnalysisContentSnapshot.of(existing) == AnalysisContentSnapshot.of(analysis, laneCount)

    /**
     * [isSameContent]가 비교하는 "내용" 필드들만 모은 스냅샷 — 코드 리뷰 지적 M-1(2026-08-20) 대응.
     *
     * 이전에는 `existing.필드 == analysis.필드`를 33줄의 `&&` 체인으로 손으로 나열했다.
     * [DataReceiveAnalysis]에 비교 대상 필드가 추가돼도 이 체인을 갱신하지 않으면 **컴파일이
     * 그대로 통과**해, 새 필드가 조용히 비교에서 빠진 채(=항상 "동일"로 오판) 실제로는 달라진
     * 상태가 upsert 분기에서 새 INSERT 대신 `rcv_date`만 갱신되는 방식으로 뒤섞일 수 있었다.
     *
     * 이제는 [of] 두 오버로드가 이 data class의 생성자를 채운다 — 필드를 추가하면 생성자 인자가
     * 하나 늘어나므로, 두 [of] 중 하나라도 그 필드를 채우지 않으면 **컴파일 에러**가 난다. `==`는
     * data class가 생성해주는 전체 필드 비교를 그대로 쓴다.
     */
    private data class AnalysisContentSnapshot(
        val analTp: String,
        val descGateLaneCount: Int,
        val descGateLaneNumber: Int,
        val descGateType: String,
        val descUserMode: String,
        val userModeCd: String?,
        val descSecurityMode: String,
        val securityModeCd: String?,
        val descInoutTime: Long,
        val descUserCount: Long,
        val descTotalCount: Long,
        val descOperation01: String,
        val descOperation02: String,
        val descOperation03: String,
        val descOperation04: String,
        val descSafety01: String,
        val descSafety02: String,
        val descSafety03: String,
        val descSafety04: String,
        val descOperation05: String,
        val descOperation06: String,
        val descOperation07: String,
        val descOperation08: String,
        val descMotorCount: Long,
        val descMasterInTotal: Long,
        val descGateStatus01: String,
        val descGateStatus02: String,
        val descGateStatus03: String,
        val descGateStatus04: String,
        val descGateStatus05: String,
        val descGateStatus06: String,
        val descGateStatus07: String,
        val descGateStatus08: String,
        val descGateStatus09: String,
        val descGateStatus10: String,
        val descGateStatus11: String,
        val descGateStatus12: String,
        val errType: Int?,
        val resolveYn: String,
    ) {
        companion object {
            /** 직전에 저장된 행에서 스냅샷을 뽑는다. */
            fun of(existing: DataReceiveAnalysis) = AnalysisContentSnapshot(
                analTp = existing.analTp,
                descGateLaneCount = existing.descGateLaneCount,
                descGateLaneNumber = existing.descGateLaneNumber,
                descGateType = existing.descGateType,
                descUserMode = existing.descUserMode,
                userModeCd = existing.userModeCd,
                descSecurityMode = existing.descSecurityMode,
                securityModeCd = existing.securityModeCd,
                descInoutTime = existing.descInoutTime,
                descUserCount = existing.descUserCount,
                descTotalCount = existing.descTotalCount,
                descOperation01 = existing.descOperation01,
                descOperation02 = existing.descOperation02,
                descOperation03 = existing.descOperation03,
                descOperation04 = existing.descOperation04,
                descSafety01 = existing.descSafety01,
                descSafety02 = existing.descSafety02,
                descSafety03 = existing.descSafety03,
                descSafety04 = existing.descSafety04,
                descOperation05 = existing.descOperation05,
                descOperation06 = existing.descOperation06,
                descOperation07 = existing.descOperation07,
                descOperation08 = existing.descOperation08,
                descMotorCount = existing.descMotorCount,
                descMasterInTotal = existing.descMasterInTotal,
                descGateStatus01 = existing.descGateStatus01,
                descGateStatus02 = existing.descGateStatus02,
                descGateStatus03 = existing.descGateStatus03,
                descGateStatus04 = existing.descGateStatus04,
                descGateStatus05 = existing.descGateStatus05,
                descGateStatus06 = existing.descGateStatus06,
                descGateStatus07 = existing.descGateStatus07,
                descGateStatus08 = existing.descGateStatus08,
                descGateStatus09 = existing.descGateStatus09,
                descGateStatus10 = existing.descGateStatus10,
                descGateStatus11 = existing.descGateStatus11,
                descGateStatus12 = existing.descGateStatus12,
                errType = existing.errType,
                resolveYn = existing.resolveYn,
            )

            /** 방금 분석한 새 패킷에서 같은 모양의 스냅샷을 뽑는다 — [buildAnalysisEntity]가 채우는 값과 1:1 대응. */
            fun of(analysis: GateStatusAnalyzer.LaneStatusAnalysis, laneCount: Int) = AnalysisContentSnapshot(
                analTp = analysis.analysisType.name,
                descGateLaneCount = laneCount,
                descGateLaneNumber = analysis.laneNumber,
                descGateType = GateStatusAnalyzer.describeGateType(analysis.gateType),
                descUserMode = GateStatusAnalyzer.describeUserMode(analysis.userMode),
                userModeCd = analysis.userMode.toString(),
                descSecurityMode = GateStatusAnalyzer.describeSecurityMode(analysis.securityMode),
                securityModeCd = analysis.securityMode.toString(),
                descInoutTime = analysis.inoutTime.toLong(),
                descUserCount = analysis.userCount.toLong(),
                descTotalCount = analysis.totalCount,
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
        }
    }

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
     * `tb_data_rcv_anal.rcv_id` 폴백 경로 — 이 상태 패킷이 `enqueueReceiveInsert`로 함께 적재한
     * 원본 `tb_data_rcv` 행의 PK를 "가장 최근에 저장된 행"으로 추정해 채운다.
     *
     * **코드 리뷰 지적 D-1(2026-08-20)**: 이 추정은 정확하지 않을 수 있다 — 같은 파티션 키(dtlIp)로
     * 먼저 enqueue된 원시 INSERT가 [GateDbWriteQueue]의 순서 보장 덕에 보통 먼저 실행되지만,
     * 그 INSERT가 큐 드롭이나 재시도 소진으로 끝내 실패하면 "가장 최근 행"은 **이전 패킷**의 것을
     * 가리킨다 — 0으로 폴백하는 대신 조용히 엉뚱한 원본과 연결되어, 감사/장애 역추적 시 실제로는
     * 무관한 과거 패킷과 대조하게 된다. 그래서 [persist]/[enqueueAnalysisInsert]/
     * [enqueueStatusUpsert]는 이제 `enqueueReceiveInsert`가 돌려주는 `Deferred<Long?>`를 우선
     * 사용한다 — 같은 파티션에서 먼저 실행되도록 순서가 보장되므로 "이번 패킷"의 결과를 정확히
     * 가리키고, 실패 시에는 null로 완료되어 이 메서드로 명시적으로 폴백한다. 이 메서드는 그
     * Deferred를 넘기지 않는 극히 드문 호출부(테스트 등)를 위한 예전 방식의 최선 추정으로만
     * 남아있다 — 못 찾으면 0으로 폴백한다(엔티티 KDoc이 이미 0을 허용값으로 규정한다).
     *
     * **레인으로 필터링하지 않는다**(2026-08-14 재검토로 발견한 버그 수정 — [DataReceiveRepository]
     * KDoc 참고) — `tb_data_rcv`는 원시 패킷 1건당 대표 레인 하나로만 태그된 행 1건을 만드는 반면,
     * [persist]는 같은 원시 패킷에서 레인 수만큼 여러 분석 행을 만든다. 레인 번호로
     * 필터링하면 대표 레인이 아닌 레인들은 방금 저장된 원시 행을 절대 찾지 못하고 무관한 과거
     * 값을 잘못 가져왔다 — 같은 패킷에서 나온 분석 행은 전부 같은 원시 행을 가리켜야 한다.
     */
    private fun resolveRcvId(dtlIp: String): Long =
        dataReceiveRepository.findTopByDtlIpOrderByRcvIdDesc(dtlIp)?.rcvId ?: 0

    /**
     * [enqueueAnalysisInsert]/[enqueueStatusUpsert]가 `rcv_id`를 채울 때 쓰는 공통 경로.
     * `sharedRcvId`/`rcvIdDeferred` 두 최적화를 합친 것 — 병합 시(0037/0038 vs 0055/0056 두
     * 워크트리가 각자 같은 문제를 다르게 고친 결과물) 어느 한쪽만 남기면 회귀가 생겨 둘 다
     * 유지했다.
     *
     * **Codex 적대적 리뷰 지적(2026-08-20, [high])**: 기존에는 `rcvIdDeferred?.await() ?: resolveRcvId(dtlIp)`
     * 형태로, `rcvIdDeferred`가 **제공됐지만 null로 완료된 경우**(원시 `tb_data_rcv` INSERT가 큐
     * 드롭이나 재시도 소진으로 끝내 실패한 경우)에도 [resolveRcvId]로 폴백했다. 이 폴백은 "이번
     * 패킷"이 아니라 **이전 패킷**이 남긴 최신 행을 찾아 잘못 연결하는데(resolveRcvId KDoc의 D-1
     * 지적 그대로), 이는 애초에 `rcvIdDeferred`를 도입해 막으려던 문제가 실패 경로에서는 전혀
     * 해결되지 않은 채 그대로 남아있었다는 뜻이다 — 이번 원시 저장이 실패했다는 사실 자체를
     * 숨기고 무관한 과거 패킷을 가리켜, 감사/장애 역추적을 오도한다.
     *
     * 따라서 `rcvIdDeferred`가 제공된 호출(정상 경로)에서는 그 결과가 null이어도(=원시 저장 실패)
     * [resolveRcvId]로 대체하지 않고 0(엔티티 KDoc이 규정한 "미상" 값)으로 명시적으로 남긴다.
     * `rcvIdDeferred` 자체가 없는 호출부(테스트 등, resolveRcvId KDoc 참고)에서만
     * [resolveRcvIdCached]로 폴백한다 — 이 폴백 경로는 같은 패킷의 여러 레인이 매번 SELECT를
     * 반복하지 않도록 `sharedRcvId`로 패킷당 1회만 실제 조회한다(2026-08-20 Opus 전체 리뷰
     * 지적 대응). `Deferred.await()`는 여러 번 호출해도 항상 같은 결과를 반환하므로(코루틴
     * 표준 동작) `rcvIdDeferred` 경로는 별도 캐싱이 필요 없다.
     */
    private suspend fun awaitRcvId(dtlIp: String, rcvIdDeferred: Deferred<Long?>?, sharedRcvId: AtomicLong): Long =
        if (rcvIdDeferred != null) rcvIdDeferred.await() ?: 0L else resolveRcvIdCached(dtlIp, sharedRcvId)

    /**
     * [resolveRcvId]를 패킷당 최대 1회만 실제로 조회하도록 감싼 캐시 래퍼(2026-08-20 Opus 전체
     * 리뷰 지적 대응) — [awaitRcvId]가 `rcvIdDeferred`를 받지 못한 호출(테스트 등)에서만 쓰는
     * 폴백 경로다. `cache`는 항상 같은 패킷에서 나온 레인 태스크들끼리만 공유되며(패킷마다 새로
     * 생성), 같은 파티션키(dtlIp)의 태스크는 [GateDbWriteQueue] 설계상 같은 샤드 워커가 순서대로
     * 처리하므로 정상 경로에서는 두 번째 레인부터 SELECT 없이 캐시값을 그대로 재사용한다.
     * `UNRESOLVED_RCV_ID`(-1)를 sentinel로 써서 "아직 계산 안 됨"과 "정상값 0"(resolveRcvId가
     * 못 찾았을 때의 폴백)을 구분한다.
     *
     * CAS 실패 시 자신이 조회한 [resolved]가 아니라 [cache]에 먼저 기록된 값을 반환해야 한다
     * (2026-08-20 Codex 리뷰 지적) — 타임아웃으로 버려진 시도가 백그라운드에서 뒤늦게 이 함수를
     * 호출하는 경합 상황에서는, 두 호출이 동시에 sentinel을 읽고 서로 다른 시점의 rcvId를 각각
     * 조회할 수 있다. 이때 CAS에서 진 쪽이 자신이 조회한 값을 그대로 반환해 버리면 같은 패킷의
     * 분석 행들이 서로 다른 원본 행(rcv_id)을 가리키게 된다 — 반드시 승자의 값으로 통일한다.
     */
    private fun resolveRcvIdCached(dtlIp: String, cache: AtomicLong): Long {
        val cached = cache.get()
        if (cached != UNRESOLVED_RCV_ID) return cached
        val resolved = resolveRcvId(dtlIp)
        return if (cache.compareAndSet(UNRESOLVED_RCV_ID, resolved)) resolved else cache.get()
    }

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
        laneCountHex: String,
    ): DataReceiveAnalysis {
        // anal_data_check_sum/packet_checksum/etx — Tail(4바이트: XOR 체크섬 2 + 고정 체크섬 1 +
        // ETX 1, 클래스 KDoc "Tail(XOR,SUM,0x08,ETX)" 참고)을 나눈 hex. tailHex는 항상 짝수 길이의
        // hex 문자열이지만, 손상된 패킷이면 4바이트에 못 미칠 수 있어 안전하게 부분 문자열을 뗀다.
        fun tailPart(fromChar: Int, toChar: Int): String =
            if (tailHex.length >= toChar) tailHex.substring(fromChar, toChar) else ""
        val checkSum = tailPart(0, 4)
        val packetChecksum = tailPart(4, 6)
        val etx = tailPart(6, 8)
        val raw = analysis.rawFields

        return DataReceiveAnalysis(
            analDate = analDate,
            analTp = analysis.analysisType.name,
            dtlIp = state.dtlIp,
            dtlLaneNo = analysis.laneNumber,
            dtlType = identity.dtlType,
            dtlTypeCd = identity.dtlType?.toString(),
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
            // anal_data_* 33개 중 30개는 GateControl(SR_Speed_Server)이 write-only(어디서도
            // SELECT하지 않음)로 판단해 2026-08-26 dev DB에서 실제로 DROP했다(GateControl 커밋
            // 8c840c3/41696f2, dev DB 실측 검증 완료). GateControl이 계속 유지하는 3개만 남긴다 —
            // [DataReceiveAnalysis] KDoc 및
            // securance-domain/src/main/resources/db/migration/V33__drop_write_only_anal_data_columns.sql
            // 참고.
            analDataObjectCode = headerFields.objectCode,
            analDataMotorOperationCount = raw.motorCount,
            analDataMasterInTotalCount = raw.masterInCount,
            // desc_data_info_length/desc_gate_name/desc_gate_ip는 레거시 트리거가 항상 채우던 필드인데
            // 이 엔티티 도입 초기에는 매핑이 누락돼 빈 문자열로만 저장되고 있었다(2026-08-14 실 DB
            // 조회로 확인 — anal_id=856773 등 secuhub가 쓴 행만 이 세 컬럼이 비어 있었다).
            descDataInfoLength = SpeedGateProtocolConstants.DATA_INFO_LENGTH,
            descGateName = identity.dtlName ?: "",
            descGateIp = state.dtlIp,
            descGateLaneCount = laneCount,
            descGateLaneNumber = analysis.laneNumber,
            descGateType = GateStatusAnalyzer.describeGateType(analysis.gateType),
            descUserMode = GateStatusAnalyzer.describeUserMode(analysis.userMode),
            userModeCd = analysis.userMode.toString(),
            descSecurityMode = GateStatusAnalyzer.describeSecurityMode(analysis.securityMode),
            securityModeCd = analysis.securityMode.toString(),
            descInoutTime = analysis.inoutTime.toLong(),
            descUserCount = analysis.userCount.toLong(),
            descTotalCount = analysis.totalCount,
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

    companion object {
        private val logger = org.slf4j.LoggerFactory.getLogger(GateStatusAnalysisPersister::class.java)

        /** `tb_data_rcv.rcv_date` — 스키마 주석 규정 포맷(분 단위). */
        private val RCV_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmm")

        /** [resolveRcvIdCached]에서 "아직 계산되지 않음"을 나타내는 sentinel — rcvId는 0 이상이다. */
        private const val UNRESOLVED_RCV_ID = -1L
    }
}
