package kr.co.securance.secuhub.server.db

import kr.co.securance.secuhub.domain.entity.OprStatus
import kr.co.securance.secuhub.domain.entity.OprStatusId
import kr.co.securance.secuhub.domain.repository.OprStatusRepository
import kr.co.securance.secuhub.protocol.GateStatusAnalyzer
import kr.co.securance.secuhub.server.connection.GateConnectionState
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 상태 패킷(0x4D)의 레인별 통행 카운터를 `tb_opr_status`에 분(分) 단위로 적재한다(2026-08-12, B6).
 *
 * ## 이식 원본
 * 레거시는 이 집계를 애플리케이션이 아니라 **MariaDB 저장 프로시저** `usp_process_status`가
 * 수행했다(`92_DB_Script/20260805/securance_gate/usp_process_status.sql`, `usp_rcv_data_raw`가
 * 레인마다 호출). 이 클래스는 그 로직을 바이트 오프셋까지 그대로 옮긴다:
 *
 * - Total/Door/In 세 누적 카운터는 상태 블록 내 [GateStatusAnalyzer.LaneStatusAnalysis]의
 *   `totalCount`/`motorCount`/`masterInTotal`과 **동일한 바이트 위치**다 — `usp_process_status`가
 *   79/123/127번째 바이트(1-idx, 헤더 포함)에서 읽는 값이 각각 블록 오프셋 6/50/54에 해당하고,
 *   이는 `GateStatusAnalyzer.StatusOffset.TOTAL_COUNT`/`MOTOR_COUNT`/`MASTER_IN_COUNT`와 같다.
 *   `tb_data_rcv_anal`(장애 분석) 관점에서는 이 필드들이 "모터 카운트"/"마스터 IN 카운트"로
 *   쓰이지만, `tb_opr_status`(통행량 집계) 관점에서는 레거시 SP가 동일 바이트를 "도어 카운트"/
 *   "IN 카운트"로 재해석한다 — 같은 원시 필드가 소비 테이블에 따라 다르게 명명된 것으로 보인다.
 * - 분(分) 버킷 키는 `opr_date`(yyyyMMddHHmm) + `opr_seq=1` + `dtl_ip` + `dtl_lane_no` 복합키다.
 *   같은 분에 같은 레인의 패킷이 다시 오면(재수신) 새 행을 만들지 않고 **기존 행을 UPDATE**하며,
 *   이때 delta는 이번에 막 조회한 PREV가 아니라 **INSERT 시점에 저장해둔 `opr_before_total` 등
 *   3종 before 값 기준**으로 재계산한다(SP 원문 그대로 — 재수신이 반복돼도 기준점이 흔들리지 않게).
 * - PREV(직전 값) 조회는 최근 24시간 이내로 제한한다 — 무제한으로 과거를 훑으면 레인이 오래
 *   쉬었다 재개할 때 매우 오래된 값과 비교해 델타가 비정상적으로 커질 수 있기 때문(SP 원문 조건).
 * - **`opr_out_total`은 SP 원문에 없는 secuhub 자체 추가분이다(2026-08-12, codex 적대적 리뷰
 *   지적)**: 레거시 SP는 `opr_out_count`(증가분)만 채우고 `opr_out_total`(누적)은 항상 기본값
 *   0으로 남겨뒀다 — Total/In/Door과 달리 프로토콜에 "누적 출구 카운터" 원시 바이트가 없어서다.
 *   그런데 `AccessReportController`가 이미 `outTotal`을 리포트/CSV 합계에 쓰고 있어, SP를
 *   그대로 따르면(0 고정) 리포트가 항상 깨진 값을 보여준다. 그래서 secuhub는 in/door와 동일하게
 *   `outBefore`(PREV의 outTotal) + `outCount`(방금 계산한 델타)를 누적해 `outTotal`을 유지한다 —
 *   레거시에 없던 값이므로 레거시 화면과 대조 검증은 불가능하지만, 델타 자체는 SP와 동일한
 *   `deltaTotal - deltaIn` 공식이라 부정확할 이유가 없다.
 *
 * ## 호출 시점
 * [kr.co.securance.secuhub.server.tcp.DefaultGatePacketHandler]가 상태 변경이 감지됐을 때
 * (`PacketDiffer.diff(...).anyChanged`)만 [persistStatusAnalysis][GatePacketPersister.persistStatusAnalysis]와
 * 나란히 호출한다. 레거시 SP는 매 상태 패킷마다 무조건 실행됐지만, Total/Door/In 카운터가 실제로
 * 안 바뀌었다면(=아무도 통행하지 않았다면) `PacketDiffer`가 그 레인 블록을 "변경 없음"으로 판정해
 * 애초에 이 메서드가 불릴 일이 없다 — 결과적으로 델타가 0인 빈 행만 반복해서 남기지 않는다는
 * 차이가 있을 뿐, 최종 누적/증가분 값 자체는 레거시와 동일하다.
 *
 * ## 큐 드롭/최종 실패 시 데이터 손실 범위(2026-08-12, codex 적대적 리뷰 지적)
 * [GateDbWriteQueue]는 큐 포화 시 작업을 드롭하고, 재시도를 모두 소진해도 실패하면 그대로
 * 포기한다(둘 다 로그 + 카운터만 남긴다, [GateDbWriteQueue] KDoc 참고). 이 write가 실제로
 * 완전히 빠지면 **그 분(分) 버킷 행 자체는 확실히 비어 남는다** — 이건 사실이고 고치지 않았다
 * (durable outbox/재처리 큐는 이 파이프라인만을 위해 들이기엔 과한 아키텍처 변경이라 범위 밖).
 * 다만 그 다음 성공한 write는 PREV 조회(`findLatestBefore`)로 **마지막으로 실제 저장된 행**을
 * 기준 삼아 델타를 다시 계산하므로(`usp_process_status` 원문 그대로), 드롭된 구간의 통행량이
 * 다음 성공 분 버킷에 합쳐져 들어갈 뿐 하루 합계(`sumUserCountToday`)에서 사라지지는 않는다 —
 * 즉 분 단위 시계열 해상도는 그 구간만 거칠어지지만, 누적 총량은 리커버된다. `PacketDiffer`가
 * "변경 없음"으로 판정해도 다음 실제 변화가 있을 때는 무조건 다시 enqueue되므로(위 "호출 시점"
 * 참고), 드롭 이후 상태가 "영구히 다시 enqueue되지 않는" 것도 아니다.
 */
