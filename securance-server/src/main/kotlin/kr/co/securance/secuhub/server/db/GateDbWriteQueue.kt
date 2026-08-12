package kr.co.securance.secuhub.server.db

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** 파티셔닝 큐에 넣을 작업 하나. [partitionKey](디바이스 IP)로 같은 디바이스의 쓰기 순서를 보장한다. */
data class GateDbWriteTask(
    val partitionKey: String,
    val operationName: String,
    val maxAttempts: Int = 3,
    /** 한 번의 [execute] 시도에 허용하는 최대 시간. 초과하면 실패로 간주하고 재시도(또는 포기)한다. */
    val timeout: Duration = 5.seconds,
    /**
     * 이 작업이 큐 포화로 드롭되거나(enqueue 시점) maxAttempts를 모두 소진하고도 끝내 실패했을 때
     * 호출되는 durable 대체 경로(2026-08-12, 큐 드롭 durable 재작성). 이 손실을 감내할 수 없는
     * 호출부(예: `OprStatusPersister`의 통행량 집계)만 채워 넣는다 — 지정하지 않으면 예전과 동일하게
     * 로그+카운터만 남기고 그대로 유실된다.
     *
     * [execute]와 마찬가지로 [blockingDispatcher]에서 실행되므로(드롭 시점엔 [enqueue] 호출 스레드,
     * 최종 실패 시점엔 샤드 워커 코루틴을 막지 않는다), 이 콜백 안에서 블로킹 DB 호출을 해도 안전하다.
     * 이 콜백 자체가 던지는 예외는 로그만 남기고 삼킨다 — durable 저장조차 실패하는 경우(예: DB
     * 완전 다운)는 원본 쓰기도 어차피 실패했을 상황이라 이중 장애이며, 더 할 수 있는 게 없다.
     */
    val onDropOrFinalFailure: (suspend () -> Unit)? = null,
    // 기존 호출부 전부가 `GateDbWriteTask(...) { ... }` 트레일링 람다 문법으로 execute를 채우므로,
    // execute는 반드시 마지막 파라미터여야 한다(트레일링 람다는 항상 마지막 파라미터에 대응된다).
    val execute: suspend () -> Unit,
)

