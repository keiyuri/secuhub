package kr.co.securance.secuhub.server.db

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GateDbWriteQueueTest {

    @Test
    fun `작업이 성공하면 한 번만 실행되고 재시도하지 않는다`() {
        val queue = GateDbWriteQueue(shardCount = 1)
        val attemptCount = AtomicInteger(0)
        val done = CountDownLatch(1)

        queue.enqueue(
            GateDbWriteTask(partitionKey = "192.168.0.1", operationName = "OK") {
                attemptCount.incrementAndGet()
                done.countDown()
            },
        )

        assertTrue(done.await(2, TimeUnit.SECONDS))
        Thread.sleep(50) // 혹시 모를 추가 재시도가 없는지 확인하기 위한 짧은 유예
        assertEquals(1, attemptCount.get())

        queue.shutdown()
    }

    @Test
    fun `일시적 실패는 maxAttempts 내에서 재시도되어 결국 성공한다`() {
        val queue = GateDbWriteQueue(shardCount = 1)
        val attemptCount = AtomicInteger(0)
        val done = CountDownLatch(1)

        queue.enqueue(
            GateDbWriteTask(
                partitionKey = "192.168.0.2",
                operationName = "RETRY_THEN_SUCCEED",
                maxAttempts = 3,
                timeout = 1.seconds,
            ) {
                val attempt = attemptCount.incrementAndGet()
                if (attempt < 3) throw RuntimeException("일시적 DB 오류(시도 $attempt)")
                done.countDown()
            },
        )

        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(3, attemptCount.get())

        queue.shutdown()
    }

    @Test
    fun `maxAttempts를 모두 소진하면 더 이상 재시도하지 않고 포기한다`() {
        val queue = GateDbWriteQueue(shardCount = 1)
        val attemptCount = AtomicInteger(0)
        val attempted = CountDownLatch(3)

        queue.enqueue(
            GateDbWriteTask(
                partitionKey = "192.168.0.3",
                operationName = "ALWAYS_FAIL",
                maxAttempts = 3,
                timeout = 1.seconds,
            ) {
                attemptCount.incrementAndGet()
                attempted.countDown()
                throw RuntimeException("항상 실패")
            },
        )

        assertTrue(attempted.await(5, TimeUnit.SECONDS))
        Thread.sleep(200) // 백오프 지연 이후에도 4번째 시도가 없는지 확인
        assertEquals(3, attemptCount.get())
        // 적대적 리뷰 지적 회귀 테스트: 예전에는 최종 실패 카운터가 아예 없어 로그로만 확인할 수 있었다.
        assertEquals(1L, queue.finalFailureCount())

        queue.shutdown()
    }

    @Test
    fun `시도 하나가 timeout을 넘기면 실패로 간주되어 재시도한다`() {
        val queue = GateDbWriteQueue(shardCount = 1)
        val attemptCount = AtomicInteger(0)
        val done = CountDownLatch(1)

        queue.enqueue(
            GateDbWriteTask(
                partitionKey = "192.168.0.4",
                operationName = "SLOW_THEN_FAST",
                maxAttempts = 3,
                timeout = 100.milliseconds,
            ) {
                val attempt = attemptCount.incrementAndGet()
                if (attempt == 1) {
                    kotlinx.coroutines.delay(1.seconds) // 타임아웃(100ms)을 초과시켜 첫 시도를 실패시킨다.
                }
                done.countDown()
            },
        )

        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(2, attemptCount.get())

        queue.shutdown()
    }

    @Test
    fun `진짜 블로킹 호출이 timeout을 넘겨도 워커는 버려두고 같은 샤드의 다음 작업을 즉시 처리한다`() {
        // 적대적 리뷰 지적: withTimeout()은 협조적 취소만 되는 suspend 코드에만 효과가 있어, 만약
        // task.execute()가 워커 코루틴의 스레드에서 직접 도는 순수 블로킹 호출(멈춘 소켓 read 등)이면
        // 타임아웃이 지나도 그 스레드가 풀려나지 않아 같은 샤드의 다음 작업까지 전부 멈춰버린다.
        // kotlinx.coroutines.delay()는 협조적이라 이 버그를 재현하지 못하므로, 여기서는 CountDownLatch로
        // 진짜 블로킹을 흉내낸다.
        val queue = GateDbWriteQueue(shardCount = 1)
        val slowTaskStarted = CountDownLatch(1)
        val slowTaskReleased = CountDownLatch(1)
        val nextTaskDone = CountDownLatch(1)

        queue.enqueue(
            GateDbWriteTask(
                partitionKey = "192.168.0.7",
                operationName = "BLOCKING_SLOW",
                maxAttempts = 1,
                timeout = 100.milliseconds,
            ) {
                slowTaskStarted.countDown()
                slowTaskReleased.await(5, TimeUnit.SECONDS)
            },
        )
        assertTrue(slowTaskStarted.await(2, TimeUnit.SECONDS))

        queue.enqueue(
            GateDbWriteTask(partitionKey = "192.168.0.7", operationName = "NEXT", maxAttempts = 1) {
                nextTaskDone.countDown()
            },
        )

        try {
            // 전용 스레드풀 분리가 없었다면(예전 구현) NEXT는 슬로우 작업의 블로킹 호출이 실제로
            // 끝날 때까지(release 전까지) 시작조차 못 했을 것이다 — timeout(100ms) 직후 워커가
            // 다음 작업으로 넘어가야만 이 안에 끝난다.
            assertTrue(nextTaskDone.await(2, TimeUnit.SECONDS), "타임아웃 이후 워커가 다음 작업으로 넘어가지 못했습니다")
        } finally {
            slowTaskReleased.countDown() // 백그라운드에 버려둔 스레드를 정리한다.
            queue.shutdown()
        }
    }

    @Test
    fun `같은 파티션키의 작업은 제출한 순서대로 처리된다`() {
        val queue = GateDbWriteQueue(shardCount = 4)
        val executedOrder = ConcurrentLinkedQueue<Int>()
        val done = CountDownLatch(20)

        for (i in 1..20) {
            queue.enqueue(
                GateDbWriteTask(partitionKey = "192.168.0.5", operationName = "ORDERED-$i") {
                    executedOrder.add(i)
                    done.countDown()
                },
            )
        }

        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals((1..20).toList(), executedOrder.toList())

        queue.shutdown()
    }

    @Test
    fun `큐가 가득 차면 blocking 없이 드롭하고 카운터를 올린다`() {
        val queue = GateDbWriteQueue(shardCount = 1)
        val workerStarted = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)

        // 워커가 첫 작업을 dequeue해 붙잡아 두게 해서, 그 뒤로 채널 버퍼(capacity=1000)를 실제로 채울 수 있게 한다.
        queue.enqueue(
            GateDbWriteTask(partitionKey = "192.168.0.6", operationName = "BLOCKER") {
                workerStarted.countDown()
                releaseWorker.await(5, TimeUnit.SECONDS)
            },
        )
        assertTrue(workerStarted.await(2, TimeUnit.SECONDS))

        // 버퍼(1000개)를 가득 채운다 — 전부 즉시 성공(trySend)해야 한다.
        repeat(1000) { i ->
            queue.enqueue(GateDbWriteTask(partitionKey = "192.168.0.6", operationName = "FILL-$i") {})
        }
        assertEquals(0L, queue.droppedCount())

        // 버퍼가 가득 찬 상태에서의 추가 enqueue는 호출 스레드를 블로킹하지 않고 즉시 드롭되어야 한다
        // (trySendBlocking을 썼다면 이 호출이 releaseWorker가 풀릴 때까지 멈췄을 것이다).
        val enqueueThread = Thread {
            queue.enqueue(GateDbWriteTask(partitionKey = "192.168.0.6", operationName = "OVERFLOW") {})
        }
        enqueueThread.start()
        val finishedPromptly = try {
            enqueueThread.join(1000)
            !enqueueThread.isAlive
        } finally {
            releaseWorker.countDown()
        }

        assertTrue(finishedPromptly, "enqueue()가 큐가 가득 찬 상태에서도 즉시 반환되어야 한다(non-blocking)")
        assertEquals(1L, queue.droppedCount())

        queue.shutdown()
    }

    /**
     * 큐 드롭 durable 재작성(2026-08-12) 회귀 테스트 — 작업이 드롭되면(큐 포화)
     * [GateDbWriteTask.onDropOrFinalFailure]가 호출돼야 한다.
     */
    @Test
    fun `큐가 가득 차 드롭되면 onDropOrFinalFailure가 호출된다`() {
        val queue = GateDbWriteQueue(shardCount = 1)
        val workerStarted = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)
        val fallbackCalled = CountDownLatch(1)

        queue.enqueue(
            GateDbWriteTask(partitionKey = "192.168.0.8", operationName = "BLOCKER") {
                workerStarted.countDown()
                releaseWorker.await(5, TimeUnit.SECONDS)
            },
        )
        assertTrue(workerStarted.await(2, TimeUnit.SECONDS))

        repeat(1000) { i ->
            queue.enqueue(GateDbWriteTask(partitionKey = "192.168.0.8", operationName = "FILL-$i") {})
        }

        queue.enqueue(
            GateDbWriteTask(
                partitionKey = "192.168.0.8",
                operationName = "DROPPED",
                onDropOrFinalFailure = { fallbackCalled.countDown() },
            ) {},
        )

        try {
            assertTrue(fallbackCalled.await(2, TimeUnit.SECONDS), "드롭된 작업의 onDropOrFinalFailure가 호출되지 않았습니다")
        } finally {
            releaseWorker.countDown()
            queue.shutdown()
        }
    }

    /**
     * 큐 드롭 durable 재작성(2026-08-12) 회귀 테스트 — maxAttempts를 모두 소진해 최종 실패해도
     * [GateDbWriteTask.onDropOrFinalFailure]가 호출돼야 한다.
     */
    @Test
    fun `재시도를 모두 소진해 최종 실패하면 onDropOrFinalFailure가 호출된다`() {
        val queue = GateDbWriteQueue(shardCount = 1)
        val fallbackCalled = CountDownLatch(1)

        queue.enqueue(
            GateDbWriteTask(
                partitionKey = "192.168.0.9",
                operationName = "ALWAYS_FAIL_WITH_FALLBACK",
                maxAttempts = 2,
                timeout = 1.seconds,
                onDropOrFinalFailure = { fallbackCalled.countDown() },
            ) {
                throw RuntimeException("항상 실패")
            },
        )

        assertTrue(fallbackCalled.await(5, TimeUnit.SECONDS), "최종 실패한 작업의 onDropOrFinalFailure가 호출되지 않았습니다")

        queue.shutdown()
    }

    /**
     * 샤드 워커 격리 회귀 테스트(2026-08-25 소스 전수 검토 지적) — [GateDbWriteQueue.runShardWorker]의
     * `catch (ex: Exception)`은 `task.execute()`가 흘린 일반 예외를 전부 삼켜 재시도하지만,
     * `Exception`이 아닌 `Throwable`(`Error` — OOM 등)은 그 catch를 통과해 워커 밖으로 새어
     * 나간다(클래스 KDoc "샤드 워커 간 격리" 참고). 예전에는 이런 `Error`가 샤드 워커들을 감싸던
     * 평범한(non-supervisor) `workerJob`을 실패시켜, 정상 가동 중인 **다른 샤드**까지 함께
     * 취소시켰다 — 각 샤드 워커를 [scope](SupervisorJob 보유)의 직접 자식으로 걸어 고쳤다. 한
     * 샤드가 `Error`로 죽어도 다른 샤드는 계속 새 작업을 처리해야 한다.
     *
     * (`CancellationException`이 아니라 `Error`로 재현하는 이유: Kotlin 구조적 동시성에서 자식
     * 코루틴이 `CancellationException`으로 완료되는 것은 "그 자식이 정상적으로 취소됨"으로
     * 취급되어 부모/형제에게 실패로 전파되지 않는다 — 실제로 이 테스트를 처음
     * `CancellationException`으로 작성했을 때는 수정 전 코드에서도 통과해버려, 이 경로가 실제
     * 위험이 아님을 먼저 확인했다. 진짜 위험은 `catch (ex: Exception)`이 잡지 못하는 `Error`뿐이다.)
     */
    @Test
    fun `한 샤드의 작업이 Error를 흘려도 다른 샤드는 계속 처리한다`() {
        // GateDbWriteQueue.shardIndexOf와 동일한 해시 규칙으로, shardCount=2에서 서로 다른
        // 샤드(0과 1)로 떨어지는 파티션키 두 개를 찾는다.
        fun shardOf(key: String) = (key.hashCode() and Int.MAX_VALUE) % 2
        val killedShardKey = generateSequence(0) { it + 1 }.map { "dead-shard-$it" }.first { shardOf(it) == 0 }
        val survivingShardKey = generateSequence(0) { it + 1 }.map { "alive-shard-$it" }.first { shardOf(it) == 1 }

        val queue = GateDbWriteQueue(shardCount = 2)
        val survivorDone = CountDownLatch(1)

        // 먼저 한 샤드의 워커를 Error로 죽인다 — catch (ex: Exception)이 잡지 못하는 Throwable이
        // task.execute() 밖으로 새어 나가는 상황을 재현한다.
        queue.enqueue(
            GateDbWriteTask(partitionKey = killedShardKey, operationName = "SPURIOUS_ERROR") {
                throw OutOfMemoryError("샤드 격리 재현용 — 실제 OOM이 아니라 의도적으로 던진 것")
            },
        )
        Thread.sleep(200) // 위 작업이 처리되어 해당 샤드 워커가 죽을 시간을 준다.

        // 죽은 샤드와 무관한 다른 샤드의 작업은 여전히 정상 처리돼야 한다.
        queue.enqueue(
            GateDbWriteTask(partitionKey = survivingShardKey, operationName = "SHOULD_STILL_RUN") {
                survivorDone.countDown()
            },
        )

        try {
            assertTrue(
                survivorDone.await(3, TimeUnit.SECONDS),
                "다른 샤드의 작업이 처리되지 않았습니다 — 한 샤드의 Error가 전체 워커를 죽였을 가능성이 있습니다",
            )
        } finally {
            queue.shutdown()
        }
    }
}
