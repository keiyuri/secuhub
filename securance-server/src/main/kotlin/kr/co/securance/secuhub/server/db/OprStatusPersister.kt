package kr.co.securance.secuhub.server.db

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kr.co.securance.secuhub.domain.entity.OprStatus
import kr.co.securance.secuhub.domain.entity.OprStatusId
import kr.co.securance.secuhub.domain.entity.OprStatusOutbox
import kr.co.securance.secuhub.domain.repository.OprStatusOutboxRepository
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
 * ## 큐 드롭/최종 실패 시 데이터 손실 범위와 durable 대체 저장(2026-08-12, codex 적대적 리뷰 지적 →
 * 2026-08-12 큐 드롭 durable 재작성)
 * [GateDbWriteQueue]는 큐 포화 시 작업을 드롭하고, 재시도를 모두 소진해도 실패하면 그대로
 * 포기한다(둘 다 로그 + 카운터만 남긴다, [GateDbWriteQueue] KDoc 참고). 처음에는(0003 항목) 이
 * write가 실제로 빠져도 다음 성공한 write가 PREV 조회(`findLatestBefore`)로 **마지막으로 실제
 * 저장된 행**을 기준 삼아 델타를 다시 계산하므로(`usp_process_status` 원문 그대로), 드롭된 구간의
 * 통행량이 다음 성공 분 버킷에 합쳐져 들어갈 뿐 하루 합계(`sumUserCountToday`)에서 사라지지는
 * 않는다는 self-healing 근거로 문서화만 하고 코드는 그대로 두었었다 — 다만 분 단위 시계열
 * 해상도(어느 분에 얼마나 통행했는지) 자체는 여전히 영구히 비어 남는 문제가 있었고, 사용자가
 * 이를 문서화만으로는 부족하다고 판단해 실제 durable 재작성을 요청했다.
 *
 * 지금은 [GateDbWriteTask.onDropOrFinalFailure]에 [saveOutboxFallback]을 연결해, 드롭/최종 실패
 * 시점에 UPSERT에 필요한 원시값을 `tb_opr_status_outbox`에 동기 저장한다([GateDbWriteQueue]가
 * 이 콜백을 [blockingDispatcher][GateDbWriteQueue]에서 실행하므로 호출 스레드는 막지 않는다).
 * `OprStatusOutboxReplayJob`(securance-scheduler)이 주기적으로 미처리 행을 읽어 [replayOutboxEntry]로
 * 원래 UPSERT를 재시도한다 — 재시도 자체가 다시 실패해도 outbox 행은 남아 있으니 다음 실행에서
 * 또 시도한다. 이 fallback 저장 자체가 실패하는 경우(DB 완전 다운 등 이중 장애)만 여전히 진짜
 * 유실이며, 그 경우는 [GateDbWriteQueue]가 로그로 크게 남긴다.
 */
