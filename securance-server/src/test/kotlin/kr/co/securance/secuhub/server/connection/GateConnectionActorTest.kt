package kr.co.securance.secuhub.server.connection

import kotlinx.coroutines.Dispatchers
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
}
