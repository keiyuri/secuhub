package kr.co.securance.secuhub.web.security

import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * 게이트 제어 재인증([GateControlReauthService])의 온라인 브루트포스 방어(코드 리뷰 지적, 2026-08-28).
 *
 * [GateControlReauthService]의 기존 KDoc은 "잠금까지 적용하면 반복 오타 한 번으로 이미 로그인된
 * 세션의 제어 기능 전체가 잠겨버려 운영 대응이 막힌다"는 이유로 [LoginAttemptService]와 같은 잠금을
 * 의도적으로 걸지 않았다. 그런데 재인증 기능의 존재 목적 자체가 "세션이 이미 로그인돼 있어도(예:
 * 방치된 브라우저 탈취) 실제 제어 명령 실행 순간에는 한 번 더 본인 확인"이므로, 방치된 세션을
 * 탈취한 공격자가 `reauthPassword`를 무제한 반복 시도해 온라인 브루트포스로 비밀번호를 알아낼 수
 * 있다면 이 기능은 막으려던 바로 그 시나리오에서 방어 효과가 없다.
 *
 * 절충안으로, [LoginAttemptService]의 15분 잠금보다 훨씬 짧은 **1분 쿨다운**을 둔다 — 온라인
 * 무차별 대입을 실질적으로 무력화하기에 충분히 느리게 만들면서(8자 이상 비밀번호 기준 초당 1회
 * 미만 시도로는 현실적 시간 내 완주 불가), 정상 운영자가 비밀번호를 한두 번 오타내도 운영 대응이
 * 장시간 막히지 않게 한다.
 *
 * **원자적 예약(Codex 리뷰 지적, 2026-08-28)**: 처음 구현은 `isLocked()`로 확인한 뒤 비밀번호를
 * 검증하고 실패 시 별도로 `recordFailure()`를 호출하는 3단계였다 — 같은 사용자에 대한 요청이
 * 병렬로 들어오면 전부 `isLocked()`를 통과한 뒤(카운트가 아직 반영되지 않은 시점에) 각자 비밀번호를
 * 시도할 수 있어, 서버의 동시 처리량만큼 잠금 임계치를 우회할 수 있었다. [tryAcquire]는 "이번
 * 시도를 진행해도 되는지 확인"과 "실패로 간주해 카운트 반영"을 하나의 동기화 블록에서 원자적으로
 * 수행한다.
 *
 * **예약 단위 정산(Codex 적대적 리뷰 지적, 2026-08-28)**: [tryAcquire]가 반환한 [ReauthAttemptTicket]
 * 없이 사용자명만으로 [recordSuccess]를 호출하던 첫 구현은, 성공 시 그 사용자의 추적 상태를
 * 통째로 지웠다 — 같은 계정에 대해 공격자가 병렬로 실패를 쌓아 두는 도중, 다른 요청(예: 실제
 * 소유자의 우연히 동시에 일어난 정상 재인증)이 성공하면 그 성공 한 건이 공격자가 쌓아둔 실패
 * 예약과 잠금까지 전부 사면해 버렸다.
 *
 * **"가장 최근 예약"만으로는 부족했다(Codex 리뷰 재지적, 2026-08-28)**: 그 다음 수정은 "내 예약
 * 번호(countAtReservation)가 현재 카운트와 같다(=내가 가장 최근 예약이다)"일 때만 전체를
 * 초기화했다. 그런데 이 조건은 "내 예약 *이후*로 아무도 건드리지 않았다"만 증명할 뿐, "내 예약
 * *이전*의 실패 예약들이 실제로 무해했는지"는 전혀 증명하지 못한다 — 병렬 공격에서 5개 요청이
 * 카운트 1~5를 예약한 뒤 가장 나중에 예약된(5번) 요청이 먼저 성공하면, 이 조건은 여전히 참이 되어
 * 1~4번(진행 중이거나 이미 실패로 확정된) 예약의 기록과 잠금까지 통째로 사라진다 — 공격자가 병렬
 * 배치마다 실패 횟수를 초기화할 수 있는 동일한 구멍이 형태만 바뀌어 남아 있었다.
 *
 * 이제 [recordSuccess]는 그 순서와 무관하게 **자신의 예약 1건만** 되돌린다(카운트를 1만 감소).
 * 그 결과 카운트가 0이 될 때만(=이 사이클에 존재했던 모든 예약이 전부 이 성공으로 해소됐을
 * 때만) 상태 전체(잠금 포함)를 지운다 — 첫 시도에 곧바로 성공하는 일반적인 UX는 그대로 보존되고,
 * 그 전에 실패(자신의 것이든 동시 공격자의 것이든)가 하나라도 남아 있었다면 그 실패 기록과 잠금은
 * 절대 이 성공 한 건으로 사면되지 않는다.
 */
