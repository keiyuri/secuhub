package kr.co.securance.secuhub.web.security

import org.slf4j.LoggerFactory
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * `securance.security.gate-control-reauth-required=true`일 때 [GateControlReauthInterceptor]가
 * 호출하는 비밀번호 재확인 로직.
 *
 * 최초 로그인([LoginAttemptService])과 달리 여기서는 계정을 잠그지 않는다 — 이미 세션 인증을
 * 통과한 사용자의 2차 확인이라 계정 탈취 시나리오와 무관하고, 잠금까지 적용하면 반복 오타
 * 한 번으로 이미 로그인된 세션의 제어 기능 전체가 잠겨버려(로그인 잠금과 별개로) 운영 중 게이트
 * 대응이 막히는 부작용이 더 크다고 판단했다.
 *
 * 대신(2026-09-03 코드 리뷰 지적 — 세션이 탈취된 공격자가 재인증 프롬프트에 비밀번호를 무제한
 * 시도할 수 있다) 연속 실패한 사용자에게만 짧은 지수 백오프를 건다 — 계정을 잠그지 않고 다음
 * 시도까지 최소 대기 시간만 강제해, 정상 사용자의 오타 한 번은 거의 영향이 없지만(1초 이하)
 * 자동화된 대입 공격의 초당 시도 횟수는 크게 줄인다. [LoginAttemptService]처럼 LRU로 자체
 * 상한을 둬 서로 다른 무작위 사용자명으로 맵을 무한정 채우는 것도 막는다.
 *
 * **동시 요청 직렬화**(2026-09-03 Codex 리뷰 재지적 — [P1]): 백오프 확인과 실패 기록이 분리돼
 * 있으면, 같은 사용자명으로 동시에 여러 요청을 병렬 전송하는 공격이 첫 실패가 기록되기 전에
 * 전부 `isBackedOff` 통과 → 동시에 비밀번호 검증 → 백오프가 사실상 요청 1건당 한 번만 적용되는
 * 우회로가 된다. [reserve]가 "백오프 확인"과 "이 사용자명에 대한 검증 시작"을 하나의 락 안에서
 * 원자적으로 수행해, 같은 사용자명에 대한 검증은 항상 한 번에 하나만 진행되도록 직렬화한다 —
 * 이미 검증이 진행 중인 사용자명으로 들어온 동시 요청은 (아직 실패로 기록되지 않았더라도) 즉시
 * 거부된다.
 */
