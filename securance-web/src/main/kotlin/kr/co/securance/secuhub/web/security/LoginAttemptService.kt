package kr.co.securance.secuhub.web.security

import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent
import org.springframework.security.authentication.event.AuthenticationSuccessEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * 로그인 실패 브루트포스 방어(회고 리뷰 항목). 계정별 실패 횟수를 메모리에 세고,
 * 임계치를 넘으면 일정 시간 동안 잠근다. [SecurityUserDetailsService]가 잠금 여부를
 * `UserDetails.isAccountNonLocked`에 반영해 Spring Security의 `AccountStatusUserDetailsChecker`가
 * `LockedException`으로 로그인을 거부하게 만든다.
 *
 * **맵 포화 공격에 대한 방어(적대적 리뷰 항목)**: 존재하지 않는 계정에 대한 실패도 이벤트가
 * 발행되므로(사용자 열거 방지를 위해 스프링 시큐리티가 `UsernameNotFoundException`을
 * `BadCredentialsException`으로 감춘다), 서로 다른 무작위 아이디를 계속 실패시키는 공격이 가능하다.
 * 이전에는 상한(MAX_TRACKED_USERNAMES) 도달 시 "새 아이디는 더 이상 추적하지 않음"으로 방어했는데,
 * 이는 공격자가 정확히 상한만큼 가짜 아이디로 맵을 먼저 채워버리면 그 이후 실존 계정의 실패는
 * *영원히* 집계되지 않아 브루트포스 방어 자체가 완전히 무력화되는 우회로였다. 대신 맵을
 * **LRU(가장 오래 미사용) 축출**로 자체 제한한다 — 새 실패는 항상 기록되고, 공간이 필요하면
 * 가장 오랫동안 조회/갱신되지 않은 항목이 먼저 밀려난다. 공격자의 일회성 무작위 아이디는 다시
 * 조회되지 않아 곧 축출 1순위가 되는 반면, 실제 로그인 중인 계정은 매 시도마다 접근되어 "최근
 * 사용됨"으로 남는다. 대가는, 지속적인 고강도 공격 하에서는 이미 잠긴 계정도 이론상 조기에
 * 축출(=잠금이 15분보다 일찍 풀림)될 수 있다는 것 — 완전 우회보다는 훨씬 나은 성능 저하다.
 *
 * 다중 인스턴스 배포에서는 인스턴스별로 카운트가 분리되어 사실상 임계치가 인스턴스 수만큼 늘어나는
 * 한계가 있다 — 계획서 6절의 Redis 캐시가 켜지면(`securance.cache.redis.enabled`) 공유 스토어로
 * 옮기는 것이 후속 과제다. 단일 인스턴스 배포에서는 이 메모리 기반 구현으로 충분하다.
 */
@Component
class LoginAttemptService(
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    private data class Attempt(val count: AtomicInteger = AtomicInteger(0), @Volatile var lockedUntil: Instant? = null)

    // LinkedHashMap(accessOrder=true) + removeEldestEntry로 LRU 상한을 구현한다. get/put이 모두
    // "접근"으로 카운트되어 순서를 갱신하므로, Collections.synchronizedMap으로 감싸 모든 접근을
    // 동기화해야 한다(LinkedHashMap 자체는 스레드 안전하지 않고, accessOrder 갱신은 get()에서도
    // 내부 연결 리스트를 변경하는 쓰기 연산이라 ConcurrentHashMap으로는 대체할 수 없다).
    private val attempts: MutableMap<String, Attempt> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Attempt>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Attempt>?): Boolean =
                size > MAX_TRACKED_USERNAMES
        },
    )

    fun isLocked(username: String): Boolean {
        synchronized(attempts) {
            val attempt = attempts[username] ?: return false
            val lockedUntil = attempt.lockedUntil ?: return false
            if (Instant.now(clock).isBefore(lockedUntil)) return true
            // 잠금 시간이 지났으면 자동으로 초기화해 다음 로그인 시도부터 다시 카운트한다.
            attempts.remove(username)
            return false
        }
    }

    @EventListener
    fun onFailure(event: AuthenticationFailureBadCredentialsEvent) {
        // 반드시 BadCredentialsEvent만 구독해야 한다 — 상위 타입인 AbstractAuthenticationFailureEvent를
        // 구독하면 AuthenticationFailureLockedEvent(이미 잠긴 계정에 재시도)까지 여기로 들어와,
        // isLocked() 체크가 비밀번호 검증보다 먼저 일어나는 순서상 "잠긴 계정에 대한 재시도"가
        // 스스로 lockedUntil을 계속 미래로 갱신해 잠금이 영원히 풀리지 않는 자기 연장 버그가 된다.
        val username = event.authentication.name ?: return
        synchronized(attempts) {
            val attempt = attempts.getOrPut(username) { Attempt() }
            val count = attempt.count.incrementAndGet()
            if (count >= MAX_ATTEMPTS) {
                attempt.lockedUntil = Instant.now(clock).plus(LOCK_DURATION)
            }
        }
    }

    @EventListener
    fun onSuccess(event: AuthenticationSuccessEvent) {
        val username = event.authentication.name ?: return
        attempts.remove(username)
    }

    companion object {
        const val MAX_ATTEMPTS = 5
        const val MAX_TRACKED_USERNAMES = 10_000
        val LOCK_DURATION: Duration = Duration.ofMinutes(15)
    }
}
