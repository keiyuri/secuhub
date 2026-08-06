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
import org.springframework.scheduling.quartz.QuartzJobBean
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

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
        cleanupStaleAttempts()

        val pending = dataSendRepository.findBySndYnAndChkYnOrderBySndId(PENDING_SND_YN, PENDING_CHK_YN)
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
                // chkYn='Y'로 표시해 findBySndYnAndChkYnOrderBySndId("N","N") 대상에서 제외한다
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

            val now = Instant.now()
            val lastAttempt = recentSendAttempts[sndId]
            if (lastAttempt != null && Duration.between(lastAttempt, now) < RESEND_GUARD) {
                // 쿨다운 이내 — 직전 스케줄에서 이미 전송을 시도했으므로 물리적 재전송을 건너뛴다.
                return
            }
            recentSendAttempts[sndId] = now

            val sent = registry.sendToLane(row.dtlIp, row.dtlLaneNo, packet)
            if (sent) {
                row.sndYn = "Y"
                dataSendRepository.save(row)
                recentSendAttempts.remove(sndId)

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
                // 커넥션 없음/대기열 초과 등 — DB는 그대로 두어 다음 스케줄(쿨다운 만료 후)에서 재시도한다.
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

    /**
     * 쿨다운 맵의 오래된 항목을 정리한다(레거시 `CleanupStaleSendAttempts`/`STALE_ENTRY_TTL` 대응).
     * 행 개수가 많지 않다는 전제하에 `NetCheckJob`처럼 매 실행마다 단순하게 전수 스캔한다 —
     * 레거시처럼 별도 정리 주기(1분)를 두는 최적화는 이번 스코프에서는 생략한다.
     */
    private fun cleanupStaleAttempts() {
        val now = Instant.now()
        recentSendAttempts.entries.removeIf { (_, lastAttempt) -> Duration.between(lastAttempt, now) >= STALE_ENTRY_TTL }
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

        /** 레거시 `RESEND_GUARD`(5초) 대응 — snd_id별 짧은 쿨다운으로 물리적 재전송을 억제한다. */
        private val RESEND_GUARD: Duration = Duration.ofSeconds(5)

        /** 레거시 `STALE_ENTRY_TTL`(5분) 대응 — 오래 방치된 쿨다운 항목을 정리한다. */
        private val STALE_ENTRY_TTL: Duration = Duration.ofMinutes(5)

        // Quartz 잡 인스턴스는 실행마다 새로 만들어질 수 있으므로, 레거시의 static
        // ConcurrentDictionary와 동등하게 동작하도록 companion object(클래스당 1개, JVM 수명 공유)에 둔다.
        private val recentSendAttempts = ConcurrentHashMap<Long, Instant>()
    }
}
