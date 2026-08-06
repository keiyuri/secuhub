package kr.co.securance.secuhub.server.connection

import kotlinx.coroutines.CompletableDeferred
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

    /**
     * [submit]과 달리 작업이 액터 큐에서 실제로 실행 완료(성공/예외)될 때까지 대기한다.
     *
     * [Codex 리뷰 수정] `SendControlJob`이 `submit()`의 "큐잉 성공"만 보고 `sndYn='Y'`를 확정
     * 저장하던 문제 — 큐잉 직후 연결이 끊기거나 `outbound.sendByteArray()`가 비동기로 실패해도
     * 이미 "전송됨"으로 영구 확정되어 재조회 대상에서 빠지는 유실 버그였다. 여기서는 작업 자체가
     * 실제 소켓 쓰기까지 끝난 뒤에야 결과를 반환하므로, 물리 전송 실패 시 호출자가 `sndYn`을
     * 갱신하지 않고 다음 스케줄의 재시도 대상으로 남길 수 있다.
     *
     * 큐 등록 자체가 거부(대기열 초과/이미 닫힘)되면 [GateTaskRejectedException]을 그대로 던진다
     * (기존 [submit] 계약과 동일 — 호출자가 "제출조차 안 됨"과 "제출 후 실패"를 구분할 필요는
     * 없으므로 두 경우 모두 `false`로 수렴시켜도 되지만, 예외를 그대로 전파해 호출자가 로그 문맥을
     * 구분할 수 있게 한다).
     */
    suspend fun submitAndAwait(task: suspend () -> Unit): Boolean {
        val completion = CompletableDeferred<Unit>()
        submit {
            try {
                task()
                completion.complete(Unit)
            } catch (ex: Exception) {
                completion.completeExceptionally(ex)
                throw ex // 액터 루프의 기존 예외 로그도 그대로 남긴다.
            }
        }
        return try {
            completion.await()
            true
        } catch (ex: Exception) {
            false
        }
    }

    /** 대기 중인 작업을 모두 버리고 액터를 종료한다. 이후 [submit]은 항상 거부된다. */
    fun close() {
        closed = true
        channel.close()
        job.cancel()
    }

    val isClosed: Boolean get() = closed
}