@Component
class OprStatusPersister(
    private val dbWriteQueue: GateDbWriteQueue,
    private val oprStatusRepository: OprStatusRepository,
    private val oprStatusOutboxRepository: OprStatusOutboxRepository,
    /**
     * [replayOutboxEntry]가 `result.await()`를 무한정 기다리지 않도록 두는 상한(밀리초, R-2 대응) —
     * 테스트가 셧다운 경합을 빠르게 재현할 수 있도록 생성자에서 주입 가능하게 뒀다
     * ([GateControlDispatcher]의 `clock` 주입과 동일한 패턴). 기본값은 [GateDbWriteTask]의 기본
     * 재시도 정책(`maxAttempts=3`, 시도당 `timeout=5초`, 백오프 최대 2초)이 정상적으로 실패로
     * 확정되기까지 걸리는 최대 시간(약 21초)보다 넉넉히 크게 잡았다.
     *
     * **타입이 `Long`(밀리초)인 이유**: `kotlin.time.Duration`은 인라인 값 클래스(value class)라,
     * 기본값이 있는 생성자 파라미터로 두면 Kotlin이 만드는 합성(디폴트용) 생성자를 Spring이
     * 빈 생성 시점에 잘못 고른다 — `DefaultConstructorMarker` 타입의 빈을 찾다가 기동 자체가
     * 실패했다(2026-08-20 실제 재현: `SecuranceApplicationTests` 컨텍스트 로드 실패). `Clock`처럼
     * 인라인이 아닌 일반 타입이면 문제없다([GateControlDispatcher]의 `clock` 파라미터 참고).
     */
    private val replayAwaitTimeoutMillis: Long = 30_000L,
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

            val currTotal = analysis.totalCount
            val currDoor = analysis.motorCount
            val currIn = analysis.masterInTotal

            dbWriteQueue.enqueue(
                GateDbWriteTask(
                    partitionKey = state.dtlIp,
                    operationName = "UpsertOprStatus(${state.dtlIp},$laneNo)",
                    onDropOrFinalFailure = {
                        saveOutboxFallback(
                            state.dtlIp, laneNo, dtlId, info.dtlType, info.locId, info.grpId,
                            dateKey, sinceDateKey, currTotal, currDoor, currIn,
                            analysis.gateType, analysis.userMode, analysis.securityMode, analysis.inoutTime,
                        )
                    },
                ) {
                    upsert(
                        state.dtlIp, laneNo, dtlId, info.dtlType, info.locId, info.grpId, dateKey, sinceDateKey,
                        currTotal, currDoor, currIn, analysis.gateType, analysis.userMode, analysis.securityMode, analysis.inoutTime,
                    )
                    Unit
                },
            )
        }
    }

    /**
     * 드롭/최종 실패한 작업을 `tb_opr_status_outbox`에 동기 저장한다([GateDbWriteQueue.blockingDispatcher]에서
     * 실행되므로 블로킹 JPA 호출이 안전하다). 이 저장 자체가 던지는 예외는 호출부([GateDbWriteQueue.runFallback])가
     * 잡아 로그로 남기므로 여기서는 별도 처리하지 않는다.
     */
    private fun saveOutboxFallback(
        dtlIp: String,
        laneNo: Int,
        dtlId: Long,
        dtlType: Int,
        locId: Long,
        grpId: Long,
        dateKey: String,
        sinceDateKey: String,
        currTotal: Long,
        currDoor: Long,
        currIn: Long,
        gateTypeRaw: Int,
        userModeRaw: Int,
        securityModeRaw: Int,
        inoutTime: Int,
    ) {
        oprStatusOutboxRepository.save(
            OprStatusOutbox(
                dtlIp = dtlIp,
                dtlLaneNo = laneNo,
                dtlId = dtlId,
                dtlType = dtlType,
                locId = locId,
                grpId = grpId,
                oprDate = dateKey,
                sinceDate = sinceDateKey,
                currTotal = currTotal,
                currDoor = currDoor,
                currIn = currIn,
                gateTypeRaw = gateTypeRaw,
                userModeRaw = userModeRaw,
                securityModeRaw = securityModeRaw,
                inoutTime = inoutTime,
                reason = "DROPPED_OR_FINAL_FAILURE",
            ),
        )
        logger.warn(
            "DB 쓰기 큐 드롭/최종실패한 통행량 집계를 outbox에 durable 저장했습니다: dtlIp={}, lane={}, oprDate={}",
            dtlIp, laneNo, dateKey,
        )
    }

    /**
     * [OprStatusOutboxReplayJob][kr.co.securance.secuhub.scheduler.job.OprStatusOutboxReplayJob]이
     * outbox의 미처리 행 하나를 재처리할 때 호출한다 — [upsert]를 그대로 재사용해 라이브 경로와
     * 재처리 경로의 UPSERT 로직이 갈라지지 않게 한다.
     *
     * 코드 리뷰 지적(2026-08-14): 예전에는 `GateDbWriteQueue`를 거치지 않고 호출 스레드(스케줄러
     * 잡 스레드)에서 직접 [upsert]를 실행했는데, 같은 `(dtlIp, dtlLaneNo)`에 대한 라이브 상태 패킷
     * 처리([persistOprStatus])는 여전히 큐를 통해 파티션별로 직렬화되어 들어오므로, 재처리 스레드가
     * 그 직렬화를 우회해 같은 분(分) 버킷 행을 락 없이 동시에 findById→save할 수 있었다(유실/갱신
     * 손실 위험). 재처리도 동일 파티션키([OprStatusOutbox.dtlIp])로 큐에 태워, 라이브 쓰기와 순서가
     * 보장되게 한다 — 재처리는 저빈도라 큐를 한 단계 더 거치는 지연은 문제되지 않는다.
     *
     * ## `result`가 영원히 완료되지 않을 수 있는 경로 (코드 리뷰 지적 R-2)
     * [result]는 (1) [upsert] 성공 시, 또는 (2) [GateDbWriteTask.onDropOrFinalFailure] 콜백 실행
     * 시에만 완료된다. 그런데 [GateDbWriteQueue.shutdown]이 호출된 직후, 마침 이 작업을 처리하던
     * 샤드 워커가 `withTimeout { execution.await() }`에서 대기하던 도중이면 `workerJob.cancel()`이
     * 만든 [kotlinx.coroutines.CancellationException]이 그 자리에서 그대로 전파되어 워커 루프
     * 자체가 끝나버린다 — 처리 중이던 이 작업도, 채널에 남아 있던 나머지 작업도 [onDropOrFinalFailure]가
     * 호출되지 않은 채 버려진다([GateDbWriteQueue.runShardWorker] 참고).
     *
     * 이 클래스만으로는 그 경합을 막을 수 없으므로(큐 쪽 종료 순서 문제), 호출부(Quartz 잡)의
     * `runBlocking`이 `@DisallowConcurrentExecution` 잡 스레드를 영구히 붙잡지 않도록
     * [withTimeoutOrNull]로 상한을 둔다. `GateDbWriteTask`의 기본 재시도 정책(`maxAttempts=3`,
     * `timeout=5초`, 최대 백오프 2초)이 정상 실패 경로에서 걸리는 최대 시간보다 넉넉히 크게 잡아,
     * 정상적인 재시도 중인 작업을 성급하게 포기 처리하지 않는다. 시간 초과 시 실패로 간주해도
     * outbox 행 자체는 지워지지 않으므로([OprStatusOutboxReplayJob]이 실패 시 `retryCount`만 올리고
     * 행은 남겨둔다) 데이터 유실은 없다 — 다음 잡 실행에서 다시 시도된다.
     */
    suspend fun replayOutboxEntry(entry: OprStatusOutbox): Boolean {
        val result = CompletableDeferred<Boolean>()
        dbWriteQueue.enqueue(
            GateDbWriteTask(
                partitionKey = entry.dtlIp,
                operationName = "ReplayOprStatus(${entry.dtlIp},${entry.dtlLaneNo})",
                onDropOrFinalFailure = { result.complete(false) },
            ) {
                upsert(
                    entry.dtlIp, entry.dtlLaneNo, entry.dtlId, entry.dtlType, entry.locId, entry.grpId,
                    entry.oprDate, entry.sinceDate, entry.currTotal, entry.currDoor, entry.currIn,
                    entry.gateTypeRaw, entry.userModeRaw, entry.securityModeRaw, entry.inoutTime,
                )
                result.complete(true)
            },
        )
        val completed = withTimeoutOrNull(replayAwaitTimeoutMillis) { result.await() }
        if (completed == null) {
            logger.error(
                "outbox 재처리 응답을 {}ms 안에 받지 못했습니다(큐 셧다운 경합 등) — 이번 시도는 실패로 " +
                    "간주합니다: dtlIp={}, lane={}, oprDate={}",
                replayAwaitTimeoutMillis, entry.dtlIp, entry.dtlLaneNo, entry.oprDate,
            )
        }
        return completed ?: false
    }

    private fun upsert(
        dtlIp: String,
        laneNo: Int,
        dtlId: Long,
        dtlType: Int,
        locId: Long,
        grpId: Long,
        dateKey: String,
        sinceDateKey: String,
        currTotal: Long,
        currDoor: Long,
        currIn: Long,
        gateTypeRaw: Int,
        userModeRaw: Int,
        securityModeRaw: Int,
        inoutTime: Int,
    ) {
        val gateType = GateStatusAnalyzer.describeGateType(gateTypeRaw)
        val userMode = userModeRaw.toString()
        val securityMode = securityModeRaw.toString()

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
            existing.inoutTime = inoutTime
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
                inoutTime = inoutTime,
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
