package kr.co.securance.secuhub.web.security

import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.crypto.password.PasswordEncoder
import java.time.Clock
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [GateControlReauthService] 검증 — `securance.security.gate-control-reauth-required=true`일 때
 * [GateControlReauthInterceptor]가 위임하는 비밀번호 검증 로직. 잘못된/빈 비밀번호로 게이트 제어가
 * 통과되면 재인증 기능 자체가 무력화되므로 실패 경로를 먼저 검증한다.
 */
class GateControlReauthServiceTest {

    private val userDetailsService = mock(UserDetailsService::class.java)
    private val passwordEncoder = mock(PasswordEncoder::class.java)
    private val fixed = Instant.parse("2026-01-01T00:00:00Z")
    private val clock = MutableClock(fixed)
    private val service = GateControlReauthService(userDetailsService, passwordEncoder, clock)

    private fun stubUser(username: String, passwordHash: String) {
        `when`(userDetailsService.loadUserByUsername(username)).thenReturn(
            User.builder().username(username).password(passwordHash).authorities("ROLE_VIEW").build(),
        )
    }

    @Test
    fun `비밀번호가 일치하면 true를 반환한다`() {
        stubUser("tester", "hashed")
        `when`(passwordEncoder.matches("raw-pw", "hashed")).thenReturn(true)

        assertTrue(service.verify("tester", "raw-pw"))
    }

    @Test
    fun `비밀번호가 일치하지 않으면 false를 반환한다`() {
        stubUser("tester", "hashed")
        `when`(passwordEncoder.matches("wrong-pw", "hashed")).thenReturn(false)

        assertFalse(service.verify("tester", "wrong-pw"))
    }

    @Test
    fun `빈 비밀번호는 UserDetailsService를 조회하지 않고 즉시 실패한다`() {
        assertFalse(service.verify("tester", ""))
    }

    @Test
    fun `세션 인증 이후 계정이 사라졌으면 실패로 처리한다`() {
        `when`(userDetailsService.loadUserByUsername("ghost")).thenThrow(UsernameNotFoundException("no such user"))

        assertFalse(service.verify("ghost", "any-pw"))
    }

    @Test
    fun `연속 실패하면 다음 시도까지 지수 백오프로 대기해야 한다`() {
        // 계정을 잠그지는 않되(2026-09-03 코드 리뷰 지적), 세션 탈취 공격자가 초당 여러 번
        // 시도하는 것을 막는다. 첫 실패 직후 바로 재시도하면 비밀번호가 맞아도 백오프에 걸려 거부된다.
        stubUser("tester", "hashed")
        `when`(passwordEncoder.matches("wrong-pw", "hashed")).thenReturn(false)
        `when`(passwordEncoder.matches("raw-pw", "hashed")).thenReturn(true)

        assertFalse(service.verify("tester", "wrong-pw")) // 1번째 실패 → 1초 백오프 시작
        assertFalse(service.verify("tester", "raw-pw")) // 백오프 중 — 맞는 비밀번호라도 거부

        clock.instant = fixed.plusSeconds(1) // 백오프 만료
        assertTrue(service.verify("tester", "raw-pw"))
    }

    @Test
    fun `성공하면 실패 카운트가 초기화돼 다음 실패는 다시 짧은 백오프부터 시작한다`() {
        stubUser("tester", "hashed")
        `when`(passwordEncoder.matches("wrong-pw", "hashed")).thenReturn(false)
        `when`(passwordEncoder.matches("raw-pw", "hashed")).thenReturn(true)

        assertFalse(service.verify("tester", "wrong-pw"))
        clock.instant = fixed.plusSeconds(1)
        assertTrue(service.verify("tester", "raw-pw")) // 성공 → 카운트 초기화

        assertFalse(service.verify("tester", "wrong-pw")) // 다시 1번째 실패 — 1초면 충분
        clock.instant = clock.instant.plusSeconds(1)
        assertTrue(service.verify("tester", "raw-pw"))
    }

    @Test
    fun `같은 사용자명으로 동시에 들어온 요청은 직렬화돼 하나만 비밀번호를 검증한다`() {
        // 2026-09-03 Codex 리뷰 재지적([P1]): isBackedOff 확인과 실패 기록이 분리돼 있으면, 세션
        // 탈취 공격자가 같은 사용자명으로 요청을 병렬 전송해 첫 실패가 기록되기 전에 전부 통과시켜
        // 백오프를 무력화할 수 있었다. reserve()가 "확인 + 검증 시작 표시"를 한 락 안에서 원자적으로
        // 수행하므로, passwordEncoder.matches가 오래 걸리는 동안 들어온 동시 요청은 (아직 실패로
        // 기록되지 않았어도) 즉시 거부돼야 한다.
        stubUser("tester", "hashed")
        val inFlight = CountDownLatch(1)
        val release = CountDownLatch(1)
        val matchesCallCount = AtomicInteger(0)
        `when`(passwordEncoder.matches("raw-pw", "hashed")).thenAnswer {
            matchesCallCount.incrementAndGet()
            inFlight.countDown()
            release.await(5, TimeUnit.SECONDS)
            true
        }

        val executor = Executors.newFixedThreadPool(2)
        val first = executor.submit<Boolean> { service.verify("tester", "raw-pw") }
        assertTrue(inFlight.await(5, TimeUnit.SECONDS), "첫 번째 요청이 비밀번호 검증을 시작하지 않았습니다.")

        // 첫 요청이 아직 검증 중인 동안(= 아직 실패로 기록되지 않은 시점) 두 번째 요청을 보낸다.
        val second = executor.submit<Boolean> { service.verify("tester", "raw-pw") }
        assertFalse(second.get(5, TimeUnit.SECONDS), "검증이 진행 중인 사용자명에 대한 동시 요청은 즉시 거부돼야 합니다.")

        release.countDown()
        assertTrue(first.get(5, TimeUnit.SECONDS))
        executor.shutdown()

        assertEquals(1, matchesCallCount.get(), "동시 요청 중 단 하나만 실제 비밀번호 검증을 수행해야 합니다.")
    }

