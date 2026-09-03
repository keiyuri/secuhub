package kr.co.securance.secuhub.server.control

import kr.co.securance.secuhub.common.util.HexCodec
import kr.co.securance.secuhub.domain.entity.DataSend
import kr.co.securance.secuhub.domain.repository.DataSendRepository
import kr.co.securance.secuhub.protocol.SpeedGateControlCommand
import kr.co.securance.secuhub.server.connection.GateConnectionRegistryImpl
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap

/** 폴링 1회의 처리 결과 요약. 잡 로그와 테스트 단언에 쓴다. */
data class DispatchSummary(
    val sent: Int = 0,
    val skipped: Int = 0,
    val confirmed: Int = 0,
    val retried: Int = 0,
    val failed: Int = 0,
    /** 전송 시도조차 못한 채 유효기간이 지나 실패 확정된 건수(R-1 대응, `expireStalePending`). */
    val expired: Int = 0,
) {
    val hasWork: Boolean get() = sent + confirmed + retried + failed + expired > 0
}

/**
 * `tb_data_snd` 기반 제어 명령 디스패처(2차 스프린트 1번 항목).
 *
 * 레거시 `ClsQuartzJobSendControl`의 이식이되, **ACK 대기/타임아웃/재전송**을 추가했다.
 * Quartz 잡([kr.co.securance.secuhub.scheduler.job.SendControlJob])은 이 클래스의
 * [dispatchPending]만 호출하므로, 재시도 로직 자체는 Quartz 없이 단위 테스트할 수 있다.
 *
 * ## 명령 1건의 수명주기
 * ```
 *  (N,N) 대기 ──전송 성공──▶ (Y,N) ACK 대기 ──장비 ACK──▶ (Y,Y) 확인 완료
 *                                  │
 *                                  └─ackTimeout 초과─┬─재시도 여유 있음─▶ (N,N)으로 되돌려 재전송
 *                                                    └─한도 초과────────▶ (Y,F) 실패 확정
 * ```
 *
 * ## 레거시 버그 회피
 * - **M-7/H-4**: 소켓 write는 반드시 [GateConnectionRegistryImpl.sendToLane](커넥션 액터 체인)을
 *   경유한다. 이 클래스는 Quartz 워커 스레드에서 돌지만 소켓을 직접 만지지 않는다.
 * - **중복 물리 전송**: 레거시가 `_recentSendAttempts`(쿨다운)와 `_inFlightSendIds`(체인 대기)
 *   두 자료구조로 막던 것을, "전송이 끝나기 전에는 `snd_yn`이 이미 Y로 바뀌어 폴링 대상에서
 *   빠진다 + 쿨다운"의 이중 가드로 대체한다. 액터 체인 제출은 즉시 반환하고 거부 시 false를
 *   돌려주므로, 레거시처럼 "타임아웃됐지만 나중에 실제로 전송되는 작업"이 생기지 않는다.
 * - **명령 영구 유실**: 전송이 거부(`REJECTED`)되면 DB를 건드리지 않아 다음 폴링에서 자연히
 *   재시도된다. 레거시는 일부 경로에서 미전송 상태로 `snd_yn='Y'`를 기록해 명령을 잃었다.
 *
 * ## 다중 인스턴스 대응 (3차 스프린트, Codex 리뷰 반영으로 4차에서 순서 재조정)
 * `NetCheckJob`/`ReqStatusJob`과 달리 이 디스패처는 인스턴스 간에 **공유되는** `tb_data_snd`를
 * 다룬다. `registry.findConnection() == null`이면 건드리지 않고 넘어가는 필터가 있어 실제로
 * 게이트 TCP 커넥션을 들고 있는 인스턴스만 전송을 시도하지만, 커넥션 소유권이 인스턴스 간에
 * 넘어가는 재접속 순간에는 두 인스턴스가 같은 행을 동시에 집어갈 수 있다.
 *
 * 신규 전송([sendPendingCommands])은 **먼저 [DataSendRepository.claimForSend]로 행을 원자적으로
 * 선점한 뒤에만** 실제 소켓 write를 수행한다 — 선점에 실패(영향 행 수 0)하면 다른 인스턴스가
 * 이미 처리 중이라는 뜻이므로 물리 전송 자체를 시도하지 않는다. 이전에는 먼저 보내고 그 다음
 * [DataSend.version] 낙관적 잠금으로 저장했는데, 그 시점엔 이미 양쪽 인스턴스가 전송을 마친
 * 뒤라 경합이 드러나도 중복 물리 전송(예: 게이트가 두 번 열림)을 막을 수 없었다. ACK
 * 확인/타임아웃 재시도 경로([confirmAckedCommands]/[reapAckTimeouts])는 이미 전송된 행만
 * 다루므로 여전히 [trySave]의 낙관적 잠금으로 충분하다 — 자세한 내용은 [DataSend]의 클래스
 * KDoc과 [trySave] 참고.
 *
 * ## 레인당 동시 in-flight 명령 1건 제한 (Codex 어드버서리얼 리뷰 대응)
 * ACK 프레임에는 어떤 명령에 대한 응답인지 식별할 필드가 없어(`SpeedGatePacketCodec.buildAck`),
 * 같은 (IP, 레인)에 명령이 2건 이상 동시에 ACK 대기 상태면 ACK 1건이 아직 수행되지 않은 명령까지
 * 함께 확인 처리할 수 있다 — 리셋 명령이면 아직 장비가 처리하지 않은 장애를 화면에서 지워버리는
 * 사고로 이어진다. [claimForSend]의 `NOT EXISTS` 조건이 같은 레인에 ACK 대기 중인 행이 있으면
 * 선점 자체를 막아, 인스턴스 수와 무관하게 레인당 동시 in-flight 명령이 항상 1건 이하로
 * 유지되게 한다. [sendPendingCommands]의 `lanesInFlight` 검사는 같은 배치 안에서 낭비되는
 * 선점 시도(claimForSend가 어차피 거부)를 줄이는 로컬 최적화일 뿐, 실제 강제력은 DB 쪽에 있다.
 *
 * 반대로 [kr.co.securance.secuhub.scheduler.job.NetCheckJob]/
 * [kr.co.securance.secuhub.scheduler.job.ReqStatusJob]은 인스턴스 로컬 상태(`GateConnectionRegistry`
 * 가 들고 있는 커넥션 집합)만 보고 동작하므로, Quartz JDBC JobStore로 클러스터링해 "인스턴스
 * 중 하나만 실행"하게 만들면 안 된다 — 다른 인스턴스에 연결된 게이트는 영원히 폴링되지 않게
 * 된다. 두 잡은 인스턴스마다 독립적으로(RAMJobStore, non-clustered) 도는 것이 의도된 설계다.
 */
