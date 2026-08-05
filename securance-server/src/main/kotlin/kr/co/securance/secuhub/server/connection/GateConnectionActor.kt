package kr.co.securance.secuhub.server.connection

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kr.co.securance.secuhub.common.exception.GateTaskRejectedException
import org.slf4j.LoggerFactory

/**
 * 커넥션 1개(디바이스 IP 1개)를 위한 직렬 처리 액터(계획서 3.3절).
 *
 * 레거시 `ClsAsyncObj.EnqueueProcessing`(모든 작업을 하나의 Task 연쇄로 묶어 순서를 보장하던 설계)의
 * Kotlin 이식이다. 이 액터에 제출된 작업(패킷 처리/ACK 송신/제어 송신/DB 콜백)은 **제출한 순서대로
 * 하나씩만** 실행되고, 서로 다른 커넥션의 액터끼리는 완전히 병렬로 동작한다.
 *
 * 대기열이 가득 차면(`queueCapacity` 초과) 새 작업을 침묵 성공시키지 않고
 * [GateTaskRejectedException]을 던진다 — 레거시의 `ChannelRejectedException` 설계 원칙을 계승.
 */
class GateConnectionActor(
    private val connectionKey: String,
    dispatcher: CoroutineDispatcher,
    queueCapacity: Int,
) {
    private val logger = LoggerFactory.getLogger(GateConnectionActor::class.java)

    private val job = SupervisorJob()
    private val scope = CoroutineScope(dispatcher + job)

    private val channel = Channel<suspend () -> Unit>(capacity = queueCapacity)

    @Volatile
    private var closed = false

    init {
        scope.launch {
            for (task in channel) {
                try {
                    task()
                } catch (ex: Exception) {
                    logger.error("커넥션[{}] 액터 작업 처리 중 예외 발생", connectionKey, ex)
                }
            }
        }
    }

    /**
     * 작업을 대기열에 넣는다. 이미 닫혔거나 대기열이 가득 차면 [GateTaskRejectedException]을 던진다
     * (호출자가 "제출되지 않음"을 명확히 구분할 수 있어야 한다 — 계획서 3.3절).
     */
    fun submit(task: suspend () -> Unit) {
        if (closed) throw GateTaskRejectedException(connectionKey)
        val result = channel.trySend(task)
        if (result.isFailure) throw GateTaskRejectedException(connectionKey)
    }

    /** 대기 중인 작업을 모두 버리고 액터를 종료한다. 이후 [submit]은 항상 거부된다. */
    fun close() {
        closed = true
        channel.close()
        job.cancel()
    }

    val isClosed: Boolean get() = closed
}
