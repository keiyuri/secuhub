package kr.co.securance.secuhub.server.db

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kr.co.securance.secuhub.common.util.HexCodec
import kr.co.securance.secuhub.domain.entity.DataReceive
import kr.co.securance.secuhub.domain.entity.DataReceiveAck
import kr.co.securance.secuhub.domain.entity.DataReceiveFail
import kr.co.securance.secuhub.domain.repository.DataReceiveAckRepository
import kr.co.securance.secuhub.domain.repository.DataReceiveAnalysisRepository
import kr.co.securance.secuhub.domain.repository.DataReceiveFailRepository
import kr.co.securance.secuhub.domain.repository.DataReceiveRepository
import kr.co.securance.secuhub.domain.repository.GateDetailRepository
import kr.co.securance.secuhub.protocol.GatePacket
import kr.co.securance.secuhub.protocol.SpeedGateProtocolConstants
import kr.co.securance.secuhub.server.connection.GateConnectionState
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
 * 유일한 예외는 상태 분석 적재가 `tb_data_rcv_anal`에 실제로 새 행을 INSERT하는 순간
 * ([GateStatusAnalysisPersister]) — 캐시 값이 설정 변경을 반영하지 못하는 문제(2026-08-14 확인)를
 * 막기 위해 그 순간만 `tb_gate_dtl`을 다시 조회한다. INSERT는 상태 변경/신규 하루 단위로만 일어나므로
 * 상태 패킷(최고빈도) 자체의 N+1로 이어지지는 않는다.
 *
 * ### 책임 분리(2026-08-25 소스 전수 검토 지적)
 * 원래 이 클래스 하나가 패킷 헤더 파싱, 4종 리포지토리 적재, 콘텐츠 중복 판정, 레인 아이덴티티
 * 재조회, 장애 복구 큐잉까지 907줄에 걸쳐 도맡고 있었다. 그중 "상태 패킷 분석 적재"만으로도 이미
 * 500줄 가까운 독립적 관심사였으므로 [GateStatusAnalysisPersister]로 떼어냈다 — 이 클래스는 이제
 * "원시 패킷/ACK/실패 적재"(수신 파이프라인의 앞단, 상대적으로 단순한 저장 로직)만 담당한다.
 * 생성자 시그니처(7개 인자)와 공개 메서드(`persistReceivedPacket`/`persistAck`/
 * `persistChecksumFailure`/`persistStatusAnalysis`)는 그대로 유지해 호출부([DefaultGatePacketHandler])와
 * 테스트(`GatePacketPersisterTest`, `DefaultGatePacketHandlerAckTest.CountingPersister` — 이
 * 클래스를 상속해 각 메서드를 오버라이드한다)가 전혀 바뀔 필요가 없도록 했다 — 내부 구현만 옮긴
 * 순수 리팩터링이다.
 */
