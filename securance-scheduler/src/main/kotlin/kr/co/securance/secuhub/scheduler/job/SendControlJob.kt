package kr.co.securance.secuhub.scheduler.job

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kr.co.securance.secuhub.common.util.HexCodec
import kr.co.securance.secuhub.domain.entity.DataSend
import kr.co.securance.secuhub.domain.repository.DataReceiveAnalysisRepository
import kr.co.securance.secuhub.domain.repository.DataSendRepository
import kr.co.securance.secuhub.scheduler.config.SchedulerProperties
import kr.co.securance.secuhub.server.connection.GateConnectionRegistry
import org.quartz.DisallowConcurrentExecution
import org.quartz.JobExecutionContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.quartz.QuartzJobBean
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 게이트 제어 명령(OPEN/CLOSE/RESET 등) 전송 잡 — 레거시 `ClsQuartzJobSendControl`에 대응한다.
 *
 * [단순화] 레거시는 `EnqueueProcessing().GetAwaiter().GetResult()`가 클라이언트 체인에 실제로
 * enqueue된 뒤 "물리 전송이 완료될 때까지" 동기 대기하는 구조였다. 그래서 타임아웃 이후에도 체인에
 * 남아있는 in-flight 전송을 별도로 추적(`_inFlightSendIds`)해 중복 물리 전송을 막아야 했다.
 *
 * [Codex 리뷰 수정] 이 코드베이스의 [GateConnectionRegistry.sendToLane]도 (`GateConnectionActor.
 * submitAndAwait` 도입 이후) 레거시와 동일하게 "액터 큐에 실제로 소켓 쓰기가 완료될 때까지" suspend로
 * 대기한 뒤 결과를 반환한다 — 큐잉 성공만 보고 `snd_yn='Y'`를 확정 저장하던 이전 구현은 큐잉 직후
 * 연결이 끊기거나 비동기 전송이 실패해도 명령이 유실된 채 재시도 대상에서 영구히 빠지는 버그였다.
 * 다만 레거시의 별도 in-flight 타임아웃 체인까지는 필요 없다 — `submitAndAwait`가 스스로 완료까지
 * 대기하므로 "타임아웃 이후에도 체인에 남아있는 전송"이라는 상황 자체가 발생하지 않고, 쿨다운
 * (재전송 방지)만으로 중복 전송을 막기에 충분하다.
 *
 * [Codex 적대적 리뷰 수정] 배치 조회가 항상 `snd_id` 오름차순 첫 페이지만 보던 구조라, 큐 앞쪽의
 * 행이 계속 실패(오프라인 게이트 등)하면 매 폴링마다 같은 행만 다시 뽑혀 뒤쪽에 적재된 정상 게이트용
 * 명령이 무기한 처리되지 못하는 헤드 오브 라인 차단이 있었다. 인메모리 쿨다운 맵(`recentSendAttempts`)
 * 은 물리 재전송만 억제할 뿐 DB 조회 자체에서는 그 행을 배제하지 못해 배치 슬롯을 계속 점유했다.
 * `DataSend.nextAttemptAt`을 실패 시 미래로 설정하고 조회 조건(`findEligiblePending`)에 반영해
 * 해결한다 — 이 컬럼이 곧 재시도 쿨다운 상태 그 자체이므로 인메모리 맵은 더 이상 필요 없다.
 *
 * [Codex 어드버서리얼 리뷰 수정] 이 프로젝트의 배포 단위는 WinSW로 등록한 단일 Windows 서비스이고
 * Quartz도 RAMJobStore(다중 인스턴스 클러스터링은 명시적으로 후속 과제, `application.yml` 참고)라
 * 정상 운영에서는 인스턴스가 하나뿐이다. 다만 롤링 재배포 중 신·구 프로세스가 잠깐 겹치거나 운영자가
 * 실수로 서비스를 이중 기동하는 경우, `findEligiblePending`으로 같은 행을 두 프로세스가 동시에 읽어
 * 같은 제어 명령(OPEN/CLOSE/RESET)을 게이트에 중복 물리 전송할 위험이 있었다 —
 * `@DisallowConcurrentExecution`은 JVM 하나 안에서만 유효하다. 각 행을 처리하기 직전에
 * [DataSendRepository.claim]로 원자적 조건부 UPDATE를 먼저 시도해, 다른 프로세스가 이미 선점한
 * 행이면(영향받은 행 수 0) 조용히 건너뛴다.
 */