@Component
class GateControlDispatcher(
    private val registry: GateConnectionRegistryImpl,
    private val dataSendRepository: DataSendRepository,
    private val faultResolutionService: GateFaultResolutionService,
    private val properties: ControlProperties,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    private val logger = LoggerFactory.getLogger(GateControlDispatcher::class.java)

    /** `snd_id` → 마지막 물리 전송 시각. 레거시 `_recentSendAttempts`(쿨다운)에 대응한다. */
    private val lastSentAt = ConcurrentHashMap<Long, Instant>()

    /** `snd_id` → 지금까지의 전송 시도 횟수. 재기동 시 초기화된다(최초 1회로 다시 센다). */
    private val attempts = ConcurrentHashMap<Long, Int>()

    /**
     * 장비 ACK가 도착한 (디바이스 IP, 레인 번호) → 수신 시각.
     *
     * 패킷 수신 스레드(Netty 이벤트루프/액터)에서 호출되므로 **여기서는 DB를 건드리지 않는다**
     * — 실제 `chk_yn` 갱신은 다음 폴링 주기에 잡 스레드가 수행한다(레거시 H-4의 재발 방지:
     * DB I/O를 통신 스레드에 올리지 않는다).
     */
    private val ackedLanes = ConcurrentHashMap<AckKey, Instant>()

    /**
     * 장비가 제어 명령(Object Code `GATE_SETTING` 0x4C)에 대한 ACK를 보냈을 때 호출된다.
     *
     * SpeedGate 프로토콜의 ACK 프레임에는 어떤 명령(`snd_id`)에 대한 응답인지 식별할 시퀀스
     * 번호가 없다 — `snd_id` 자체로 매칭하는 것은 프로토콜을 바꾸지 않는 한 불가능하다. 대신
     * ACK 패킷은 상태 블록의 대표 레인 번호는 담고 있으므로(`DefaultGatePacketHandler.
     * representativeLaneNo`), 이전처럼 IP 전체가 아니라 **(IP, 레인)** 단위로 좁혀 확인 처리한다.
     *
     * 이렇게 해도 같은 (IP, 레인)에 명령이 동시에 여러 건 대기 중이면(같은 레인에 명령을 연속
     * 발행) 첫 ACK가 여러 건을 한꺼번에 확인 처리할 수 있는 것은 여전하다 — 이 잔여 한계는
     * 프로토콜 자체의 한계이며, 다중 레인 장비에서 다른 레인의 명령까지 함께 확인 처리되던
     * 문제(IP 단위 매칭)만큼은 제거한다.
     */
    fun onDeviceControlAck(dtlIp: String, dtlLaneNo: Int) {
        ackedLanes[AckKey(dtlIp, dtlLaneNo)] = clock.instant()
    }

    /** (dtlIp, dtlLaneNo) 복합키 — `ackedLanes`/`confirmAckedCommands`에서만 쓰는 내부 키. */
    private data class AckKey(val dtlIp: String, val dtlLaneNo: Int)

    /**
     * 폴링 1회를 수행한다: ACK 확인 → 타임아웃 처리 → 신규/재전송 발송.
     *
     * 순서가 중요하다. 타임아웃 처리를 먼저 하면, 방금 도착한 ACK가 반영되기 전에 같은 명령이
     * 재전송되어 게이트가 두 번 동작할 수 있다.
     */
    fun dispatchPending(): DispatchSummary {
        val expired = expireStalePending()
        val confirmed = confirmAckedCommands()
        val (retried, failed) = reapAckTimeouts()
        val (sent, skipped) = sendPendingCommands()

        val summary = DispatchSummary(sent, skipped, confirmed, retried, failed, expired)
        if (summary.hasWork) {
            logger.debug(
                "제어 명령 폴링 결과: 전송={}, 보류={}, ACK확인={}, 재전송={}, 실패확정={}, 유효기간초과={}",
                sent, skipped, confirmed, retried, failed, expired,
            )
        }
        return summary
    }

    /**
     * 전송조차 되지 못한 채 [ControlProperties.pendingExpirySeconds]를 넘긴 대기 명령을 일괄
     * 실패 확정한다(코드 리뷰 지적 R-1) — [sendPendingCommands]/[findPendingCommands]가 겪는
     * 헤드 오브 라인 차단을 근본적으로 막는다. 자세한 이유는
     * [kr.co.securance.secuhub.domain.repository.DataSendRepository.expireStalePending] 참고.
     *
     * 단일 벌크 UPDATE라 DB 레벨에서 직렬화되므로 다중 인스턴스에서 동시에 돌아도 안전하다.
     */
    private fun expireStalePending(): Int {
        val cutoff = LocalDateTime.now(clock)
            .minusSeconds(properties.pendingExpirySeconds)
            .format(GateControlDateFormats.SEND_DATE)
        val expired = dataSendRepository.expireStalePending(cutoff)
        if (expired > 0) {
            logger.warn(
                "전송 유효기간({}초)을 넘긴 대기 명령을 실패 확정했습니다: {}건 (게이트 미접속/잘못된 대상 등)",
                properties.pendingExpirySeconds, expired,
            )
        }
        return expired
    }

    /**
     * ACK가 도착한 (IP, 레인)의 미확인 명령을 확인 처리하고, 리셋 명령이면 장애 해제까지 수행한다.
     *
     * 코드 리뷰 지적(2026-08-14): 예전에는 [ackedLanes]를 스냅샷 뜨자마자 무조건 비운 뒤,
     * `findAwaitingAck`의 첫 페이지(`batchSize`행)에서만 매칭을 시도했다 — 동시에 ACK 대기 중인
     * 명령이 `batchSize`를 넘는 극단적 상황(다수 장비 동시 재접속 등)에서는, 그 페이지 밖에 있는
     * 명령에 대한 정상 ACK가 이미 비워진 키를 찾지 못해 조용히 버려졌다. 그 명령은 실제로는
     * 장비가 수행했는데도 [reapAckTimeouts]가 결국 타임아웃으로 재전송해버려, 리셋/개폐처럼
     * 비멱등인 명령이 중복 실행될 위험이 있었다.
     *
     * 이제 매칭에 성공한 키만 그 자리에서 제거한다 — 이번 페이지에 없던 키는 [ackedLanes]에
     * 남아 다음 폴링 주기에 다시 시도된다(그 사이 도착한 새 ACK와 합쳐질 뿐 안전하다). 끝내 어떤
     * 대기 명령과도 매칭되지 않는 키(스퓨리어스 ACK 등)가 무한정 쌓이지 않도록 [purgeStaleAcks]로
     * 오래된 항목만 별도로 정리한다.
     *
     * [Codex 리뷰 지적] `trySave`가 DB에 쓰는 동안(코루틴 서스펜션 지점) 같은 (IP, 레인)에 대한
     * *다음* 명령의 ACK가 [onDeviceControlAck]로 도착하면 [ackedLanes]의 타임스탬프가 갱신된다. 이때
     * 무조건 `remove(key)`를 하면 방금 도착한 새 ACK까지 지워버려, 실제로는 장비가 수행한 다음
     * 명령이 매칭 근거를 잃고 [reapAckTimeouts]에 의해 불필요하게 재전송될 수 있었다. 확인
     * 시작 시점에 읽은 타임스탬프 값이 그대로일 때만 제거하도록 `remove(key, value)`(원자적
     * compare-and-remove)를 써서, 그 사이 갱신된 새 ACK는 다음 폴링 주기를 위해 남겨둔다.
     *
     * [Codex 적대적 리뷰 지적] [purgeStaleAcks]가 매칭되지 않은 ACK를 최대 `ackTimeoutSeconds * 2`
     * 까지 [ackedLanes]에 남겨두는데, 그 시간 동안 같은 (IP, 레인)에 **새 명령**이 전송되면 이
     * 지연/중복/스퓨리어스 ACK가 그 새 명령의 실제 전송을 기다리지 않고 즉시 확인 처리해버렸다
     * — 장비가 새 명령을 수행하지 않았는데도 `chkYn=YES`로 기록되고, 리셋 명령이면 아직 존재하는
     * 장애까지 화면에서 지워지는 허위 성공이었다. ACK 시각이 이 명령의 실제 물리 전송 시각
     * ([lastSentAt]) *이후*일 때만 확인 처리하도록 선후관계를 검증한다. 전송 시각을 아직 모르면
     * (재기동 직후 [reapAckTimeouts]가 채우기 전) 안전하게 이번 주기는 건너뛴다.
     */
    private fun confirmAckedCommands(): Int {
        if (ackedLanes.isEmpty()) return 0

        var confirmed = 0
        for (command in dataSendRepository.findAwaitingAck(PageRequest.of(0, properties.batchSize))) {
            val key = AckKey(command.dtlIp, command.dtlLaneNo)
            val ackedAt = ackedLanes[key] ?: continue
            val sndId = command.sndId ?: continue

            val sentAt = lastSentAt[sndId]
            if (sentAt == null || ackedAt.isBefore(sentAt)) continue

            command.chkYn = DataSend.YES
            if (!trySave(command, "ACK확인")) continue
            ackedLanes.remove(key, ackedAt)
            clearTracking(sndId)
            confirmed++

            resolveFaultsIfReset(command)
        }
        purgeStaleAcks()
        return confirmed
    }

    /**
     * 결국 어떤 대기 명령과도 매칭되지 못한 [ackedLanes] 항목(스퓨리어스 ACK, 이미 다른 경로로
     * 종료된 명령 등)이 무한정 쌓이는 것을 막는다 — ACK 타임아웃의 2배가 지나도록 매칭되지
     * 않았다면 더 기다려도 매칭될 대기 명령이 새로 생길 가능성이 낮다고 보고 버린다.
     */
    private fun purgeStaleAcks() {
        val cutoff = clock.instant().minus(Duration.ofSeconds(properties.ackTimeoutSeconds * 2))
        ackedLanes.entries.removeIf { it.value.isBefore(cutoff) }
    }

    /**
     * 리셋 명령이 장비에서 확인된 뒤에만 `tb_data_rcv_anal.resolve_yn`을 갱신한다.
     *
     * 레거시는 **전송 직후**(ACK 확인 없이) 해제했다 — 장비가 명령을 받지 못했어도 화면에서는
     * 장애가 사라져, 실제로 고장 난 게이트가 정상으로 보이는 문제가 있었다.
     */
    private fun resolveFaultsIfReset(command: DataSend) {
        val control = SpeedGateControlCommand.ofLegacyCode(command.sndTypeCd) ?: return
        if (!control.isReset) return

        try {
            faultResolutionService.resolveByResetCommand(
                dtlIp = command.dtlIp,
                dtlLaneNo = command.dtlLaneNo,
                command = control,
                resolvedBy = command.sndUser.ifBlank { "SYSTEM" },
            )
        } catch (ex: Exception) {
            // 장애 해제 실패가 명령 확인 자체를 되돌리면 안 된다 — 명령은 이미 장비에서 수행됐다.
            logger.error(
                "게이트[{}] 레인 {} 리셋 후 장애 해제 실패: snd_id={}",
                command.dtlIp, command.dtlLaneNo, command.sndId, ex,
            )
        }
    }

    /** ACK 타임아웃을 넘긴 명령을 재전송 대상으로 되돌리거나 실패 확정한다. */
    private fun reapAckTimeouts(): Pair<Int, Int> {
        val now = clock.instant()
        val timeout = Duration.ofSeconds(properties.ackTimeoutSeconds)
        var retried = 0
        var failed = 0

        for (command in dataSendRepository.findAwaitingAck(PageRequest.of(0, properties.batchSize))) {
            val sndId = command.sndId ?: continue
            val sentAt = lastSentAt[sndId]
            if (sentAt == null) {
                // 애플리케이션 재기동 등으로 전송 시각을 잃었다. 재전송하면 비멱등 명령(리셋/개폐)이
                // 중복 수행될 수 있으므로, 지금 시각을 기준으로 다시 세어 한 번 더 기다린다.
                lastSentAt[sndId] = now
                continue
            }
            if (Duration.between(sentAt, now) < timeout) continue

            val attemptCount = attempts[sndId] ?: 1
            if (attemptCount >= properties.maxSendAttempts) {
                command.chkYn = DataSend.FAILED
                if (!trySave(command, "실패확정")) continue
                clearTracking(sndId)
                failed++
                logger.warn(
                    "게이트[{}] 레인 {} 제어 명령 실패 확정 — ACK 미수신 {}회: snd_id={}, cmd={}",
                    command.dtlIp, command.dtlLaneNo, attemptCount, sndId, command.sndTypeCd,
                )
            } else {
                // 전송 대기 상태로 되돌려 다음 단계(sendPendingCommands)가 다시 집어가게 한다.
                command.sndYn = DataSend.NO
                if (!trySave(command, "타임아웃재시도")) continue
                retried++
                logger.info(
                    "게이트[{}] 레인 {} 제어 명령 ACK 미수신({}초) — 재전송 예약: snd_id={}, 시도={}회",
                    command.dtlIp, command.dtlLaneNo, properties.ackTimeoutSeconds, sndId, attemptCount,
                )
            }
        }
        return retried to failed
    }

    /** 전송 대기 명령을 게이트로 내보낸다. */
    private fun sendPendingCommands(): Pair<Int, Int> {
        val pending = dataSendRepository.findPendingCommands(PageRequest.of(0, properties.batchSize))
        if (pending.isEmpty()) return 0 to 0

        val now = clock.instant()
        val guard = Duration.ofSeconds(properties.resendGuardSeconds)
        var sent = 0
        var skipped = 0

        // 레인당 동시 in-flight 명령 1건 제한(Codex 어드버서리얼 리뷰 대응)의 인스턴스 로컬
        // 사전 필터. 진짜 강제력은 [DataSendRepository.claimForSend]의 NOT EXISTS 조건(DB 레벨,
        // 다중 인스턴스에도 적용)에 있다 — 여기서는 같은 배치 안에서 같은 레인 명령을 두 번
        // 시도하는 낭비(어차피 두 번째는 claimForSend에서 거부됨)만 미리 걸러낸다.
        val lanesInFlight = dataSendRepository.findAwaitingAck(PageRequest.of(0, properties.batchSize))
            .mapTo(mutableSetOf()) { it.dtlIp to it.dtlLaneNo }

        for (command in pending) {
            val sndId = command.sndId ?: continue
            val laneKey = command.dtlIp to command.dtlLaneNo

            if (laneKey in lanesInFlight) {
                // 같은 레인에 이미 ACK 대기 중인 명령이 있다 — 그 명령의 ACK(또는 실패 확정)가
                // 처리될 때까지 다음 명령을 보내지 않는다. 그러지 않으면 ACK 1건이 어떤 명령에
                // 대한 응답인지 프로토콜상 구분할 수 없어, 아직 수행되지 않은 명령까지 함께
                // 확인 처리될 수 있다(예: RESET이 조기 확정되어 장애 기록이 잘못 해제됨).
                skipped++
                continue
            }

            // 쿨다운: DB 갱신이 지연/실패하는 사이 같은 명령이 반복 전송되는 것을 막는다.
            val previous = lastSentAt[sndId]
            if (previous != null && Duration.between(previous, now) < guard) {
                skipped++
                continue
            }

            val packet = try {
                HexCodec.fromHex(command.sndRaw)
            } catch (ex: IllegalArgumentException) {
                // 인코딩 자체가 깨진 명령은 몇 번을 재시도해도 성공하지 않는다 — 즉시 실패 확정.
                command.sndYn = DataSend.YES
                command.chkYn = DataSend.FAILED
                // 다른 갱신 경로(confirmAckedCommands/reapAckTimeouts)와 동일하게 낙관적 잠금
                // 충돌 시(다른 인스턴스가 먼저 이 행을 갱신) clearTracking을 건너뛴다 — 저장이
                // 실패했는데도 로컬 추적을 지우면, DB에는 여전히 sndYn='N'으로 남아 다음 폴링에서
                // 다시 집히는데 로컬 상태만 지워져 동일한 인코딩 오류가 매 주기 반복된다
                // (2026-08-13 코드 리뷰).
                if (!trySave(command, "인코딩오류")) continue
                clearTracking(sndId)
                logger.error("제어 명령의 원시 데이터가 유효한 16진 문자열이 아닙니다: snd_id={}", sndId, ex)
                continue
            }
            if (packet.isEmpty()) {
                command.sndYn = DataSend.YES
                command.chkYn = DataSend.FAILED
                if (!trySave(command, "빈데이터")) continue
                clearTracking(sndId)
                logger.error("제어 명령의 원시 데이터가 비어 있습니다: snd_id={}", sndId)
                continue
            }

            if (registry.findConnection(command.dtlIp) == null) {
                // 장비 미접속 — 대기 상태로 남겨 재접속 후 자연히 전송되게 한다.
                skipped++
                continue
            }

            // 물리 전송 *전에* 이 인스턴스가 행을 선점한다(Codex 리뷰 P1). 두 인스턴스가 재접속
            // 전환 시점에 같은 대기 행을 동시에 집어가도, 이 원자적 UPDATE 자체가 DB에서
            // 직렬화되므로 단 한 인스턴스만 영향받은 행 수(1)를 받는다 — 그 인스턴스만 아래로
            // 내려가 실제 소켓 write를 수행한다. 이전에는 먼저 보내고 나중에 낙관적 잠금으로
            // 저장했는데, 그때는 이미 두 인스턴스 모두 전송을 마친 뒤라 경합이 드러나도 중복
            // 물리 전송 자체를 막을 수 없었다.
            val claimed = dataSendRepository.claimForSend(sndId, command.version, LOCAL_SERVER)
            if (claimed == 0) {
                logger.info(
                    "게이트[{}] 레인 {} 제어 명령 선점 실패(다른 인스턴스가 먼저 처리했거나 같은 레인에 " +
                        "ACK 대기 중인 명령이 있음) — 이번 주기는 건너뜀: snd_id={}",
                    command.dtlIp, command.dtlLaneNo, sndId,
                )
                skipped++
                continue
            }

            if (!registry.sendToLane(command.dtlIp, command.dtlLaneNo, packet)) {
                // 액터 대기열 포화 또는 이 소켓이 담당하지 않는 레인. 이미 선점(snd_yn='Y')했으므로
                // 반드시 대기 상태로 되돌려야 한다 — 그러지 않으면 미전송 명령이 영원히 폴링
                // 대상에서 빠져 유실된다(레거시가 재현하던 바로 그 패턴).
                dataSendRepository.releaseClaim(sndId)
                skipped++
                logger.warn(
                    "게이트[{}] 레인 {} 제어 명령 전송 보류(대기열 포화/레인 불일치) — 선점 해제: snd_id={}",
                    command.dtlIp, command.dtlLaneNo, sndId,
                )
                continue
            }

            lastSentAt[sndId] = now
            attempts.merge(sndId, 1, Int::plus)
            sent++
            // 이 배치 안에서 같은 레인의 다음 명령이 또 시도되지 않도록 즉시 in-flight로 표시한다.
            lanesInFlight += laneKey
            logger.info(
                "게이트[{}] 레인 {} 제어 명령 전송: snd_id={}, cmd={}, 시도={}회",
                command.dtlIp, command.dtlLaneNo, sndId, command.sndTypeCd, attempts[sndId],
            )
        }
        return sent to skipped
    }

    /** 명령이 종료(확인/실패)되면 추적 자료구조에서 제거한다 — 장기 가동 시 메모리 누수 방지(레거시 L-3). */
    private fun clearTracking(sndId: Long) {
        lastSentAt.remove(sndId)
        attempts.remove(sndId)
    }

    /**
     * 낙관적 잠금을 인지하는 저장 — 다중 인스턴스 동시 갱신 경합에서 진 쪽을 정상 흐름으로 처리한다
     * (3차 스프린트 "다중 인스턴스 대응", [DataSend.version] 참고).
     *
     * 다른 인스턴스가 이미 같은 행을 처리했다는 뜻이므로 예외를 삼키고 false를 돌려준다 — 호출부는
     * 이 배치 항목을 건너뛰고 나머지 항목 처리를 계속한다. 저장 실패 한 건이 폴링 사이클 전체를
     * 중단시키던(모든 save가 try/catch 밖에 있던) 이전 구조와 달리, 행 단위로 격리한다.
     */
    private fun trySave(command: DataSend, context: String): Boolean =
        try {
            dataSendRepository.save(command)
            true
        } catch (ex: ObjectOptimisticLockingFailureException) {
            logger.info(
                "게이트[{}] 레인 {} 제어 명령 갱신 경합(다른 인스턴스가 먼저 처리) — 이번 주기는 건너뜀: " +
                    "snd_id={}, 단계={}",
                command.dtlIp, command.dtlLaneNo, command.sndId, context,
            )
            false
        }

    companion object {
        /** `snd_server` 기본값 — 다중 인스턴스 배포 시 어느 서버가 보냈는지 구분하는 용도. */
        private val LOCAL_SERVER: String =
            runCatching { java.net.InetAddress.getLocalHost().hostAddress }.getOrDefault("unknown")
    }
}
