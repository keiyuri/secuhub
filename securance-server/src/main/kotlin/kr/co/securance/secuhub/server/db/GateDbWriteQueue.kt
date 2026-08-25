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
import java.util.concurrent.atomic.AtomicReferenceArray
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
 *
 * **샤드 워커 간 격리(2026-08-25 소스 전수 검토 지적)**: [runShardWorker]는 `task.execute()`가
 * 흘린 일반 `Exception`은 전부 삼켜 재시도한다(아래 `catch (ex: Exception)`). 다만 그 catch가
 * 잡지 못하는 `Throwable`(`Exception`이 아닌 `Error` — 예: `OutOfMemoryError`,
 * `StackOverflowError`, 또는 `task.execute()`가 직접 `throw`한 임의의 `Error`)이 새어 나오면
 * 이야기가 다르다. [workerJobs]의 각 샤드 워커는 [scope](`SupervisorJob` 보유)의 **직접** 자식으로
 * `launch`해야 한다 — `scope.launch { shards.forEach { launch { ... } } }`처럼 한 단계 감싸면
 * 실제 샤드 워커들은 그 감싸는 평범한(non-supervisor) Job의 자식이 되고, 이런 `Error`가 그
 * 감싸는 Job을 실패시켜 형제 샤드 워커 전부가 함께 취소된다 — **DB 쓰기 파이프라인 전체가
 * 재기동 없이 조용히 멈춘다**. [scope]에 직접 걸어야 `SupervisorJob`의 격리가 실제로 적용되어
 * 한 샤드의 실패가 다른 샤드를 끌고 내려가지 않는다. (참고: `CancellationException`은 이
 * 문제와 무관하다 — 자식 코루틴이 `CancellationException`으로 완료되는 것은 Kotlin
 * 구조적 동시성에서 "그 자식이 정상적으로 취소됨"으로 취급되어 부모/형제에게 실패로
 * 전파되지 않는다. `GateDbWriteQueueTest`의 격리 회귀 테스트가 `Error`로 이를 재현·고정한다.)
 *
 * **같은 샤드 내 워커 생존(Codex 리뷰 지적, 2026-08-25 후속)**: 위 격리만으로는 부족했다 —
 * [runShardWorker]가 `Error`를 그대로 밖으로 흘리면 형제 샤드는 살아남지만, **그 `Error`를 던진
 * 샤드 자신의 워커 코루틴**은 영구 종료된다. 이후 같은 샤드로 해시되는 모든 작업은 소비자가
 * 사라진 [Channel]에 쌓이다가 용량(1000)을 넘기면 계속 드롭되고, 그 파티션키(디바이스)의 DB
 * 쓰기는 프로세스를 재기동하기 전까지 복구되지 않는다.
 *
 * **`VirtualMachineError`는 "작업 실패"로 삼키지 않는다(Codex 적대적 리뷰 지적, 2026-08-25
 * 재후속)**: 위 문제의 첫 수정은 [runShardWorker]가 [processTask] 전체를 `catch (ex: Throwable)`로
 * 감싸 이 작업 하나만 최종 실패 처리하고 루프를 계속 돌게 했다. 하지만 `Throwable`을 이렇게
 * 넓게 잡으면 `OutOfMemoryError`/`StackOverflowError` 같은 [VirtualMachineError](JVM 자체가
 * 이미 불안정하다는 신호)까지 "이 작업 하나만 실패했을 뿐"인 것처럼 로그·카운터·fallback을
 * 실행하고 **곧바로 다음 작업을 계속 처리**하게 된다 — 손상됐을 수 있는 JVM 상태로 DB 쓰기를
 * 계속 시도하는 셈이라 위험하고, 진짜 치명적 장애를 평범한 작업 실패로 위장해 감지·복구를
 * 방해한다. 그래서 [processTask]는 `VirtualMachineError`(와 절대 삼켜서는 안 되는 `ThreadDeath`)를
 * 만나면 삼키지 않고 그대로 재전파한다 — [runShardWorker]도 이를 다시 재전파해 그 시도의
 * 콜스택(잠재적으로 손상된 상태)을 즉시 벗어난다.
 *
 * 대신 [runShardWorker]를 감싸는 [superviseShardWorker]가 `VirtualMachineError`를 받으면 그
 * 사실을 ERROR 레벨로 남기고(직전 작업 결과는 신뢰할 수 없다는 뜻) 짧은 지연 후 **새 코루틴으로
 * [runShardWorker]를 재시작**한다 — "죽은 척만 하고 계속 처리"가 아니라 "실제로 죽고, 새로
 * 태어난 워커가 다음 작업부터 이어받는" 구조다. 채널에서 이미 소비된(=죽음의 원인이 된) 작업은
 * 재시도되지 않고 유실되며([runFallback]도 호출되지 않는다 — fallback 자체도 블로킹 IO라 같은
 * 손상된 JVM 상태에서 또 실패할 수 있다), 그 뒤에 들어온 작업들은 재시작된 워커가 정상적으로
 * 이어받는다. `ThreadDeath`는 [superviseShardWorker]에서도 잡지 않고 그대로 통과시킨다(JVM
 * 명세상 항상 다시 던져야 하는 유일한 예외).
 *
 * `VirtualMachineError`가 아닌 그 외 `Throwable`(예: `AssertionError`, `task.execute()`가 직접
 * `throw`한 커스텀 `Error`)은 JVM 자체의 건전성과 무관하므로 여전히 [runShardWorker]가 이 작업
 * 하나만 최종 실패로 처리(카운터 증가 + [runFallback])하고 워커를 재시작 없이 계속 사용한다 —
 * 재시작(및 그 지연)은 진짜 `VirtualMachineError`에만 쓰는 무거운 대응이다.
 * `CancellationException`은 어느 계층에서도 그대로 다시 던져 shutdown()에 의한 정상 종료를
 * 방해하지 않는다.
 *
 * **`ThreadDeath`도 실제로는 재전파돼야 했다(Codex 리뷰 지적, 2026-08-25 삼후속)**: 위 문단이
 * "`ThreadDeath`는 [superviseShardWorker]에서도 잡지 않고 통과시킨다"고 적어놓고도, 정작
 * [runShardWorker]의 `catch (ex: Throwable)`(`VirtualMachineError`가 아닌 그 외 Error 처리용)이
 * `ThreadDeath`까지 함께 걸러 "이 작업만 최종 실패"로 삼키고 있었다 — `ThreadDeath`는
 * `VirtualMachineError`의 하위 타입이 아니라 [runShardWorker]의 `VirtualMachineError` catch를
 * 거치지 않고 그대로 아래 넓은 `catch (ex: Throwable)`로 떨어졌기 때문이다. 그 결과
 * `Thread.stop()`(또는 명시적으로 던져진 `ThreadDeath`)으로 워커 스레드 종료를 요청해도 워커가
 * 계속 살아남아 다음 작업을 처리하는, JVM 명세("`ThreadDeath`를 잡았다면 반드시 다시 던져야
 * 한다")를 정면으로 어기는 회귀였다. [runShardWorker]에 `VirtualMachineError`와 나란히
 * `catch (ex: ThreadDeath) { throw ex }`를 추가해 바로잡았다.
 *
 * **`ThreadDeath`도 채널 소비자를 잃지 않아야 한다(Codex 적대적 리뷰 지적, 2026-08-25 사후속)**:
 * 위 수정은 [runShardWorker] 레벨의 삼킴은 고쳤지만, [superviseShardWorker]가 `VirtualMachineError`와
 * 달리 `ThreadDeath`는 여전히 잡지 않고 그대로 통과시켰다 — "명세를 지킨다"는 점에서는 맞았지만,
 * 그 결과 이 코루틴이 그대로 종료되며 [Channel] 소비자가 영구히 사라진다는 점은
 * "샤드 워커 간 격리"/"같은 샤드 내 워커 생존" 문단이 `VirtualMachineError`에 대해 이미 고쳤던
 * 것과 동일한 문제였다 — 이후 같은 샤드로 해시되는 모든 작업이 프로세스 재기동 전까지 조용히
 * 드롭된다. `VirtualMachineError`처럼 **같은 코루틴 안에서 재시도**할 수는 없다(JVM 명세: 잡았다면
 * 반드시 다시 던져야 하므로 "잡고 계속 진행"이 애초에 금지된다). 그래서 [superviseShardWorker]는
 * `ThreadDeath`를 잡으면 ① 새 코루틴으로 **후임 [superviseShardWorker]를 먼저 띄워 채널 소비를
 * 이어받게 하고**([activeWorkerJobs]에 등록해 [shutdown]이 추적할 수 있게 한다) ② 그런 뒤에야
 * 원래의 `ThreadDeath`를 그대로 다시 던져 **이 코루틴 자신은 진짜로 종료**시킨다 — "명세를 지키며
 * 죽는 것"과 "채널에 소비자가 비지 않는 것"을 동시에 만족한다. 후임 코루틴이 이어받는 시점에
 * 이미 채널에서 소비된(=`ThreadDeath`의 원인이 된) 작업은 재시도되지 않고 유실된다(`VirtualMachineError`
 * 재시작과 동일 — [runFallback]도 호출하지 않는다).
 */
open class GateDbWriteQueue(
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

    /**
     * 샤드별 "현재" 감독 코루틴 — 각각 [scope](SupervisorJob 보유)의 직접 자식으로 걸어 서로
     * 격리한다(클래스 KDoc "샤드 워커 간 격리" 참고). `ThreadDeath`를 만나면 [superviseShardWorker]가
     * 후임 코루틴을 새로 띄우고 이 배열의 해당 슬롯을 그 후임으로 교체한다(클래스 KDoc "`ThreadDeath`도
     * 채널 소비자를 잃지 않아야 한다" 참고) — 그래서 고정된 `List<Job>`이 아니라 교체 가능한
     * [AtomicReferenceArray]로 추적해야 [shutdown]이 항상 "현재" 활성 코루틴을 취소할 수 있다.
     */
    private val activeWorkerJobs: AtomicReferenceArray<Job> = AtomicReferenceArray<Job>(shardCount).apply {
        shards.forEachIndexed { index, shard ->
            set(index, scope.launch { superviseShardWorker(index, shard) })
        }
    }

    /** 작업을 파티션 큐에 넣는다. 큐가 가득 차면 드롭하고 카운터만 올린다(계획서 3.5절 방어적 설계). */
    open fun enqueue(task: GateDbWriteTask) {
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
        // activeWorkerJobs는 ThreadDeath 재시작으로 교체될 수 있어(클래스 KDoc 참고) 생성 시점의
        // 고정 목록이 아니라 "지금 이 순간" 각 슬롯이 가리키는 코루틴을 취소한다 — 고정 목록을
        // 취소했다면 ThreadDeath 이후 새로 띄운 후임 코루틴은 취소되지 않고 남아있게 된다.
        for (i in 0 until shardCount) {
            activeWorkerJobs.get(i).cancel()
        }
        // graceful shutdown: 이미 blockingExecutor에서 돌고 있는 블로킹 호출을 강제 인터럽트하지 않는다
        // (표준 Socket.read()는 인터럽트에 반응하지 않으므로 효과도 없다) — JDBC 소켓 타임아웃이
        // 각 스레드를 알아서 정리해줄 때까지 기다리게 둔다.
        blockingExecutor.shutdown()
    }

    /**
     * [runShardWorker]를 감독한다 — `VirtualMachineError`로 죽으면 새 코루틴으로 재시작해 채널
     * 소비자를 복구한다(클래스 KDoc "`VirtualMachineError`는 '작업 실패'로 삼키지 않는다" 참고).
     *
     * `ThreadDeath`는 JVM 명세상 이 함수 자신은 반드시 다시 던져 종료돼야 하므로 `VirtualMachineError`
     * 처럼 "같은 코루틴에서 재시도"할 수는 없다 — 대신 다시 던지기 **전에** 후임 코루틴을 새로 띄워
     * 채널 소비가 끊기지 않게 한 뒤에야 종료한다(클래스 KDoc "`ThreadDeath`도 채널 소비자를 잃지
     * 않아야 한다" 참고).
     */
    private suspend fun superviseShardWorker(shardIndex: Int, shard: Channel<GateDbWriteTask>) {
        while (true) {
            try {
                runShardWorker(shardIndex, shard)
                return // shard.close()(shutdown())로 채널이 정상 종료됨 — 재시작하지 않고 끝낸다.
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: VirtualMachineError) {
                logger.error(
                    "샤드 워커가 VirtualMachineError로 종료되어 재시작합니다(shard={}) — 직전 작업 결과는 " +
                        "신뢰할 수 없어 유실 처리합니다. 같은 오류가 반복되면 프로세스 자체의 안정성을 " +
                        "점검해야 합니다.",
                    shardIndex, ex,
                )
                // 짧은 지연 후 재시작 — 같은 오류가 즉시 반복되는 크래시 루프에서 로그/CPU가
                // 무한정 소모되는 것을 막는다. 재시작 자체를 포기하지는 않는다 — 채널에 소비자가
                // 아예 없는 것보다는(=그 샤드로 라우팅되는 모든 이후 작업이 드롭됨) 낫다는 판단이다.
                delay(SHARD_WORKER_RESTART_DELAY)
            } catch (@Suppress("DEPRECATION") ex: ThreadDeath) {
                logger.error(
                    "샤드 워커가 ThreadDeath로 종료됩니다(shard={}) — 이 코루틴 자신은 JVM 명세대로 " +
                        "다시 던져 그대로 종료시키되, 채널 소비자가 비지 않도록 후임 코루틴을 먼저 띄워 " +
                        "이어받게 합니다. 직전 작업 결과는 신뢰할 수 없어 유실 처리합니다(Codex 적대적 " +
                        "리뷰 지적, 2026-08-25).",
                    shardIndex, ex,
                )
                // 후임을 먼저 띄우고 activeWorkerJobs에 등록해 shutdown()이 추적할 수 있게 한 뒤에야
                // ThreadDeath를 다시 던진다 — "잡았다면 다시 던져야 한다"는 명세와 "채널에 소비자가
                // 비는 순간이 없어야 한다"를 함께 만족한다. VirtualMachineError처럼 지연을 두지 않는
                // 이유: ThreadDeath는 보통 일회성 종료 요청(Thread.stop() 등)이지 반복되는 JVM
                // 불안정 신호가 아니라, 크래시 루프 억제보다 소비자 복구를 즉시 하는 쪽이 낫다.
                activeWorkerJobs.set(shardIndex, scope.launch { superviseShardWorker(shardIndex, shard) })
                throw ex
            }
        }
    }

    private suspend fun runShardWorker(shardIndex: Int, shard: Channel<GateDbWriteTask>) {
        for (task in shard) {
            try {
                processTask(shardIndex, task)
            } catch (ex: CancellationException) {
                // shutdown()/workerJobs 개별 cancel()로 인한 진짜 취소 — 그대로 전파해야 for 루프가
                // 멈추고 워커 코루틴이 정상적으로 종료된다.
                throw ex
            } catch (ex: VirtualMachineError) {
                // OutOfMemoryError/StackOverflowError 등 — 여기서 삼켜 다음 작업을 계속 처리하면
                // 안 된다(클래스 KDoc 참고). superviseShardWorker가 워커 자체를 재시작한다.
                throw ex
            } catch (@Suppress("DEPRECATION") ex: ThreadDeath) {
                // JVM 명세상 항상 다시 던져야 하는 유일한 예외(2026-08-25 Codex 리뷰 지적) — 아래
                // `catch (ex: Throwable)`은 VirtualMachineError가 아닌 Error를 "이 작업만 최종
                // 실패"로 삼켜 워커를 계속 돌리는데, ThreadDeath까지 여기 걸리면 Thread.stop() 등
                // 스레드 종료 요청이 무시된 채 워커가 계속 살아남는다. VirtualMachineError와 동일하게
                // 재전파해 superviseShardWorker(또는 그 상위)가 이 종료 신호를 그대로 받게 한다.
                // `ThreadDeath` 자체가 Java 20부터 @Deprecated지만(향후 제거 예정), 남아있는 동안은
                // JVM 명세("잡았다면 반드시 다시 던져야 한다")를 지켜야 하므로 타입 참조를 그대로 쓴다.
                throw ex
            } catch (ex: Throwable) {
                // VirtualMachineError가 아닌 Error(예: AssertionError, task.execute()가 직접 던진
                // 커스텀 Error) — JVM 자체의 건전성과는 무관하므로 이 작업 하나만 최종 실패로
                // 처리하고 워커 재시작 없이 for 루프를 계속 돈다.
                finalFailureCount.incrementAndGet()
                logger.error(
                    "DB 쓰기 중 처리 불가능한 오류(Error) 발생 — 이 작업만 최종 실패로 처리하고 워커는 계속 동작합니다: shard={}, op={}",
                    shardIndex, task.operationName, ex,
                )
                runFallback(task, shardIndex)
            }
        }
    }

    /** [runShardWorker]가 작업 하나를 재시도 한도까지 시도하는 본체 — 성공/최종 실패 판정과 백오프를 담당한다. */
    private suspend fun processTask(shardIndex: Int, task: GateDbWriteTask) {
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
                // shutdown()/workerJobs 개별 cancel()로 인한 진짜 취소 — 작업 실패로 삼켜 재시도하면
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

    companion object {
        /** [superviseShardWorker]가 `VirtualMachineError` 이후 워커를 재시작하기 전 대기하는 시간. */
        private val SHARD_WORKER_RESTART_DELAY = 1.seconds
    }
}