@Component
class GatePacketPersister(
    private val dbWriteQueue: GateDbWriteQueue,
    private val dataReceiveRepository: DataReceiveRepository,
    private val dataReceiveAckRepository: DataReceiveAckRepository,
    private val dataReceiveFailRepository: DataReceiveFailRepository,
    dataReceiveAnalysisRepository: DataReceiveAnalysisRepository,
    gateDetailRepository: GateDetailRepository,
    faultResolutionService: GateFaultResolutionService,
) {
    private val logger = LoggerFactory.getLogger(GatePacketPersister::class.java)

    /** 상태 패킷 분석 적재 전담 위임 대상([GateStatusAnalysisPersister] KDoc 참고). */
    private val statusAnalysisPersister = GateStatusAnalysisPersister(
        dbWriteQueue, dataReceiveRepository, dataReceiveAnalysisRepository, gateDetailRepository, faultResolutionService,
    )

    /**
     * 객체 코드별 상세 저장. 저장 대상이 없는(정보성) 패킷이면 null을 반환한다.
     *
     * @param laneNo 이 패킷의 대표 레인 번호(상태 블록 첫 바이트).
     * @return 이 패킷의 `tb_data_rcv` 원시 INSERT가 최종적으로 어떤 `rcv_id`로 끝났는지 알려주는
     *   [Deferred] — 저장 대상이 없으면 null. [persistStatusAnalysis]에 그대로 넘기면 D-1 리뷰
     *   지적(아래 [resolveRcvId] KDoc 참고)이 막는 잘못된 `rcv_id` 연결을 피할 수 있다. 큐 드롭/
     *   최종 실패 시에는 `null`로 완료된다 — 그 경우 호출부는 [resolveRcvId]로 **폴백하지 않고**
     *   0(미상)으로 기록한다(아래 [awaitRcvId] KDoc — Codex 적대적 리뷰 지적, 2026-08-20).
     */
    fun persistReceivedPacket(state: GateConnectionState, packet: GatePacket, laneNo: Int): Deferred<Long?>? {
        val objectCode = packet.objectCode
        val raw = packet.raw
        return when (objectCode) {
            // 게이트 상태(0x4D) — 가장 고빈도. 원시 패킷을 tb_data_rcv에 적재한다.
            SpeedGateProtocolConstants.ObjectCode.GATE_STATUS ->
                enqueueReceiveInsert(state, raw, laneNo, "상태 수신 데이터 저장")

            // 설정/모터/스케줄/휴일 — 레거시 BUG-02(저장 호출 누락으로 콘텐츠 영구 유실) 대응 분기.
            // 현재 스키마에서는 네 종류 모두 tb_data_rcv 한 곳에 원시 패킷으로 적재한다
            // (레거시의 tb_data_rcv_motor/schedule/holiday 분리 테이블은 계획서 4.2절에서 통합됨).
            SpeedGateProtocolConstants.ObjectCode.GATE_SETTING,
            SpeedGateProtocolConstants.ObjectCode.GATE_MOTOR,
            SpeedGateProtocolConstants.ObjectCode.TIME_ZONE,
            SpeedGateProtocolConstants.ObjectCode.HOLIDAY,
            ->
                enqueueReceiveInsert(state, raw, laneNo, "패킷 콘텐츠 저장(objectCode=0x%02X)".format(objectCode))

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
                null
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

        // ack_header/ack_data/ack_tail — enqueueReceiveInsert의 rcv_header/rcv_data/rcv_tail과
        // 동일한 Header(27)/Data/Tail(4) 구간 분할이다. 컬럼은 V1 스키마에 있었지만 이 값을 실제로
        // 채우는 코드가 없어 ack_raw(전체 원본)만 저장되고 나머지는 늘 빈 값이었다(2026-08-18 확인).
        val headerEnd = minOf(SpeedGateProtocolConstants.HEADER_LENGTH, raw.size)
        val tailStart = maxOf(raw.size - SpeedGateProtocolConstants.TAIL_LENGTH, headerEnd)
        val ackHeader = HexCodec.toHex(raw.copyOfRange(0, headerEnd))
        val ackData = HexCodec.toHex(raw.copyOfRange(headerEnd, tailStart))
        val ackTail = HexCodec.toHex(raw.copyOfRange(tailStart, raw.size))

        dbWriteQueue.enqueue(
            GateDbWriteTask(partitionKey = state.dtlIp, operationName = "InsertReceiveAck(${state.dtlIp},$laneNo)") {
                dataReceiveAckRepository.save(
                    DataReceiveAck(
                        ackDate = ackDate,
                        dtlIp = state.dtlIp,
                        dtlLaneNo = laneNo,
                        dtlId = info?.dtlId,
                        ackRaw = hex,
                        ackHeader = ackHeader,
                        ackData = ackData,
                        ackTail = ackTail,
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
     * 상태 패킷(0x4D)을 레인별로 분석해 `tb_data_rcv_anal`에 적재한다 — 실제 구현은
     * [GateStatusAnalysisPersister.persist]에 위임한다(위 클래스 KDoc "책임 분리" 참고).
     *
     * @param rcvIdDeferred 이 원시 패킷을 [enqueueReceiveInsert]가 적재한 `tb_data_rcv` 행의
     *   `rcv_id` — [DefaultGatePacketHandler]가 [persistReceivedPacket]의 반환값을 그대로
     *   넘겨준다. 자세한 배경은 [GateStatusAnalysisPersister.persist] KDoc 참고.
     */
    fun persistStatusAnalysis(state: GateConnectionState, raw: ByteArray, rcvIdDeferred: Deferred<Long?>? = null) {
        statusAnalysisPersister.persist(state, raw, rcvIdDeferred)
    }

    /**
     * `tb_data_rcv` 적재 작업을 파티션 큐에 넣는다(Header/Data/Tail 구간을 나눠 저장).
     *
     * @return 이 INSERT가 최종적으로 어떤 `rcv_id`로 끝났는지 알려주는 [Deferred](코드 리뷰 지적
     *   D-1 대응) — 저장이 성공하면 생성된 PK, 큐 드롭/최종 실패로 끝내 저장되지 못하면 null로
     *   완료된다. "가장 최신 행 재조회" 폴백과 달리 "이번 패킷"의 INSERT를 정확히 가리킨다(같은
     *   파티션에서 먼저 enqueue된 이 작업이 뒤이어 enqueue되는 분석 작업보다 먼저 실행되도록
     *   [GateDbWriteQueue]가 순서를 보장하므로, 분석 작업이 이 Deferred를 캡처해 await하면 항상
     *   완료된 상태이거나 곧 완료된다 — 데드락 위험이 없다).
     */
    private fun enqueueReceiveInsert(
        state: GateConnectionState,
        raw: ByteArray,
        laneNo: Int,
        operationName: String,
    ): Deferred<Long?> {
        val rcvIdResult = CompletableDeferred<Long?>()
        val info = state.laneInfoOf(laneNo) ?: state.primaryLaneInfo
        val rcvDate = LocalDateTime.now().format(RCV_DATE_FORMAT)

        val headerEnd = minOf(SpeedGateProtocolConstants.HEADER_LENGTH, raw.size)
        val tailStart = maxOf(raw.size - SpeedGateProtocolConstants.TAIL_LENGTH, headerEnd)
        val header = HexCodec.toHex(raw.copyOfRange(0, headerEnd))
        val body = HexCodec.toHex(raw.copyOfRange(headerEnd, tailStart))
        val tail = HexCodec.toHex(raw.copyOfRange(tailStart, raw.size))

        // rcv_data_info/rcv_data_lane — body(DataInfo+레인데이터 통합)를 다시 DataInfo 구간과
        // 레인 상태 블록 구간으로 나눈 값이다. 두 컬럼은 V1 스키마에 있었지만 채우는 코드가 없어
        // 항상 NULL로 저장되고 있었다(2026-08-18 확인). DataInfo 길이는 고정 상수가 아니라 이
        // 패킷의 헤더 `DATA_INFO_LENGTH` 필드(오프셋 22, objectCode마다 다를 수 있음)에서 읽는다 —
        // GATE_STATUS(0x4D)가 아닌 다른 objectCode(설정/모터/스케줄/휴일)는 DataInfo 길이가
        // [SpeedGateProtocolConstants.DATA_INFO_LENGTH](45, 상태 패킷 전용) 고정값과 다를 수 있다.
        val dataInfoLength = if (headerEnd > SpeedGateProtocolConstants.HeaderOffset.DATA_INFO_LENGTH) {
            raw[SpeedGateProtocolConstants.HeaderOffset.DATA_INFO_LENGTH].toInt() and 0xFF
        } else {
            0
        }
        val dataInfoEnd = minOf(headerEnd + dataInfoLength, tailStart)
        val dataInfo = HexCodec.toHex(raw.copyOfRange(headerEnd, dataInfoEnd))
        val laneData = HexCodec.toHex(raw.copyOfRange(dataInfoEnd, tailStart))

        dbWriteQueue.enqueue(
            GateDbWriteTask(
                partitionKey = state.dtlIp,
                operationName = "$operationName(${state.dtlIp},$laneNo)",
                // 큐 드롭 또는 재시도 소진(최종 실패)로 이 INSERT가 끝내 반영되지 못하면 null로
                // 완료한다 — 그러지 않으면 이 Deferred를 await하는 분석 작업이 영원히 대기한다.
                onDropOrFinalFailure = { rcvIdResult.complete(null) },
            ) {
                val saved = dataReceiveRepository.save(
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
                        rcvDataInfo = dataInfo,
                        rcvDataLane = laneData,
                        rcvTail = tail,
                    ),
                )
                rcvIdResult.complete(saved.rcvId)
            },
        )
        return rcvIdResult
    }

    companion object {
        /** `tb_data_rcv.rcv_date` — 스키마 주석 규정 포맷(분 단위). */
        private val RCV_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmm")

        /** ACK/실패 기록은 동일 분 내 다건이 흔하므로 초 단위까지 남긴다. */
        private val TIMESTAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
    }
}