/**
 * 디바이스 IP로 파티셔닝된 비동기 DB 쓰기 큐(계획서 3.5/3.7절).
 *
 * 레거시 `DatabaseWriterService`(8-shard 큐, 샤드별 워커 1개)의 Kotlin 이식이다.
 * `partitionKey.hashCode() % shardCount`로 샤드를 고정해 **같은 디바이스의 쓰기는 항상 같은
 * 워커가 순서대로 처리**하고, 서로 다른 디바이스 간에는 순서를 강제하지 않아 병렬성을 확보한다.
 * 실패한 작업은 큐 뒤로 다시 넣지 않고(순서 보장을 위해) 그 자리에서 지수 백오프를 두고 재시도한다.
 * 각 시도는 [GateDbWriteTask.timeout]으로 제한된다.
 *
 * **블로킹 JDBC 호출과 타임아웃(적대적 리뷰에서 지적, 전용 스레드풀로 해결)**: [kotlinx.coroutines.withTimeout]은
 * 취소에 협조하는 suspend 코드에만 효과가 있다 — [GateDbWriteTask.execute]가 호출하는 JPA/JDBC는
 * 순수 블로킹이라, 만약 샤드 워커 코루틴이 직접 그 스레드에서 실행한다면 소켓 read에 멈춰버린 순간
 * 취소 신호가 와도 실제 응답(또는 소켓 예외)이 올 때까지 그 워커는 풀려나지 않아 `timeout`이
 * 사실상 무의미해진다(같은 샤드의 다른 작업들까지 전부 멈춘다). 이를 막기 위해 [GateDbWriteTask.execute]는
 * 항상 전용 [blockingExecutor] 스레드풀에서 실행하고, 샤드 워커는 그 결과를 `Deferred.await()`로만
 * 기다린다 — `await()`는 완전히 협조적인 suspend 지점이라 [withTimeout]이 확실하게 취소할 수 있다.
 * 타임아웃이 지나면 워커는 블로킹 호출이 실제로 끝나기를 기다리지 않고 즉시 다음 시도/작업으로
 * 넘어가며, 버려진 스레드는 백그라운드에서 계속 돌다가 결국 **JDBC 드라이버의 소켓 타임아웃**
 * (`spring.datasource.hikari.data-source-properties.socketTimeout` — `securance-app/src/main/resources/application.yml`
 * 참고)에 의해 강제 종료된다 — 그 드라이버 레벨 타임아웃이 진짜 백스톱이고, 이게 없으면 [blockingExecutor]도
 * 언젠가 고갈될 수 있으므로 두 설정은 반드시 함께 있어야 한다.
 *
 * **주의(멱등성)**: 위 구조상 타임아웃 판정 이후 버려둔 호출이 뒤늦게 실제로 성공할 수 있고, 그 시점에는
 * 이미 재시도로 같은 작업이 한 번 더 실행됐을 수 있다 — 즉 같은 쓰기가 두 번 적용될 가능성이 있다.
 * 그래서 [GateDbWriteTask.execute]는 **항상 멱등 연산(예: PK로 조회 후 없으면 생성해 저장하는
 * find-or-create + save, 즉 UPSERT)으로 작성해야 한다** — 현재 유일한 호출부인
 * `GateConnectionRegistryImpl.enqueueNetStateUpdate`는 이미 이 패턴을 따른다(같은 작업이 중복
 * 실행돼도 같은 복합키 레코드의 상태/시각만 재기록될 뿐 중복 행이나 불일치가 생기지 않는다).
 * 새로 [GateDbWriteTask]를 넣는 호출부도 이 규칙을 반드시 지켜야 한다.
 *
 * **주의(순서 역전, 적대적 리뷰(codex) 지적)**: 멱등성만으로는 부족하다 — 버려둔 오래된 시도가 뒤늦게
 * 완료될 때, 같은 파티션키에 대해 그 사이 들어온 더 최신 작업(예: OFFLINE 다음의 ONLINE)이 이미
 * 반영된 값을 오래된 값으로 덮어쓸 수 있다("느린 오래된 쓰기가 빠른 최신 쓰기를 역전"). 파티션(샤드)
 * 안에서 실행 "순서"만 보장될 뿐, 실행 "완료" 순서까지는 이 큐가 보장하지 않기 때문이다. 따라서
 * 서로 다른 시점의 값이 같은 레코드를 두고 경쟁할 수 있는 호출부는 [GateDbWriteTask.execute] 안에서
 * 실제 쓰기 직전에 자체적으로 "내 이벤트가 여전히 최신인가"를 검증해야 한다 —
 * `GateConnectionRegistryImpl`이 호출 시점(큐 진입 전)에 발급한 단조증가 시퀀스를 저장 직전에
 * CAS로 재확인하는 방식(`tryClaimNetStateSeq`)이 그 예다.
 *
 * **드롭/최종 실패 시 durable 대체 저장(2026-08-12, 큐 드롭 durable 재작성)**: [GateDbWriteTask.onDropOrFinalFailure]를
 * 지정한 작업은 드롭되거나 재시도를 모두 소진해도 로그+카운터만 남기고 끝나지 않고, 그 콜백이
 * (보통 별도 테이블에 최소한의 원시값을 동기 저장하는 형태로) 실행된다. 지정하지 않은 작업은
 * 예전과 동일하게 그대로 유실된다 — 기존 호출부(`GateLogService`, `GatePacketPersister`,
 * `GateConnectionRegistryImpl`, `DirectGateControlService`)는 대부분 자체적으로 재수신/재조회 시
 * 자연 복구되거나(멱등 UPSERT, net_state는 다음 상태 패킷이 갱신) 손실 허용 범위로 판단해 그대로
 * 두었고, `OprStatusPersister`(통행량 집계, codex 적대적 리뷰 지적)만 이 콜백을 채워 넣는다.
 */