@Component
class OprStatusPersister(
    private val dbWriteQueue: GateDbWriteQueue,
    private val oprStatusRepository: OprStatusRepository,
) {
    private val logger = LoggerFactory.getLogger(OprStatusPersister::class.java)

    /** 상태 패킷 1건(원시 바이트)을 분석해 레인 전체를 `tb_opr_status`에 반영한다. */
    fun persistOprStatus(state: GateConnectionState, raw: ByteArray) {
        val analyses = GateStatusAnalyzer.analyze(raw)
        if (analyses.isEmpty()) return

        val now = LocalDateTime.now()
        val dateKey = now.format(MINUTE_FORMAT)
        val sinceDateKey = now.minusDays(1).toLocalDate().atStartOfDay().format(MINUTE_FORMAT)

        for (analysis in analyses) {
            // 레인 번호 0은 사용하지 않는 슬롯(SP의 `IF vRealLaneNo = 0 THEN LEAVE`와 동일).
            if (analysis.laneNumber == 0) continue

            val laneNo = analysis.laneNumber
            val info = state.laneInfoOf(laneNo)
            val dtlId = info?.dtlId
            if (info == null || dtlId == null) {
                logger.debug("커넥션[{}] 레인 {}이(가) tb_gate_dtl에 없어 통행량 집계를 건너뜁니다.", state.dtlIp, laneNo)
                continue
            }

            dbWriteQueue.enqueue(
                GateDbWriteTask(
                    partitionKey = state.dtlIp,
                    operationName = "UpsertOprStatus(${state.dtlIp},$laneNo)",
                ) {
                    upsert(state.dtlIp, laneNo, analysis, dtlId, info.dtlType, info.locId, info.grpId, dateKey, sinceDateKey)
                    Unit
                },
            )
        }
    }

    private fun upsert(
        dtlIp: String,
        laneNo: Int,
        analysis: GateStatusAnalyzer.LaneStatusAnalysis,
        dtlId: Long,
        dtlType: Int,
        locId: Long,
        grpId: Long,
        dateKey: String,
        sinceDateKey: String,
    ) {
        val currTotal = analysis.totalCount
        val currDoor = analysis.motorCount.toLong()
        val currIn = analysis.masterInTotal.toLong()
        val gateType = GateStatusAnalyzer.describeGateType(analysis.gateType)
        val userMode = analysis.userMode.toString()
        val securityMode = analysis.securityMode.toString()

        val id = OprStatusId(oprDate = dateKey, oprSeq = 1, dtlIp = dtlIp, dtlLaneNo = laneNo)
        val existing = oprStatusRepository.findById(id).orElse(null)

        if (existing != null) {
            // 같은 분 재수신 — delta는 INSERT 시점에 저장해둔 before 값 기준으로 재계산한다.
            val deltaTotal = (currTotal - (existing.beforeTotal ?: 0L)).coerceAtLeast(0L)
            val deltaDoor = (currDoor - (existing.doorBefore ?: 0L)).coerceAtLeast(0L)
            val deltaIn = (currIn - (existing.inBefore ?: 0L)).coerceAtLeast(0L)
            val deltaOut = (deltaTotal - deltaIn).coerceAtLeast(0L)

            existing.userCount = deltaTotal.toInt()
            existing.totalCount = currTotal
            existing.doorCount = deltaDoor.toInt()
            existing.doorTotal = currDoor
            existing.inCount = deltaIn.toInt()
            existing.inTotal = currIn
            existing.outCount = deltaOut.toInt()
            // outBefore는 INSERT 시점 값 그대로(위 before류와 동일 원칙) — outTotal만 재수신 시 재계산.
            existing.outTotal = (existing.outBefore ?: 0L) + deltaOut
            existing.gateType = gateType
            existing.userMode = userMode
            existing.securityMode = securityMode
            existing.inoutTime = analysis.inoutTime
            oprStatusRepository.save(existing)
            return
        }

        // 신규 분 버킷 — 최근 24시간 이내 직전 레코드를 PREV로 삼는다(없으면 0).
        val prev = oprStatusRepository
            .findLatestBefore(dtlId, dtlIp, laneNo, dateKey, sinceDateKey, PageRequest.of(0, 1))
            .firstOrNull()
        val prevTotal = prev?.totalCount ?: 0L
        val prevDoor = prev?.doorTotal ?: 0L
        val prevIn = prev?.inTotal ?: 0L
        // 프로토콜에는 "누적 출구 카운터" 원시 바이트가 없어(Total/In/Door과 달리) PREV의 outTotal을
        // 그대로 이어받아 누적한다(codex 적대적 리뷰 지적, 2026-08-12 B6 후속 수정 — KDoc 참고).
        val prevOut = prev?.outTotal ?: 0L

        val deltaTotal = (currTotal - prevTotal).coerceAtLeast(0L)
        val deltaDoor = (currDoor - prevDoor).coerceAtLeast(0L)
        val deltaIn = (currIn - prevIn).coerceAtLeast(0L)
        val deltaOut = (deltaTotal - deltaIn).coerceAtLeast(0L)

        oprStatusRepository.save(
            OprStatus(
                id = id,
                dtlId = dtlId,
                dtlType = dtlType,
                dtlNo = DTL_NO_DEFAULT,
                locId = locId,
                grpId = grpId,
                gateType = gateType,
                userMode = userMode,
                securityMode = securityMode,
                inoutTime = analysis.inoutTime,
                userCount = deltaTotal.toInt(),
                totalCount = currTotal,
                beforeTotal = prevTotal,
                inCount = deltaIn.toInt(),
                inTotal = currIn,
                inBefore = prevIn,
                outCount = deltaOut.toInt(),
                outTotal = prevOut + deltaOut,
                outBefore = prevOut,
                doorCount = deltaDoor.toInt(),
                doorTotal = currDoor,
                doorBefore = prevDoor,
                useYn = "Y",
            ),
        )
    }

    private companion object {
        /** `tb_opr_status.opr_date` — 분 단위 버킷 키(레거시 SP `DATE_FORMAT(NOW(),'%Y%m%d%H%i')`). */
        val MINUTE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmm")

        /** SP가 `usp_rcv_data_raw`에서 항상 `vDtlNo=0`으로 호출한다 — Serial 연결 번호, TCP 경로에선 미사용. */
        const val DTL_NO_DEFAULT = 0
    }
}
