package kr.co.securance.secuhub.web.security

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [GateControlReauthAttemptService] 검증 — 특히 [Codex 리뷰 지적, 2026-08-28]로 도입된
 * [GateControlReauthAttemptService.tryAcquire]의 원자성(잠금 확인 + 시도 예약을 한 연산으로 묶음)과
 * [Codex 적대적 리뷰 지적, 2026-08-28]로 도입된 [GateControlReauthAttemptService.recordSuccess]의
 * 예약 단위 정산(성공 한 건이 동시에 쌓인 다른 요청의 상태까지 지우지 않음)을 고정한다.
 *
 * 이전 구현(`isLocked()` 확인 → 비밀번호 검증 → `recordFailure()`)은 같은 사용자에 대한 병렬
 * 요청이 전부 확인을 통과한 뒤 카운트가 반영돼, 서버의 동시 처리량만큼 잠금 임계치를 우회할 수
 * 있었다. 그 뒤 첫 번째 수정(사용자명만으로 `recordSuccess(username)`을 호출)은, 동시에 쌓인
 * 공격자의 실패 예약이 무관한 성공 한 건으로 통째로 사면되는 새로운 구멍을 남겼다. 두 번째
 * 수정("내 예약이 가장 최근이다"라는 조건만으로 전체 초기화 여부를 판단)도 예약 *순서*만 볼 뿐
 * 이전 예약들이 실제로 해소됐는지는 보지 않아, 가장 나중에 예약된 요청이 먼저 성공하면 여전히
 * 같은 구멍이 재현됐다(아래 "P1 회귀" 테스트). 지금 구현은 성공이 항상 자신의 예약 1건만
 * 되돌리고, 카운트가 정확히 0이 될 때만 전체(잠금 포함)를 초기화한다.
 */
class GateControlReauthAttemptServiceTest {

    @Test
    fun `순차 호출은 MAX_ATTEMPTS번째까지만 허용되고 그 다음은 거부된다`() {
        val service = GateControlReauthAttemptService()
        repeat(GateControlReauthAttemptService.MAX_ATTEMPTS) {
            assertNotNull(service.tryAcquire("tester"))
        }
        assertNull(service.tryAcquire("tester"))
    }

    @Test
    fun `첫 시도에 곧바로 성공하면 전체가 초기화된다`() {
        // 예약이 이것 하나뿐이었던(=이전에 아무 실패도 없었던) 가장 흔한 UX — 성공으로 그 예약 1건을
        // 되돌리면 카운트가 정확히 0이 되므로 안전하게 전체를 초기화할 수 있다.
        val service = GateControlReauthAttemptService()
        val ticket = service.tryAcquire("tester")
        assertNotNull(ticket)
        service.recordSuccess(ticket)

        repeat(GateControlReauthAttemptService.MAX_ATTEMPTS) {
            assertNotNull(service.tryAcquire("tester"))
        }
    }

    @Test
    fun `실패가 남아있는 채로 성공해도 그 실패 기록은 지워지지 않는다(Codex 재지적 회귀)`() {
        // Codex 재지적 회귀 테스트: "내 예약이 가장 최근이다(count == countAtReservation)"라는
        // 조건만으로 전체 초기화 여부를 판단하던 이전 수정은, 병렬 공격에서 가장 나중에 예약된
        // 요청이 먼저 성공하면 여전히 이 조건이 참이 되어 그 이전 예약들의 실패 기록까지 통째로
        // 사라졌다. 이제는 성공이 자신의 예약 1건만 되돌리므로, 그 전에 남은 실패(2건)는 그대로
        // 남아 다음 시도의 카운트에 반영돼야 한다.
        val service = GateControlReauthAttemptService()

        repeat(2) { assertNotNull(service.tryAcquire("tester")) } // 실패 예약 2건 (count=2)
        val successTicket = service.tryAcquire("tester") // 성공 예약 (count=3)
        assertNotNull(successTicket)

        service.recordSuccess(successTicket) // 성공 반영 — 자신의 예약 1건만 되돌아간다(count=2)

        // 남은 카운트가 2이므로, 잠금 임계치(MAX_ATTEMPTS)까지 남은 여유는 MAX_ATTEMPTS-2건뿐이어야
        // 한다 — 즉 이전의 실패 2건이 사면되지 않고 그대로 이어졌는지를 검증한다.
        repeat(GateControlReauthAttemptService.MAX_ATTEMPTS - 2) {
            assertNotNull(service.tryAcquire("tester"))
        }
        assertNull(service.tryAcquire("tester")) // 잠금 임계치 도달 — 사면되지 않았다면 여기서 잠긴다
    }

