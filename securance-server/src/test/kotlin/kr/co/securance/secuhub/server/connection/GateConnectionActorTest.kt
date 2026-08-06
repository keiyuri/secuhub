package kr.co.securance.secuhub.server.connection

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kr.co.securance.secuhub.common.exception.GateTaskRejectedException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GateConnectionActorTest {

    @Test
    fun `제출한 작업은 순서대로 하나씩 실행된다`() {
        val actor = GateConnectionActor("192.168.0.1", Dispatchers.Default, queueCapacity = 100)
        val executedOrder = ConcurrentLinkedQueue<Int>()
        val latch = CountDownLatch(20)

        for (i in 1..20) {
            actor.submit {
                executedOrder.add(i)
                latch.countDown()
            }
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals((1..20).toList(), executedOrder.toList())
        actor.close()
    }

    @Test
    fun `대기열이 가득 차면 GateTaskRejectedException을 던진다`() {
        val actor = GateConnectionActor("192.168.0.2", Dispatchers.Default, queueCapacity = 1)
        val workerStarted = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)

        // 첫 작업이 워커를 점유하도록 만들어(dequeue된 상태) 대기열(capacity=1)을 온전히 채울 수 있게 한다.
        actor.submit {
            workerStarted.countDown()
            releaseWorker.await(5, TimeUnit.SECONDS)
        }
        assertTrue(workerStarted.await(5, TimeUnit.SECONDS))

        actor.submit { } // 대기열 슬롯(1개)을 채움 — 성공해야 함

        assertFailsWith<GateTaskRejectedException> { actor.submit { } } // 대기열 초과 — 거부되어야 함

        releaseWorker.countDown()
        actor.close()
    }

    @Test
    fun `닫힌 액터에 제출하면 즉시 거부한다`() {
        val actor = GateConnectionActor("192.168.0.3", Dispatchers.Default, queueCapacity = 10)
        actor.close()

        assertFailsWith<GateTaskRejectedException> { actor.submit { } }
    }

    @Test
    fun `큐에 대기 중인 작업이 실행되지 못한 채 액터가 닫혀도 submitAndAwait는 무기한 멈추지 않고 false를 반환한다`() =
        runBlocking {
            // [Codex 적대적 리뷰 회귀 테스트] 재연결로 옛 액터가 close()될 때, 이미 큐에 들어가
            // 실행을 기다리던 submitAndAwait 호출이 completion.await()에서 영원히 멈추지 않아야
            // 한다 — 멈추면 SendControlJob의 @DisallowConcurrentExecution 때문에 이후 잡 실행
            // 전체가 막히는 치명적 결함으로 이어진다.
            val actor = GateConnectionActor("192.168.0.4", Dispatchers.Default, queueCapacity = 10)
            val workerStarted = CountDownLatch(1)
            val releaseWorker = CountDownLatch(1)

            // 워커를 점유해 두 번째 작업이 큐에서 대기하도록 만든다(dequeue되지 않은 상태 재현).
            actor.submit {
                workerStarted.countDown()
                releaseWorker.await(5, TimeUnit.SECONDS)
            }
            assertTrue(workerStarted.await(5, TimeUnit.SECONDS))

            val pendingAwait = async(Dispatchers.Default) {
                actor.submitAndAwait { /* 큐에서 대기만 하다 close()로 폐기되어야 한다 */ }
            }

            delay(100) // pendingAwait가 확실히 큐에 들어갈 시간을 준다.
            actor.close()
            releaseWorker.countDown()

            val result = withTimeout(5_000) { pendingAwait.await() }
            assertEquals(false, result)
        }
}