@Service
class GateControlReauthService(
    private val userDetailsService: UserDetailsService,
    private val passwordEncoder: PasswordEncoder,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    private val logger = LoggerFactory.getLogger(GateControlReauthService::class.java)

    private data class FailureState(
        val count: AtomicInteger = AtomicInteger(0),
        @Volatile var retryAfter: Instant? = null,
        @Volatile var verifying: Boolean = false,
    )

    // LoginAttemptService와 동일한 LRU 상한 패턴(그 클래스 KDoc 참고) — accessOrder=true라
    // get()도 내부 연결 리스트를 바꾸는 쓰기 연산이므로 synchronizedMap으로 전체를 감싼다.
    private val failures: MutableMap<String, FailureState> = Collections.synchronizedMap(
        object : LinkedHashMap<String, FailureState>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FailureState>?): Boolean =
                size > MAX_TRACKED_USERNAMES
        },
    )

    /** @return 비밀번호가 현재 사용자(username)의 것과 일치하면 true. */
    fun verify(username: String, rawPassword: String?): Boolean {
        val state = reserve(username) ?: return false
        try {
            if (rawPassword.isNullOrBlank()) {
                recordFailure(username, state)
                return false
            }
            val matched = try {
                val userDetails = userDetailsService.loadUserByUsername(username)
                passwordEncoder.matches(rawPassword, userDetails.password)
            } catch (ex: UsernameNotFoundException) {
                // 세션은 이미 인증돼 있는데 그 사이 계정이 삭제/비활성화된 극단적 경우 — 실패로 처리.
                logger.warn("재인증 대상 사용자를 찾을 수 없습니다: {}", username)
                false
            }
            if (matched) {
                // 이 호출이 예약한 state 객체가 여전히 맵의 현재 항목일 때만(참조 동일성 기준) 제거한다
                // (2026-09-03 코드 리뷰 재지적 — ABA 경쟁: remove 이후 같은 키로 다른 스레드가 새
                // state를 만들 수 있으므로, 그 다른 스레드가 만든 항목까지 지워버리면 안 된다).
                // FailureState는 data class라 Map.remove(key, value)의 값 비교(equals)에 기대지
                // 않고 `===`로 직접 확인한다 — count 필드가 AtomicInteger라 equals 의미가 불분명하다.
                synchronized(failures) { if (failures[username] === state) failures.remove(username) }
            } else {
                recordFailure(username, state)
            }
            return matched
        } finally {
            release(state)
        }
    }

    /**
     * [reserve]가 내준 state 객체 자체를 직접 해제한다 — username으로 맵을 다시 조회하지 않는다
     * (2026-09-03 재지적 — ABA 경쟁: remove 이후 같은 키로 다른 스레드가 새 state를 만들 수 있는데,
     * username으로 재조회하면 그 새 state를 잘못 해제해 직렬화 보장이 깨진다. 성공 시
     * `failures.remove(username)` ~ 이 호출 사이의 좁은 창에서 재현됨). `verify()`의 finally에서만
     * 호출하며, 별도 함수로 분리해 둔 이유는 테스트가 이 메서드를 reflection으로 직접 호출해
     * finally가 실제로 이 구현(state 참조 직접 해제)에 위임하는지 검증할 수 있게 하기 위함이다.
     */
    private fun release(state: FailureState) {
        state.verifying = false
    }

    /**
     * 백오프 확인과 "이 사용자명에 대한 검증 시작 표시"를 한 번의 락 안에서 원자적으로 수행한다
     * (클래스 KDoc "동시 요청 직렬화" 참고). 이미 다른 요청이 같은 사용자명을 검증 중이면
     * (아직 실패로 기록되지 않았어도) 예약에 실패해 즉시 거부된다.
     *
     * @return 이번 호출이 검증을 진행해도 되면 예약된 [FailureState](호출자는 반드시 finally에서
     *   이 객체의 verifying을 직접 해제해야 한다 — username으로 맵을 재조회하지 말 것). 아니면 null.
     */
    private fun reserve(username: String): FailureState? {
        synchronized(failures) {
            val state = failures.getOrPut(username) { FailureState() }
            val retryAfter = state.retryAfter
            if (retryAfter != null && Instant.now(clock).isBefore(retryAfter)) return null
            if (state.verifying) return null
            state.verifying = true
            return state
        }
    }

    /**
     * count번째 실패마다 지연을 [BASE_DELAY] * 2^(count-1)로 늘리고 [MAX_DELAY]에서 상한을 둔다.
     *
     * @param state [reserve]가 이 호출을 위해 예약해 준 객체를 그대로 받는다 — username으로 맵을
     *   다시 조회하지 않는다. LRU 축출로 그 사이 같은 키의 항목이 다른 객체로 바뀌었더라도, 실패
     *   카운트는 이 호출이 실제로 검증한 시도 기준으로 정확히 이 state에 반영돼야 하기 때문이다.
     */
    private fun recordFailure(username: String, state: FailureState) {
        synchronized(failures) {
            // 이 state가 이미 LRU로 축출돼 맵에서 빠졌다면 굳이 다시 넣지 않는다 — 새로 들어온
            // 요청은 새 state로 예약을 새로 받으므로, 축출된 이 객체에 값을 채워도 아무도 보지 않는다.
            if (failures[username] !== state) return@synchronized
            val count = state.count.incrementAndGet()
            val delay = minOf(BASE_DELAY.multipliedBy(1L shl (count - 1).coerceAtMost(10)), MAX_DELAY)
            state.retryAfter = Instant.now(clock).plus(delay)
        }
    }

    companion object {
        const val MAX_TRACKED_USERNAMES = 1000
        val BASE_DELAY: Duration = Duration.ofSeconds(1)
        val MAX_DELAY: Duration = Duration.ofSeconds(30)
    }
}