    @Test
    fun `검증 성공 직후 좁은 창에서 새로 예약된 요청을 finally가 잘못 해제하지 않는다`() {
        // 2026-09-03 재지적(ABA 경쟁): verify() 성공 시 failures.remove(username) 실행 이후,
        // finally가 실행되기 전의 좁은 창에서 같은 사용자명으로 새 요청이 reserve()를 통해 새
        // FailureState를 만들 수 있다. finally가 username으로 맵을 다시 조회해 verifying을
        // 끄면(구버전 버그) 그 새 예약을 잘못 해제해버려서, 뒤이은 세 번째 요청이 직렬화를
        // 우회해 통과한다. 실제 스레드 타이밍으로는 이 창이 너무 좁아 안정적으로 재현할 수
        // 없으므로, reflection으로 verify() 성공 경로의 순서(예약 → remove → 새 예약 → 해제)를
        // 직접 재현한다.
        //
        // (2026-09-03 재검토 지적) 4단계에서 verifying 필드를 손으로 false로 세팅하는 방식은
        // "finally가 이렇게 동작해야 한다"를 흉내낸 것일 뿐, verify()의 실제 finally 코드 경로를
        // 통과하지 않아 finally가 username 재조회로 되돌아가도 이 테스트가 잡아내지 못했다.
        // 그래서 finally의 해제 로직을 private release(state) 메서드로 분리하고, finally는
        // release(state) 하나만 호출하도록 고쳤다 — 이 테스트는 그 release()를 reflection으로
        // 직접 호출해, "finally가 실제로 위임하는 그 구현"이 state 참조만 건드리는지 검증한다.
        stubUser("tester", "hashed")

        val reserveMethod = GateControlReauthService::class.java.getDeclaredMethod("reserve", String::class.java)
            .apply { isAccessible = true }
        val releaseMethod = GateControlReauthService::class.java
            .getDeclaredMethod("release", Class.forName("kr.co.securance.secuhub.web.security.GateControlReauthService\$FailureState"))
            .apply { isAccessible = true }
        val failuresField = GateControlReauthService::class.java.getDeclaredField("failures")
            .apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val failuresMap = failuresField.get(service) as MutableMap<String, Any>

        // 1) A가 검증을 시작해 stateA를 예약받는다.
        val stateA = reserveMethod.invoke(service, "tester")
        assertTrue(stateA != null, "첫 예약은 성공해야 합니다.")

        // 2) A의 verify()가 성공 경로에서 하는 것과 동일하게 맵에서 제거한다.
        failuresMap.remove("tester")

        // 3) A의 finally가 아직 실행되기 전, B가 같은 사용자명으로 새로 예약을 받는다.
        val stateB = reserveMethod.invoke(service, "tester")
        assertTrue(stateB != null, "제거 직후에는 새 예약이 성공해야 합니다.")
        assertTrue(stateB !== stateA, "새로 생성된 state는 A의 state와 다른 객체여야 합니다.")

        // 4) A의 finally가 실제로 호출하는 release(state)를 그대로 실행한다 — stateB에는 영향이
        // 없어야 한다.
        releaseMethod.invoke(service, stateA)

        val verifyingFieldB = stateB!!.javaClass.getDeclaredField("verifying").apply { isAccessible = true }
        assertTrue(
            verifyingFieldB.get(stateB) as Boolean,
            "A의 finally(release)가 B의 예약을 잘못 해제하면 안 됩니다 — B는 여전히 검증 중이어야 합니다.",
        )

        // 5) B가 아직 검증 중(verifying=true)이므로, 세 번째 요청은 예약에 실패해야 한다.
        val stateC = reserveMethod.invoke(service, "tester")
        assertTrue(stateC == null, "B가 검증 중인 동안 C의 예약은 거부돼야 합니다(직렬화 보장).")
    }

    /** 백오프 만료 시각 경과를 시뮬레이션하기 위한 가변 [Clock](LoginAttemptServiceTest와 동일 패턴). */
    private class MutableClock(var instant: Instant) : Clock() {
        override fun instant(): Instant = instant

        override fun withZone(zone: java.time.ZoneId?): Clock = this

        override fun getZone(): java.time.ZoneId = java.time.ZoneOffset.UTC
    }
}