class GateDbWriteQueue(
    private val shardCount: Int,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /**
     * [GateDbWriteTask.execute]의 블로킹 호출 전용 스레드풀 크기. 샤드 수보다 넉넉히 잡아둬야,
     * 몇 개가 타임아웃 이후에도 소켓 타임아웃을 기다리며 "버려진" 채로 남아 있는 동안에도
     * 나머지 샤드의 정상적인 시도가 스레드 부족으로 큐잉되지 않는다.
     */
    blockingPoolSize: Int = shardCount * 4,
) {
    private val logger = LoggerFactory.getLogger(GateDbWriteQueue::class.java)

    private val shards: List<Channel<GateDbWriteTask>> = List(shardCount) { Channel(capacity = 1000) }
    private val droppedCount = AtomicLong(0)

    /**
     * maxAttempts를 모두 소진하고도 끝내 실패한 작업 수(적대적 리뷰 지적) — 예전에는 로그 한 줄만
     * 남기고 카운터가 전혀 없어, 이런 작업이 얼마나 쌓이는지 프로그램적으로 확인할 방법이 없었다.
     * [droppedCount]와 마찬가지로 지금은 이 값을 노출만 하고 별도 알림/메트릭 연동은 하지 않는다 —
     * Actuator/Micrometer 도입은 별도 의사결정이 필요한 아키텍처 변경이라 범위 밖으로 남겨둔다.
     */
    private val finalFailureCount = AtomicLong(0)

    private val blockingThreadCounter = AtomicInteger(0)
    private val blockingExecutor = Executors.newFixedThreadPool(blockingPoolSize) { runnable ->
        Thread(runnable, "gate-db-write-blocking-${blockingThreadCounter.incrementAndGet()}").apply { isDaemon = true }
    }
    private val blockingDispatcher = blockingExecutor.asCoroutineDispatcher()

    private val workerJob: Job = scope.launch {
        shards.forEachIndexed { index, shard ->
            launch { runShardWorker(index, shard) }
        }
    }

    /** 작업을 파티션 큐에 넣는다. 큐가 가득 차면 드롭하고 카운터만 올린다(계획서 3.5절 방어적 설계). */
    fun enqueue(task: GateDbWriteTask) {
        val shardIndex = shardIndexOf(task.partitionKey)
        // trySend는 큐가 가득 찼을 때 호출 스레드를 블로킹하지 않고 즉시 실패를 반환한다.
        // (trySendBlocking을 쓰면 슬롯이 빌 때까지 호출 스레드가 그대로 멈춰버려,
        //  공유 액터 스레드풀 전체가 DB 지연에 의해 고갈될 수 있다.)
        val result = shards[shardIndex].trySend(task)
        if (result.isFailure) {
            droppedCount.incrementAndGet()
            logger.error("DB 쓰기 큐가 가득 차 작업을 드롭했습니다: shard={}, op={}", shardIndex, task.operationName)
            runFallback(task, shardIndex)
        }
    }

    fun droppedCount(): Long = droppedCount.get()

    /** maxAttempts를 모두 소진하고도 끝내 실패한 작업의 누적 건수. */
    fun finalFailureCount(): Long = finalFailureCount.get()

    fun shutdown() {
        shards.forEach { it.close() }
        workerJob.cancel()
        // graceful shutdown: 이미 blockingExecutor에서 돌고 있는 블로킹 호출을 강제 인터럽트하지 않는다
        // (표준 Socket.read()는 인터럽트에 반응하지 않으므로 효과도 없다) — JDBC 소켓 타임아웃이
        // 각 스레드를 알아서 정리해줄 때까지 기다리게 둔다.
        blockingExecutor.shutdown()
    }

    private suspend fun runShardWorker(shardIndex: Int, shard: Channel<GateDbWriteTask>) {
        for (task in shard) {
            var attempt = 0
            var succeeded = false
            while (!succeeded && attempt < task.maxAttempts) {
                attempt++
                // 블로킹 호출을 전용 풀에 위임한다. withTimeout이 실제로 감싸는 건 execution.await()라는
                // 완전히 협조적인 suspend 지점뿐이므로, 타임아웃이 지나면 이 execution의 스레드는
                // 백그라운드에 버려둔 채 워커 코루틴은 즉시 다음 시도/작업으로 넘어갈 수 있다.
                val execution: Deferred<Unit> = scope.async(blockingDispatcher) { task.execute() }
                try {
                    withTimeout(task.timeout) { execution.await() }
                    succeeded = true
                } catch (ex: TimeoutCancellationException) {
                    // withTimeout()이 자체적으로 던지는 취소 예외 — 우리가 건 타임아웃이므로 작업
                    // 실패로 간주하고 재시도한다(아래 일반 CancellationException과는 구분해야 함).
                    logger.warn(
                        "DB 쓰기 타임아웃(shard={}, op={}, attempt={}/{}, timeout={}) - 버려둔 블로킹 호출은 " +
                            "백그라운드에서 계속 진행되며 JDBC 소켓 타임아웃이 최종적으로 정리합니다.",
                        shardIndex, task.operationName, attempt, task.maxAttempts, task.timeout,
                    )
                    logLateCompletion(execution, shardIndex, task)
                } catch (ex: CancellationException) {
                    // shutdown()/workerJob.cancel()로 인한 진짜 취소 — 작업 실패로 삼켜 재시도하면
                    // 코루틴이 취소된 상태로 계속 루프를 도는 구조적 동시성 위반이 된다. 그대로 전파한다.
                    throw ex
                } catch (ex: Exception) {
                    logger.warn(
                        "DB 쓰기 실패(shard={}, op={}, attempt={}/{})",
                        shardIndex, task.operationName, attempt, task.maxAttempts, ex,
                    )
                }
                // 재시도 전에는 지수 백오프로 잠깐 쉰다 — DB 장애 시 즉시 3연타로 부하를 더하지 않기 위함.
                // (마지막 시도 실패 후에는 대기하지 않고 바로 최종 실패 처리로 넘어간다.)
                if (!succeeded && attempt < task.maxAttempts) {
                    delay(backoffDelayFor(attempt))
                }
            }
            if (!succeeded) {
                finalFailureCount.incrementAndGet()
                logger.error("DB 쓰기 최종 실패: shard={}, op={}", shardIndex, task.operationName)
                runFallback(task, shardIndex)
            }
        }
    }

    /**
     * [GateDbWriteTask.onDropOrFinalFailure]가 지정돼 있으면 [blockingDispatcher]에서 실행한다.
     * 드롭 경로([enqueue])에서 호출될 때는 호출 스레드(Netty 이벤트루프 등)를, 최종 실패 경로
     * ([runShardWorker])에서 호출될 때는 샤드 워커 코루틴을 막지 않기 위해 항상 별도 launch로
     * 던지고 완료를 기다리지 않는다 — 어느 쪽도 이 fallback 저장 때문에 지연되어서는 안 된다.
     */
    private fun runFallback(task: GateDbWriteTask, shardIndex: Int) {
        val fallback = task.onDropOrFinalFailure ?: return
        scope.launch(blockingDispatcher) {
            try {
                fallback()
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                logger.error(
                    "durable 대체 저장(onDropOrFinalFailure)마저 실패했습니다 — 이 작업은 완전히 유실됩니다: shard={}, op={}",
                    shardIndex, task.operationName, ex,
                )
            }
        }
    }

    /**
     * 타임아웃 판정 이후 백그라운드에 버려둔 [execution]이 뒤늦게 완료됐을 때(성공/실패 모두) 로그만
     * 남긴다 — 이미 재시도가 별도로 실행됐을 수 있으므로(멱등성 주의, 클래스 KDoc 참고) 관측 목적일 뿐
     * 별도 처리는 하지 않는다.
     */
    private fun logLateCompletion(execution: Deferred<Unit>, shardIndex: Int, task: GateDbWriteTask) {
        execution.invokeOnCompletion { cause ->
            when (cause) {
                null -> logger.warn(
                    "DB 쓰기가 타임아웃 판정 이후 뒤늦게 성공했습니다(shard={}, op={}) - 재시도와 중복 실행되었을 수 있습니다.",
                    shardIndex, task.operationName,
                )
                is CancellationException -> Unit // scope 종료 등으로 인한 정상 취소는 로그가 필요 없다.
                else -> logger.warn(
                    "DB 쓰기가 타임아웃 판정 이후 뒤늦게 실패로 끝났습니다(shard={}, op={})",
                    shardIndex, task.operationName, cause,
                )
            }
        }
    }

    /** attempt(1부터 시작)에 대한 지수 백오프 지연: 200ms, 400ms, 800ms, ... 최대 2초. */
    private fun backoffDelayFor(attempt: Int): Duration =
        min(200L * (1L shl (attempt - 1)), 2_000L).milliseconds

    private fun shardIndexOf(partitionKey: String): Int =
        (partitionKey.hashCode() and Int.MAX_VALUE) % shardCount
}
