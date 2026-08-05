package kr.co.securance.secuhub.server.db

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong

/** 파티셔닝 큐에 넣을 작업 하나. [partitionKey](디바이스 IP)로 같은 디바이스의 쓰기 순서를 보장한다. */
data class GateDbWriteTask(
    val partitionKey: String,
    val operationName: String,
    val maxAttempts: Int = 3,
    val execute: suspend () -> Unit,
)

/**
 * 디바이스 IP로 파티셔닝된 비동기 DB 쓰기 큐(계획서 3.5/3.7절).
 *
 * 레거시 `DatabaseWriterService`(8-shard 큐, 샤드별 워커 1개)의 Kotlin 이식이다.
 * `partitionKey.hashCode() % shardCount`로 샤드를 고정해 **같은 디바이스의 쓰기는 항상 같은
 * 워커가 순서대로 처리**하고, 서로 다른 디바이스 간에는 순서를 강제하지 않아 병렬성을 확보한다.
 * 실패한 작업은 큐 뒤로 다시 넣지 않고(순서 보장을 위해) 그 자리에서 지수 백오프 없이 재시도한다.
 */
class GateDbWriteQueue(
    private val shardCount: Int,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val logger = LoggerFactory.getLogger(GateDbWriteQueue::class.java)

    private val shards: List<Channel<GateDbWriteTask>> = List(shardCount) { Channel(capacity = 1000) }
    private val droppedCount = AtomicLong(0)

    private val workerJob: Job = scope.launch {
        shards.forEachIndexed { index, shard ->
            launch { runShardWorker(index, shard) }
        }
    }

    /** 작업을 파티션 큐에 넣는다. 큐가 가득 차면 드롭하고 카운터만 올린다(계획서 3.5절 방어적 설계). */
    fun enqueue(task: GateDbWriteTask) {
        val shardIndex = shardIndexOf(task.partitionKey)
        val result = shards[shardIndex].trySendBlocking(task)
        if (result.isFailure) {
            droppedCount.incrementAndGet()
            logger.error("DB 쓰기 큐가 가득 차 작업을 드롭했습니다: shard={}, op={}", shardIndex, task.operationName)
        }
    }

    fun droppedCount(): Long = droppedCount.get()

    fun shutdown() {
        shards.forEach { it.close() }
        workerJob.cancel()
    }

    private suspend fun runShardWorker(shardIndex: Int, shard: Channel<GateDbWriteTask>) {
        for (task in shard) {
            var attempt = 0
            var succeeded = false
            while (!succeeded && attempt < task.maxAttempts) {
                attempt++
                try {
                    task.execute()
                    succeeded = true
                } catch (ex: Exception) {
                    logger.warn(
                        "DB 쓰기 실패(shard={}, op={}, attempt={}/{})",
                        shardIndex, task.operationName, attempt, task.maxAttempts, ex,
                    )
                }
            }
            if (!succeeded) {
                logger.error("DB 쓰기 최종 실패: shard={}, op={}", shardIndex, task.operationName)
            }
        }
    }

    private fun shardIndexOf(partitionKey: String): Int =
        (partitionKey.hashCode() and Int.MAX_VALUE) % shardCount
}