@Component
class GateControlReauthAttemptService(
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    private data class Attempt(val count: AtomicInteger = AtomicInteger(0), @Volatile var lockedUntil: Instant? = null)

    private val attempts: MutableMap<String, Attempt> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Attempt>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Attempt>?): Boolean =
                size > MAX_TRACKED_USERNAMES
        },
    )

    /**
     * [tryAcquire] 한 번의 예약을 가리키는 불투명한 토큰. [recordSuccess]에 반드시 이 토큰을
     * 넘겨야 하며, 다른 사용자/다른 예약의 상태를 실수로 건드리지 않도록(예: 잠금 만료로 이미
     * 새 사이클로 교체된 경우) 내부적으로 원본 [Attempt] 인스턴스를 들고 있는다.
     */
    class ReauthAttemptTicket internal constructor(
        internal val username: String,
        private val attempt: Any,
    ) {
        internal fun isFor(candidate: Any) = attempt === candidate
    }

    /**
     * 잠금 여부 확인과 실패 카운트 예약을 하나의 원자적 연산으로 묶는다.
     *
     * @return null이면 쿨다운 중이므로 [GateControlReauthService]는 비밀번호 검증 자체를 건너뛰고
     *   즉시 실패 처리해야 한다. null이 아니면 이번 호출이 비밀번호 검증을 진행해도 된다는 뜻이다
     *   (카운트는 이미 실패로 간주해 반영됐다 — 실제로 성공하면 반드시 반환된 티켓으로
     *   [recordSuccess]를 호출해 되돌려야 한다).
     */
    fun tryAcquire(username: String): ReauthAttemptTicket? {
        synchronized(attempts) {
            val existing = attempts[username]
            val lockedUntil = existing?.lockedUntil
            if (lockedUntil != null) {
                if (Instant.now(clock).isBefore(lockedUntil)) return null
                // 잠금 시간이 지났으면 초기화하고 이번 호출부터 다시 카운트한다.
                attempts.remove(username)
            }
            val attempt = attempts.getOrPut(username) { Attempt() }
            val count = attempt.count.incrementAndGet()
            if (count >= MAX_ATTEMPTS) {
                attempt.lockedUntil = Instant.now(clock).plus(LOCK_DURATION)
            }
            return ReauthAttemptTicket(username, attempt)
        }
    }

    /**
     * 비밀번호 검증에 실제로 성공했을 때, 그 성공을 발생시킨 [tryAcquire] 호출이 반환한 [ticket]과
     * 함께 호출한다.
     *
     * [tryAcquire]가 이번 예약을 "일단 실패로 간주"하고 낙관적으로 반영해 둔 카운트 1건을
     * 되돌린다(항상 1만 감소 — "가장 최근 예약인지"는 더 이상 보지 않는다, 클래스 KDoc의 재지적
     * 참고). 그 결과 카운트가 0이 됐을 때만(=이 사이클에 존재했던 모든 예약이 이 성공으로 전부
     * 해소됐을 때만) 상태 전체(잠금 포함)를 지운다. 그렇지 않다면 — 이 티켓 이전에 아직 남아 있는
     * 실패 기록이 있다는 뜻이므로(같은 사용자의 이전 오타든, 동시에 진행 중인 공격자의 몫이든) —
     * 잠금과 나머지 카운트를 그대로 둔다.
     */
    fun recordSuccess(ticket: ReauthAttemptTicket) {
        synchronized(attempts) {
            val attempt = attempts[ticket.username] ?: return
            if (!ticket.isFor(attempt)) return // 잠금 만료 등으로 이미 새 사이클로 교체됨 — 되돌릴 예약 없음
            if (attempt.count.decrementAndGet() <= 0) {
                attempts.remove(ticket.username)
            }
        }
    }

    companion object {
        const val MAX_ATTEMPTS = 5
        const val MAX_TRACKED_USERNAMES = 10_000
        val LOCK_DURATION: Duration = Duration.ofMinutes(1)
    }
}