    @Test
    fun `성공 예약 사이에 다른 요청이 끼어들면 그 요청 몫은 지워지지 않는다(Codex 적대적 리뷰 회귀)`() {
        // Codex 적대적 리뷰 지적 회귀 테스트: 공격자가 먼저 실패를 3건 쌓은 뒤, 정상 사용자의 성공
        // 예약이 끼어들고, 그 사이 공격자가 5번째 예약으로 잠금까지 세운다. 정상 사용자의 성공을
        // 반영해도(recordSuccess) 그 사이 쌓인 공격자의 실패/잠금은 그대로 남아야 한다 — 그렇지
        // 않으면 공격자가 무관한 성공 한 건에 편승해 잠금을 피하고 재시도를 무제한 확보하게 된다.
        val service = GateControlReauthAttemptService()

        repeat(3) { assertNotNull(service.tryAcquire("tester")) } // 공격자 실패 예약 3건 (count=3)
        val successTicket = service.tryAcquire("tester") // 정상 사용자 성공 예약 (count=4)
        assertNotNull(successTicket)
        assertNotNull(service.tryAcquire("tester")) // 공격자 5번째 예약 — 잠금 임계치 도달, 잠금 설정
        assertNull(service.tryAcquire("tester")) // 이미 잠겨 있으므로 공격자의 추가 시도는 거부

        service.recordSuccess(successTicket) // 정상 사용자의 성공을 반영

        // 성공 예약 자신의 몫(1건)만 되돌아갔을 뿐 — 공격자가 세운 잠금은 여전히 유효해야 한다.
        assertNull(service.tryAcquire("tester"))
    }

    @Test
    fun `가장 나중에 예약된 요청이 먼저 성공해도 앞선 예약의 잠금은 사면되지 않는다(P1 회귀)`() {
        // Codex 리뷰 P1 지적의 정확한 재현: 5개 요청이 카운트 1~5를 병렬로 예약한 뒤, "가장 나중에
        // 예약된"(5번, 잠금을 직접 발동시킨) 요청이 성공한다. 예약 순서만 보던 이전 수정은 이 경우
        // "내가 가장 최근 예약"이라는 조건이 참이 되어 전체(잠금 포함)를 지웠다 — 공격자가 매
        // 배치의 마지막 한 건만 정답을 흘려 넣으면 잠금 자체를 무력화할 수 있었다.
        val service = GateControlReauthAttemptService()

        val tickets = (1..GateControlReauthAttemptService.MAX_ATTEMPTS).map {
            requireNotNull(service.tryAcquire("tester"))
        }
        assertNull(service.tryAcquire("tester")) // 5건째에서 이미 잠금 발동

        service.recordSuccess(tickets.last()) // 가장 나중에 예약된(5번) 요청이 성공

        // 잠금은 그대로 유효해야 한다 — 성공 한 건으로 앞선 4건의 실패와 잠금이 사면되면 안 된다.
        assertNull(service.tryAcquire("tester"))
    }

    @Test
    fun `잠금이 만료된 뒤의 예약은 새 사이클로 취급되어 성공 시 전체를 초기화한다`() {
        // 잠금 만료로 tryAcquire가 attempts.remove(username)을 수행하면 내부 Attempt 인스턴스가
        // 교체된다 — 이때 새 사이클의 첫 예약이 성공하면(정상적인 순차 사용) 여전히 전체 초기화가
        // 적용돼야 한다는 것을 고정한다.
        val mutableClock = MutableClock(java.time.Instant.parse("2026-08-28T00:00:00Z"))
        val service = GateControlReauthAttemptService(clock = mutableClock)
        repeat(GateControlReauthAttemptService.MAX_ATTEMPTS) {
            assertNotNull(service.tryAcquire("tester"))
        }
        assertNull(service.tryAcquire("tester")) // 잠김

        mutableClock.advance(GateControlReauthAttemptService.LOCK_DURATION.plusSeconds(1))

        val ticket = service.tryAcquire("tester") // 잠금 만료 → 새 사이클의 1번째 예약
        assertNotNull(ticket)
        service.recordSuccess(ticket)

        repeat(GateControlReauthAttemptService.MAX_ATTEMPTS) {
            assertNotNull(service.tryAcquire("tester"))
        }
    }

    private class MutableClock(private var instant: java.time.Instant) : java.time.Clock() {
        override fun getZone(): java.time.ZoneId = java.time.ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?): java.time.Clock = this
        override fun instant(): java.time.Instant = instant
        fun advance(duration: java.time.Duration) {
            instant = instant.plus(duration)
        }
    }

    @Test
    fun `동시에 몰리는 요청도 정확히 MAX_ATTEMPTS번만 허용된다(원자성 회귀 테스트)`() {
        // Codex 리뷰 지적 회귀 테스트: 같은 사용자에 대해 MAX_ATTEMPTS의 4배(20개) 스레드가 동시에
        // tryAcquire를 호출해도, 허용되는 횟수는 정확히 MAX_ATTEMPTS(5)여야 한다 — 그 이상 허용되면
        // 잠금 확인과 카운트 반영 사이에 경합 창이 남아있다는 뜻이다.
        val service = GateControlReauthAttemptService()
        val threadCount = GateControlReauthAttemptService.MAX_ATTEMPTS * 4
        val pool = Executors.newFixedThreadPool(threadCount)
        val startGate = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)
        val allowedCount = AtomicInteger(0)

        repeat(threadCount) {
            pool.submit {
                startGate.await()
                if (service.tryAcquire("tester") != null) allowedCount.incrementAndGet()
                doneLatch.countDown()
            }
        }
        startGate.countDown() // 모든 스레드가 동시에 tryAcquire를 호출하도록 신호를 한 번에 푼다.
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS), "스레드가 시간 내에 끝나지 않았습니다")
        pool.shutdown()

        assertEquals(GateControlReauthAttemptService.MAX_ATTEMPTS, allowedCount.get())
    }
}