@DisallowConcurrentExecution
class SendControlJob : QuartzJobBean() {

    @Autowired
    private lateinit var registry: GateConnectionRegistry

    @Autowired
    private lateinit var dataSendRepository: DataSendRepository

    @Autowired
    private lateinit var dataReceiveAnalysisRepository: DataReceiveAnalysisRepository

    @Autowired
    private lateinit var properties: SchedulerProperties

    private val logger = LoggerFactory.getLogger(SendControlJob::class.java)

    override fun executeInternal(context: JobExecutionContext) {
        // Opus 전체 리뷰 지적: 조건 없이 전체를 읽으면 큐가 밀렸을 때 매초 대량 로딩으로 폴링 자체가
        // 느려지는 악순환에 빠질 수 있어 배치 상한(Pageable)을 둔다 — 남은 행은 다음 폴링에서 이어진다.
        //
        // [Codex 적대적 리뷰 수정] next_attempt_at이 아직 도래하지 않은(=재시도 쿨다운 중인) 행은
        // 조회 자체에서 제외해, 계속 실패하는 앞쪽 행이 배치 슬롯을 영구히 점유하지 않게 한다.
        val batchSize = properties.sendControlBatchSize.coerceAtLeast(1)
        val pending = dataSendRepository.findEligiblePending(
            PENDING_SND_YN,
            PENDING_CHK_YN,
            LocalDateTime.now(),
            PageRequest.of(0, batchSize),
        )
        if (pending.isEmpty()) return

        runBlocking(Dispatchers.IO) {
            val semaphore = Semaphore(properties.jobConcurrency.coerceAtLeast(1))
            pending.map { row ->
                async {
                    semaphore.withPermit {
                        processRow(row)
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun processRow(row: DataSend) {
        val sndId = row.sndId
        if (sndId == null) {
            logger.warn("[SendJob] snd_id가 없는 행을 건너뜁니다. dtl_ip={}", row.dtlIp)
            return
        }

        // 다른 프로세스(또는 이 프로세스의 겹치는 다음 폴링)가 이미 이 행을 선점했다면 여기서
        // 원자적 UPDATE가 0행에 걸려 즉시 알 수 있다 — 중복 물리 전송 방지.
        val now = LocalDateTime.now()
        val claimed = dataSendRepository.claim(sndId, PENDING_SND_YN, PENDING_CHK_YN, now, now.plus(CLAIM_LEASE))
        if (claimed == 0) {
            logger.debug("[SendJob] 다른 프로세스가 이미 선점한 행이라 건너뜁니다. snd_id={}, ip={}", sndId, row.dtlIp)
            return
        }

        try {
            val rawHex = row.sndRaw
            if (rawHex.isNullOrBlank()) {
                logger.warn("[SendJob] snd_raw가 비어 있어 전송을 건너뜁니다. snd_id={}, ip={}", sndId, row.dtlIp)
                markPermanentlyUnsendable(row)
                return
            }

            val packet = try {
                HexCodec.fromHex(rawHex)
            } catch (ex: IllegalArgumentException) {
                // 파싱 실패는 데이터 자체의 영구적 결함이라 재시도해도 결과가 달라지지 않는다.
                // sndYn='N'인 채로 방치하면 매 스케줄마다 같은 실패가 반복 로그로 남으므로,
                // chkYn='Y'로 표시해 findEligiblePending("N","N") 대상에서 제외한다
                // (sndYn은 "게이트에 실제로 전송됨"의 의미를 지키기 위해 'N'으로 그대로 둔다 —
                // "확인/종결됨"과 "전송됨"을 혼동하지 않도록 chkYn만 바꾼다).
                logger.warn(
                    "[SendJob] snd_raw가 유효한 16진 문자열이 아니어서 영구적으로 스킵합니다. snd_id={}, ip={}, 원인: {}",
                    sndId,
                    row.dtlIp,
                    ex.message,
                )
                markPermanentlyUnsendable(row)
                return
            }

            val sent = registry.sendToLane(row.dtlIp, row.dtlLaneNo, packet)
            if (sent) {
                row.sndYn = "Y"
                row.nextAttemptAt = null
                dataSendRepository.save(row)

                // [수정] 레거시는 `snd_data_tp.Split('_')[0] == "RESET"`처럼 첫 토큰 완전 일치로
                // 판정한다. `startsWith`는 "RESETUP" 같은 향후 코드값도 오탐할 수 있어 첫 토큰
                // 완전 일치로 좁힌다.
                val typeCd = row.sndTypeCd
                val typeParts = typeCd?.split("_") ?: emptyList()
                if (typeParts.firstOrNull() == RESET_TYPE_PREFIX) {
                    resolveGateErrors(row.dtlIp, row.sndUser, subType = typeParts.getOrNull(1))
                }

                logger.debug("[SendJob] 전송 및 갱신 완료: snd_id={}, ip={}", sndId, row.dtlIp)
            } else {
                // [Codex 적대적 리뷰 수정] sndYn/chkYn은 그대로 두어 재시도 대상으로 남기되,
                // next_attempt_at을 미래로 밀어 다음 폴링의 조회 조건에서 이 행을 제외한다 —
                // 그래야 이 행이 계속 실패해도 뒤쪽에 적재된 다른 정상 행이 배치 슬롯을 차지할 수 있다.
                row.nextAttemptAt = LocalDateTime.now().plus(RESEND_GUARD)
                dataSendRepository.save(row)
                logger.warn(
                    "[SendJob] 전송 실패(연결 없음/대기열 초과 등) — 쿨다운({}s) 이후 재시도됩니다. snd_id={}, ip={}",
                    RESEND_GUARD.seconds,
                    sndId,
                    row.dtlIp,
                )
            }
        } catch (ex: Exception) {
            // 이 행 처리 중 예외가 다른 행 처리에 영향을 주지 않도록 여기서 흡수한다.
            logger.error("[SendJob] 행 처리 중 오류: snd_id={}, ip={}", sndId, row.dtlIp, ex)
        }
    }

    private fun markPermanentlyUnsendable(row: DataSend) {
        row.chkYn = "Y"
        dataSendRepository.save(row)
    }

    /**
     * 리셋 플래그 연동(레거시 `FinalizeSuccessfulSend`의 `UpdateResetFlagMotor/Sensor/Gate` 대응).
     *
     * [Codex 적대적 리뷰 수정] 예전에는 서브타입을 구분하지 않고 `dtlIp`의 미해결 오류 전체를
     * resolve 처리했다 — 모터 하나만 RESET해도 같은 IP의 무관한 활성 오류(예: 화재경보)까지 함께
     * 사라지는 결함이었다. 레거시 SQL(`ClsMariaDB.UpdateResetFlagMotor/Sensor/UpdateResetFlag`)을
     * 다시 확인해 서브타입(`snd_type_cd`의 두 번째 토큰: MOTOR/OPER/GATE)별로 정확히 대응하는
     * 컬럼 조건의 리포지토리 메서드를 각각 호출한다. 인식할 수 없는 서브타입은 — 레거시도
     * `switch`문에 없는 값이면 아무 것도 갱신하지 않았으므로 — 동일하게 아무 것도 resolve하지
     * 않고 경고만 남긴다(예전처럼 안전 실패 대신 "전부 resolve"로 폭넓게 처리하지 않는다).
     */
    private suspend fun resolveGateErrors(dtlIp: String, sndUser: String?, subType: String?) {
        val resolveUser = sndUser ?: "system"
        val now = LocalDateTime.now()
        // 레거시 `UpdateResetFlagGeneric`과 동일하게 "어제 00:00 ~ 오늘 23:59"로 대상을 제한한다.
        val today = LocalDate.now()
        val sinceDate = today.minusDays(1).format(ANAL_DATE_PATTERN) + "0000"
        val untilDate = today.format(ANAL_DATE_PATTERN) + "2359"

        try {
            val resolvedCount = when (subType) {
                RESET_SUBTYPE_MOTOR -> dataReceiveAnalysisRepository.resolveMotorErrors(dtlIp, resolveUser, now, sinceDate, untilDate)
                RESET_SUBTYPE_OPER -> dataReceiveAnalysisRepository.resolveSensorErrors(dtlIp, resolveUser, now, sinceDate, untilDate)
                RESET_SUBTYPE_GATE -> dataReceiveAnalysisRepository.resolveGateErrors(dtlIp, resolveUser, now, sinceDate, untilDate)
                else -> {
                    logger.warn(
                        "[SendJob] 인식할 수 없는 RESET 서브타입이라 오류 resolve를 건너뜁니다. snd_type_cd 서브타입={}, ip={}",
                        subType,
                        dtlIp,
                    )
                    0
                }
            }
            if (resolvedCount > 0) {
                logger.debug(
                    "[SendJob] RESET_{} 명령 전송 성공 — 미해결 오류 {}건 resolve 처리: ip={}",
                    subType,
                    resolvedCount,
                    dtlIp,
                )
            }
        } catch (ex: Exception) {
            logger.error("[SendJob] 리셋 플래그 갱신 중 오류: ip={}, 서브타입={}", dtlIp, subType, ex)
        }
    }

    companion object {
        private const val PENDING_SND_YN = "N"
        private const val PENDING_CHK_YN = "N"
        private const val RESET_TYPE_PREFIX = "RESET"

        // 레거시 `ClsConst.PROBLEM_MOTOR/OPER/GATE`(= snd_type_cd의 "RESET_" 다음 토큰) 대응.
        private const val RESET_SUBTYPE_MOTOR = "MOTOR"
        private const val RESET_SUBTYPE_OPER = "OPER"
        private const val RESET_SUBTYPE_GATE = "GATE"

        /** `anal_date`(yyyyMMddHHmm 문자열) 비교용 날짜 포맷 — 레거시 `DATE_FORMAT(...,'%Y%m%d')` 대응. */
        private val ANAL_DATE_PATTERN: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

        /**
         * 레거시 `RESEND_GUARD`(5초) 대응 — 실패한 행의 짧은 재시도 쿨다운.
         * [Codex 적대적 리뷰 수정] 이제 인메모리 맵이 아니라 `DataSend.nextAttemptAt`(DB 컬럼)에
         * 반영되므로, JVM 재시작/다중 인스턴스에서도 동일하게 적용되고 조회 조건에도 반영된다.
         */
        private val RESEND_GUARD: Duration = Duration.ofSeconds(5)

        /**
         * [Codex 어드버서리얼 리뷰 수정] 행을 선점(claim)한 뒤 실제 처리가 끝나기 전까지 다른
         * 프로세스가 같은 행을 다시 선점하지 못하게 막는 리스 기간. 처리가 정상 종료되면(성공/실패
         * 모두) `nextAttemptAt`이 최종 값으로 다시 저장되어 이 리스는 즉시 실제 상태로 대체된다 —
         * 이 값은 오직 "처리 도중 프로세스가 죽어 최종 저장까지 못 갔을 때" 행이 영구히 멈추지
         * 않도록 하는 상한선이다.
         */
        private val CLAIM_LEASE: Duration = Duration.ofSeconds(30)
    }
}
