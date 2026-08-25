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

    /**
     * 같은 샤드 생존 회귀 테스트(Codex 리뷰 지적, 2026-08-25 후속) — 위 테스트는 "다른" 샤드가
     * 살아남는지만 확인했는데, 그것만으로는 부족했다. [GateDbWriteQueue.runShardWorker]가
     * `Error`를 그대로 밖으로 흘리면 형제 샤드는 [scope](SupervisorJob)로 격리돼 살아남지만,
     * 정작 그 `Error`를 던진 **자기 자신의 샤드 워커 코루틴**은 영구 종료된다 — 이후 같은 샤드로
     * 라우팅되는 모든 작업(같은 디바이스뿐 아니라 해시가 같은 다른 디바이스도 포함)이 소비자
     * 없는 채널에 쌓이다 드롭된다.
     *
     * 이 테스트는 `OutOfMemoryError`([VirtualMachineError])로 재현하는데, 첫 수정
     * (`processTask`를 `catch (ex: Throwable)`로 감싸 워커를 살리는 방식)은 Codex 적대적 리뷰가
     * 지적했듯 진짜 JVM 치명적 오류까지 "이 작업만 실패했을 뿐"인 것처럼 삼키고 곧장 다음 작업을
     * 처리해 손상된 JVM 상태로 DB 쓰기를 계속 시도하는 위험이 있었다. 지금은
     * [GateDbWriteQueue.superviseShardWorker]가 `VirtualMachineError`를 잡아 **워커 코루틴 자체를
     * 재시작**하는 방식으로 바뀌었다 — 죽은 척하지 않고 실제로 죽은 뒤, `SHARD_WORKER_RESTART_DELAY`
     * (≈1초) 지연을 두고 새로 태어난 워커가 다음 작업부터 이어받는다. "같은 샤드가 살아남는다"는
     * 결과뿐 아니라 이 지연이 실제로 있었는지(=재시작 경로를 탔는지, 이전처럼 같은 코루틴이
     * catch 후 즉시 이어받은 게 아닌지)까지 최소 소요 시간으로 함께 고정한다 — 재시작 지연 없이
     * 즉시 처리되는 경우와의 구분은 아래 "VirtualMachineError가 아닌 Error는 워커를 재시작하지
     * 않고 즉시 처리한다" 테스트가 대조군 역할을 한다.
     */
    @Test
    fun `한 샤드에서 Error가 발생해도 같은 샤드가 재시작 지연 후 이후 작업을 계속 처리한다`() {
        val queue = GateDbWriteQueue(shardCount = 1)
        val afterErrorDone = CountDownLatch(1)

        // 이 샤드(유일한 샤드)의 워커를 VirtualMachineError로 죽이려 시도한다.
        val beforeError = System.nanoTime()
        queue.enqueue(
            GateDbWriteTask(partitionKey = "same-shard-error", operationName = "SPURIOUS_ERROR") {
                throw OutOfMemoryError("같은 샤드 생존 재현용 — 실제 OOM이 아니라 의도적으로 던진 것")
            },
        )
        Thread.sleep(200) // 위 작업이 처리되어 워커가 Error를 마주칠 시간을 준다.

        // 같은 샤드(같은 파티션키)로 뒤이어 들어온 정상 작업도 (재시작된 워커에 의해) 결국
        // 처리돼야 한다 — 워커가 영구 종료됐다면 이 작업은 채널에 그대로 쌓인 채 실행되지 않는다.
        queue.enqueue(
            GateDbWriteTask(partitionKey = "same-shard-error", operationName = "SHOULD_STILL_RUN_AFTER_ERROR") {
                afterErrorDone.countDown()
            },
        )

        try {
            assertTrue(
                afterErrorDone.await(3, TimeUnit.SECONDS),
                "Error 발생 이후 같은 샤드의 후속 작업이 처리되지 않았습니다 — 워커가 영구 종료됐을 가능성이 있습니다",
            )
            val elapsedMs = (System.nanoTime() - beforeError) / 1_000_000
            assertTrue(
                elapsedMs >= 900,
                "후속 작업이 재시작 지연(≈1초) 없이 ${elapsedMs}ms 만에 처리됐습니다 — VirtualMachineError를 " +
                    "재시작 없이 같은 코루틴에서 곧장 삼키고 있을 가능성이 있습니다(대안 강등)",
            )
        } finally {
            queue.shutdown()
        }
    }

    /**
     * `VirtualMachineError`가 아닌 그 외 `Throwable`(예: `AssertionError`)은 JVM 자체의 건전성과
     * 무관하므로, 워커를 재시작(`SHARD_WORKER_RESTART_DELAY`≈1초 지연)하지 않고 같은 코루틴이
     * 즉시 다음 작업을 처리해야 한다(클래스 KDoc "`VirtualMachineError`는 '작업 실패'로 삼키지
     * 않는다" 참고 — 재시작은 진짜 `VirtualMachineError`에만 쓰는 무거운 대응이다). 재시작 지연이
     * 있었다면 이 테스트는 500ms 안에 완료되지 못했을 것이다 — 두 Error 처리 경로가 실제로
     * 분기됨을 고정한다.
     */
    @Test
    fun `VirtualMachineError가 아닌 Error는 워커를 재시작하지 않고 즉시 처리한다`() {
        val queue = GateDbWriteQueue(shardCount = 1)
        val afterErrorDone = CountDownLatch(1)

        queue.enqueue(
            GateDbWriteTask(partitionKey = "non-fatal-error", operationName = "SPURIOUS_ASSERTION_ERROR") {
                throw AssertionError("VM 치명적이지 않은 Error 재현용")
            },
        )

        queue.enqueue(
            GateDbWriteTask(partitionKey = "non-fatal-error", operationName = "SHOULD_RUN_IMMEDIATELY") {
                afterErrorDone.countDown()
            },
        )

        try {
            assertTrue(
                afterErrorDone.await(500, TimeUnit.MILLISECONDS),
                "VirtualMachineError가 아닌 Error 이후 후속 작업이 500ms 안에 처리되지 않았습니다 — " +
                    "불필요하게 워커가 재시작(지연)되고 있을 가능성이 있습니다",
            )
        } finally {
            queue.shutdown()
        }
    }

    /**
     * `ThreadDeath`는 JVM 명세상 "잡았다면 반드시 다시 던져야 하는" 유일한 예외라, 그 예외를 잡은
     * 코루틴 자신이 "이 작업만 최종 실패로 처리하고 계속 동작"할 수는 없다 — 그건 종료 요청을
     * 삼키는 셈이기 때문이다. 하지만 그렇다고 그 샤드의 채널 소비자가 영구히 사라져도 되는 것은
     * 아니다(Codex 적대적 리뷰 지적, 2026-08-25 — "명세는 지켰지만 그 샤드의 DB 쓰기가 프로세스
     * 재기동 전까지 조용히 멈춘다"는 문제). 그래서 [GateDbWriteQueue.superviseShardWorker]는
     * `ThreadDeath`를 만나면 자기 자신은 다시 던져 종료하되, 그 전에 후임 코루틴을 먼저 띄워 채널
     * 소비를 이어받게 한다. 이 테스트는 그 후속 작업이 실제로(그것도 `VirtualMachineError`의
     * 재시작 지연 없이 신속하게) 처리된다는 것으로 "채널 소비자가 끊기지 않는다"를 고정한다 — 만약
     * `superviseShardWorker`가 다시 `ThreadDeath`를 그냥 통과시키기만 하고 후임을 띄우지 않는
     * 회귀가 생기면, 후속 작업은 채널에 소비자 없이 쌓인 채 처리되지 않아 이 테스트가 실패한다.
     */
    @Test
    fun `ThreadDeath 발생 후에도 같은 샤드가 후임 코루틴으로 이어받아 이후 작업을 계속 처리한다`() {
        val queue = GateDbWriteQueue(shardCount = 1)
        val afterDeathDone = CountDownLatch(1)

        @Suppress("DEPRECATION")
        queue.enqueue(
            GateDbWriteTask(partitionKey = "thread-death", operationName = "SPURIOUS_THREAD_DEATH") {
                throw ThreadDeath()
            },
        )
        Thread.sleep(200) // 위 작업이 처리되어 워커가 ThreadDeath를 마주칠 시간을 준다.

        queue.enqueue(
            GateDbWriteTask(partitionKey = "thread-death", operationName = "SHOULD_STILL_RUN_AFTER_THREAD_DEATH") {
                afterDeathDone.countDown()
            },
        )

        try {
            assertTrue(
                afterDeathDone.await(2, TimeUnit.SECONDS),
                "ThreadDeath 발생 이후 같은 샤드의 후속 작업이 처리되지 않았습니다 — ThreadDeath를 재전파만 " +
                    "하고 채널 소비자를 이어받을 후임 코루틴을 띄우지 않았을 가능성이 있습니다",
            )
        } finally {
            queue.shutdown()
        }
    }

    /**
     * 위 테스트가 "한 번"의 `ThreadDeath` 이후 복구를 확인했다면, 이 테스트는 그 복구가 일회성이
     * 아니라 반복돼도 매번 새 후임이 이어받는지를 검증한다 — `superviseShardWorker`가 `ThreadDeath`를
     * 잡을 때마다 새 코루틴을 띄우고 [GateDbWriteQueue]의 활성 워커 슬롯을 그 후임으로 교체하므로,
     * 두 번째 `ThreadDeath`도 (그 시점의 후임이었던) 워커에서 동일한 경로로 처리돼야 한다.
     */
    @Test
    fun `ThreadDeath가 반복 발생해도 매번 새 후임이 이어받아 계속 처리한다`() {
        val queue = GateDbWriteQueue(shardCount = 1)
        val afterSecondDeathDone = CountDownLatch(1)

        @Suppress("DEPRECATION")
        queue.enqueue(
            GateDbWriteTask(partitionKey = "thread-death-repeat", operationName = "SPURIOUS_THREAD_DEATH_1") {
                throw ThreadDeath()
            },
        )
        Thread.sleep(200)

        @Suppress("DEPRECATION")
        queue.enqueue(
            GateDbWriteTask(partitionKey = "thread-death-repeat", operationName = "SPURIOUS_THREAD_DEATH_2") {
                throw ThreadDeath()
            },
        )
        Thread.sleep(200)

        queue.enqueue(
            GateDbWriteTask(partitionKey = "thread-death-repeat", operationName = "SHOULD_STILL_RUN_AFTER_REPEATED_THREAD_DEATH") {
                afterSecondDeathDone.countDown()
            },
        )

        try {
            assertTrue(
                afterSecondDeathDone.await(2, TimeUnit.SECONDS),
                "ThreadDeath가 두 번 반복된 뒤 같은 샤드의 후속 작업이 처리되지 않았습니다 — 후임 코루틴 " +
                    "체인이 두 번째 이후로는 끊겼을 가능성이 있습니다",
            )
        } finally {
            queue.shutdown()
        }
    }

    /**
     * VirtualMachineError/ThreadDeath fallback 회귀 테스트(Opus 재검토 지적, 2026-08-25 칠후속) —
     * [GateDbWriteQueue.runShardWorker]가 `VirtualMachineError`/`ThreadDeath`를 재전파하기 전
     * `runFallback`을 호출하지 않던 예전 구현에서는, 이 작업의 `onDropOrFinalFailure`(예:
     * `GatePacketPersister.enqueueReceiveInsert`의 `CompletableDeferred.complete(null)`)가 전혀
     * 실행되지 않아 그 완료를 기다리는 다운스트림이 자체 타임아웃(수 초~수십 초)만큼 스톨했다.
     * 이 테스트는 `onDropOrFinalFailure`가 (다음 작업을 기다릴 필요 없이) 즉시 호출되는지로 그
     * 회귀를 고정한다.
     */
    @Test
    fun `VirtualMachineError로 워커가 재시작될 때도 onDropOrFinalFailure가 즉시 호출된다`() {
        val queue = GateDbWriteQueue(shardCount = 1)
        val fallbackCalled = CountDownLatch(1)

        queue.enqueue(
            GateDbWriteTask(
                partitionKey = "vme-fallback",
                operationName = "SPURIOUS_OOM_WITH_FALLBACK",
                onDropOrFinalFailure = { fallbackCalled.countDown() },
            ) {
                throw OutOfMemoryError("VME fallback 재현용 — 실제 OOM이 아니라 의도적으로 던진 것")
            },
        )

        try {
            assertTrue(
                fallbackCalled.await(2, TimeUnit.SECONDS),
                "VirtualMachineError로 작업이 유실됐는데도 onDropOrFinalFailure가 호출되지 않았습니다 — " +
                    "다운스트림(rcv_id Deferred 등)이 타임아웃까지 스톨할 수 있습니다",
            )
        } finally {
            queue.shutdown()
        }
    }

    /** 위와 동일한 회귀를 `ThreadDeath` 경로에 대해서도 고정한다. */
    @Test
    fun `ThreadDeath로 워커가 종료될 때도 onDropOrFinalFailure가 즉시 호출된다`() {
        val queue = GateDbWriteQueue(shardCount = 1)
        val fallbackCalled = CountDownLatch(1)

        @Suppress("DEPRECATION")
        queue.enqueue(
            GateDbWriteTask(
                partitionKey = "threaddeath-fallback",
                operationName = "SPURIOUS_THREAD_DEATH_WITH_FALLBACK",
                onDropOrFinalFailure = { fallbackCalled.countDown() },
            ) {
                throw ThreadDeath()
            },
        )

        try {
            assertTrue(
                fallbackCalled.await(2, TimeUnit.SECONDS),
                "ThreadDeath로 작업이 유실됐는데도 onDropOrFinalFailure가 호출되지 않았습니다 — " +
                    "다운스트림(rcv_id Deferred 등)이 타임아웃까지 스톨할 수 있습니다",
            )
        } finally {
            queue.shutdown()
        }
    }

    /**
     * `task.execute()`가 직접 던진 `CancellationException` 회귀 테스트(Opus 재검토 지적,
     * 2026-08-25 칠후속) — `task.execute()`는 호출부가 채우는 임의의 코드라, 진짜 취소 신호 없이도
     * `CancellationException`을 스스로 던질 수 있다(예: 내부에서 이미 취소된 다른 `Deferred`를
     * `await()`하는 경우). 이런 `CancellationException`은 `scope.async`의 자식 코루틴이 "정상
     * 취소"로 완료된 것으로 구조적 동시성상 부모에게 실패로 전파되지 않으므로, 워커 코루틴
     * 자신은 여전히 active다 — 그런데도 타입만 보고 무조건 재전파하면 이 샤드의 워커가 후임 없이
     * 영구 종료된다. `currentCoroutineContext().ensureActive()`로 재확인해, 진짜 취소가 아니면
     * 이번 시도만 실패로 간주하고 재시도해야 한다.
     */
    @Test
    fun `task가 직접 던진 CancellationException은 진짜 취소로 오인하지 않고 재시도한다`() {
        val queue = GateDbWriteQueue(shardCount = 1)
        val attemptCount = AtomicInteger(0)
        val done = CountDownLatch(1)

        queue.enqueue(
            GateDbWriteTask(
                partitionKey = "fake-cancellation",
                operationName = "SPURIOUS_CANCELLATION",
                maxAttempts = 3,
                timeout = 1.seconds,
            ) {
                val attempt = attemptCount.incrementAndGet()
                if (attempt < 2) {
                    throw kotlinx.coroutines.CancellationException("진짜 취소가 아니라 task.execute()가 직접 던진 것")
                }
                done.countDown()
            },
        )

        try {
            assertTrue(
                done.await(5, TimeUnit.SECONDS),
                "task.execute()가 직접 던진 CancellationException을 진짜 취소로 오인해 재시도하지 않았습니다",
            )
            assertEquals(2, attemptCount.get())
        } finally {
            queue.shutdown()
        }
    }

    /**
     * 위 테스트가 "재시도"만 확인했다면, 이 테스트는 그 결과로 샤드 워커 자신이 후임 없이 영구
     * 종료되지 않는지(=채널 소비자가 살아있는지)까지 확인한다 — `ensureActive()` 재확인이 없다면
     * `maxAttempts`를 다 태우기도 전에 워커 코루틴 자체가 죽어, 같은 샤드로 들어온 이후 작업이
     * 전혀 처리되지 않는다.
     */
    @Test
    fun `task가 직접 던진 CancellationException 이후에도 같은 샤드가 후속 작업을 계속 처리한다`() {
        val queue = GateDbWriteQueue(shardCount = 1)
        val afterDone = CountDownLatch(1)

        queue.enqueue(
            GateDbWriteTask(
                partitionKey = "fake-cancellation-survival",
                operationName = "SPURIOUS_CANCELLATION_ALWAYS",
                maxAttempts = 1,
                timeout = 1.seconds,
            ) {
                throw kotlinx.coroutines.CancellationException("진짜 취소가 아니라 task.execute()가 직접 던진 것")
            },
        )
        Thread.sleep(200)

        queue.enqueue(
            GateDbWriteTask(partitionKey = "fake-cancellation-survival", operationName = "SHOULD_STILL_RUN") {
                afterDone.countDown()
            },
        )

        try {
            assertTrue(
                afterDone.await(2, TimeUnit.SECONDS),
                "task.execute()가 던진 CancellationException 이후 같은 샤드의 후속 작업이 처리되지 " +
                    "않았습니다 — 진짜 취소로 오인해 워커가 후임 없이 영구 종료됐을 가능성이 있습니다",
            )
        } finally {
            queue.shutdown()
        }
    }
}
