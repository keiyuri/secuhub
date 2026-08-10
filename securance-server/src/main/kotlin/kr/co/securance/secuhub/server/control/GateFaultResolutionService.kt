package kr.co.securance.secuhub.server.control

import kr.co.securance.secuhub.domain.repository.DataReceiveAnalysisRepository
import kr.co.securance.secuhub.protocol.SpeedGateControlCommand
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** 장애 해제 대상 분류. 리셋 명령 종류와 장비 자동 복구 신호 양쪽에서 쓴다. */
enum class GateFaultCategory {
    /** 게이트 전체(운영/안전 센서 + 서브보드 + 모터). 시스템/메인보드 리셋에 대응. */
    ALL,

    /** 운영/안전 센서 계열. */
    SENSOR,

    /** 모터 계열. */
    MOTOR,

    /** 화재 경보. */
    FIRE,
    ;

    companion object {
        /**
         * 리셋 명령 → 해제 대상 매핑.
         *
         * 레거시는 `tb_data_snd.snd_data_tp`를 `'_'`로 잘라 첫 토큰이 `RESET`이면 두 번째 토큰
         * (`MOTOR`/`OPER`/`GATE`)으로 분기했다(`ClsQuartzJobSendControl.FinalizeSuccessfulSend`).
         * 문자열 파싱은 오타 시 조용히 아무것도 해제하지 않으므로 열거형 매핑으로 대체한다.
         */
        fun of(command: SpeedGateControlCommand): GateFaultCategory? = when (command) {
            SpeedGateControlCommand.RESET_SYSTEM,
            SpeedGateControlCommand.RESET_BOARD,
            -> ALL

            SpeedGateControlCommand.RESET_OPERATION_SENSOR,
            SpeedGateControlCommand.RESET_SAFETY_SENSOR,
            -> SENSOR

            SpeedGateControlCommand.RESET_MOTOR -> MOTOR

            else -> null
        }
    }
}

/**
 * `tb_data_rcv_anal.resolve_yn` 갱신 — 장애/이벤트 해제 처리(2차 스프린트 3번 항목).
 *
 * 두 가지 경로에서 호출된다.
 * 1. **운영자 리셋**: 제어 명령이 실제로 장비로 나간 뒤 [resolveByResetCommand].
 *    레거시 `ClsQuartzJobSendControl.FinalizeSuccessfulSend`의 `UpdateResetFlag*` 호출에 대응하며,
 *    **전송이 확정된 경우에만** 호출해야 한다 — 전송되지 않은 명령으로 장애를 해제하면 실제로는
 *    고장 난 게이트가 화면에서 정상으로 보인다.
 * 2. **장비 자동 복구**: 상태 패킷에서 장애 비트가 사라지면 [resolveByRecovery].
 *    레거시 DB 트리거 `utrg_data_rcv_anlz`의 `ELSE` 분기(센서/화재/모터 복구 UPDATE)에 대응한다.
 *
 * ### 레거시 대비 변경점
 * 레거시는 해제 조건에 `dtl_ip`만 넣어, 다중 레인 장비에서 1번 레인을 리셋하면 **2번 레인의
 * 미해결 장애까지 함께 해제**되었다. 여기서는 `dtl_lane_no`를 조건에 포함해 리셋한 레인만 해제한다.
 */
@Service
class GateFaultResolutionService(
    private val analysisRepository: DataReceiveAnalysisRepository,
) {
    private val logger = LoggerFactory.getLogger(GateFaultResolutionService::class.java)

    /**
     * 리셋 명령에 따른 장애 해제. 리셋 계열이 아닌 명령이면 아무 것도 하지 않고 0을 반환한다.
     *
     * @return 해제된 분석 행 수.
     */
    @Transactional
    fun resolveByResetCommand(
        dtlIp: String,
        dtlLaneNo: Int,
        command: SpeedGateControlCommand,
        resolvedBy: String,
    ): Int {
        val category = GateFaultCategory.of(command)
        if (category == null) {
            logger.debug("리셋 계열이 아닌 명령이라 장애 해제를 건너뜁니다: {}", command)
            return 0
        }
        return resolve(dtlIp, dtlLaneNo, category, resolvedBy, RESET_LOOKBACK)
    }

    /**
     * 장비가 장애 비트를 내렸을 때의 자동 해제.
     *
     * 조회 구간(lookback)은 레거시 트리거의 복구 분기와 동일하게 분류별로 다르다 —
     * 센서 3시간, 화재 1일, 모터 60초. 모터가 유독 짧은 것은 모터 부하 값이 매 패킷 요동쳐
     * 넓은 구간을 잡으면 과거 장애까지 무분별하게 해제되기 때문이다.
     */
    @Transactional
    fun resolveByRecovery(dtlIp: String, dtlLaneNo: Int, category: GateFaultCategory): Int {
        val lookback = when (category) {
            GateFaultCategory.SENSOR, GateFaultCategory.ALL -> Duration.ofHours(3)
            GateFaultCategory.FIRE -> Duration.ofDays(1)
            GateFaultCategory.MOTOR -> Duration.ofSeconds(60)
        }
        return resolve(dtlIp, dtlLaneNo, category, RECOVERY_USER, lookback)
    }

    private fun resolve(
        dtlIp: String,
        dtlLaneNo: Int,
        category: GateFaultCategory,
        resolvedBy: String,
        lookback: Duration,
    ): Int {
        val now = LocalDateTime.now()
        val fromDate = now.minus(lookback).format(ANAL_DATE_FORMAT)
        val toDate = now.format(ANAL_DATE_FORMAT)

        val resolved = when (category) {
            GateFaultCategory.ALL ->
                analysisRepository.resolveAllErrors(dtlIp, dtlLaneNo, fromDate, toDate, resolvedBy, now)

            GateFaultCategory.SENSOR ->
                analysisRepository.resolveSensorErrors(dtlIp, dtlLaneNo, fromDate, toDate, resolvedBy, now)

            GateFaultCategory.MOTOR ->
                analysisRepository.resolveMotorErrors(dtlIp, dtlLaneNo, fromDate, toDate, resolvedBy, now)

            GateFaultCategory.FIRE ->
                analysisRepository.resolveFireAlarms(dtlIp, dtlLaneNo, fromDate, toDate, resolvedBy, now)
        }

        if (resolved > 0) {
            logger.info(
                "게이트[{}] 레인 {} 장애 해제: 분류={}, {}건, 처리자={}",
                dtlIp, dtlLaneNo, category, resolved, resolvedBy,
            )
        }
        return resolved
    }

    companion object {
        /** `anal_date` 컬럼 포맷(`yyyyMMddHHmm`) — 문자열 비교로 범위를 거르므로 자릿수가 고정이어야 한다. */
        private val ANAL_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmm")

        /** 운영자 리셋의 조회 구간 — 레거시 `UpdateResetFlagGeneric`(전일 0시 ~ 현재)과 동일한 폭. */
        private val RESET_LOOKBACK: Duration = Duration.ofDays(1)

        /** 장비 자동 복구로 해제된 행의 `resolve_user` 값(레거시 트리거와 동일). */
        const val RECOVERY_USER = "SYSTEM"
    }
}
