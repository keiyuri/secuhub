package kr.co.securance.secuhub.server.connection

import kr.co.securance.secuhub.protocol.GateProtocolCodec
import kr.co.securance.secuhub.protocol.PacketReassembler
import reactor.netty.Connection
import reactor.netty.NettyOutbound
import java.util.concurrent.atomic.AtomicReference

/**
 * 커넥션(디바이스 IP) 1개의 런타임 상태.
 *
 * 레거시 `ClsAsyncObj`에 대응한다. 연결 방향(SERVER/CLIENT)에 관계없이 동일한 구조를 쓴다 —
 * 어느 쪽이 소켓을 열었는지는 이 클래스가 몰라도 되며, [GateConnectionRegistry]가 IP로만 다룬다.
 */
class GateConnectionState(
    val dtlIp: String,
    val gateTypeCode: Int,
    val codec: GateProtocolCodec,
    val connection: Connection,
    val outbound: NettyOutbound,
    val actor: GateConnectionActor,
) {
    /** 채널이 살아있는지(레거시 `Socket.Poll` 대응) — `NetCheckJob`(securance-scheduler)이 사용. */
    val isChannelActive: Boolean
        get() = connection.channel().isActive

    /**
     * 이 소켓이 실어나르는 레인 번호 집합(최대 32개, 계획서 3.2절).
     *
     * **원자적 교체(적대적 리뷰에서 지적)**: 예전에는 `ConcurrentHashMap.newKeySet()`에
     * `clear()` 후 `addAll()`을 호출했는데, 개별 연산은 스레드 안전해도 그 사이(clear~addAll)에
     * 집합이 "일시적으로 빈 상태"로 관측될 수 있었다 — 그 창에서 `sendToLane`이 제어 명령을
     * 침묵 드롭하거나, `laneSnapshot()`이 빈 집합을 반환해 `finalizeClose`가 오프라인 net_state
     * 갱신을 하나도 큐잉하지 못하는 문제가 있었다. `AtomicReference<Set<Int>>`로 바꿔 교체를
     * 항상 단일 원자적 스왑(또는 CAS)으로 수행한다.
     */
    private val laneNumbersRef = AtomicReference<Set<Int>>(emptySet())

    /**
     * `0x4D`(GATE_STATUS) 패킷으로 레인 집합을 한 번이라도 authoritative하게 교체했는지 여부.
     * 그 전까지는 다른 패킷으로 레인 번호를 알게 되어도 union-add만 허용한다(계획서 3.2절 —
     * 레인 집합이 부분 정보로 축소되어 제어 명령이 특정 레인에 전달되지 않는 레거시 버그 재발 방지).
     */
    @Volatile
    var hasAuthoritativeLaneInfo: Boolean = false
        private set

    val reassembler: PacketReassembler = codec.newReassembler()

    @Volatile
    var lastStatusPacket: ByteArray? = null

    /**
     * `DefaultGatePacketHandler`가 이미 온라인으로 net_state에 기록한 레인 캐시(적대적 리뷰 지적:
     * 상태 패킷마다 무조건 전 레인을 다시 enqueue하면 DB 쓰기 큐가 폴링 주기마다 포화됨).
     * [lastStatusPacket]과 마찬가지로 이 커넥션의 액터(순차 실행) 안에서만 읽고 쓰므로 동기화가
     * 필요 없다 — 단, 액터 밖에서 접근하는 코드를 추가하려면 반드시 동기화 수단을 함께 넣어야 한다.
     */
    var onlineLanesRecorded: Set<Int> = emptySet()

    fun laneSnapshot(): Set<Int> = laneNumbersRef.get()

    /** `0x4D` 상태 패킷 수신 시 호출 — 레인 집합을 authoritative하게 교체한다(단일 원자적 스왑). */
    fun replaceLaneNumbers(lanes: Collection<Int>) {
        laneNumbersRef.set(lanes.toSet())
        hasAuthoritativeLaneInfo = true
    }

    /** `0x4D` 이외의 패킷에서 레인 번호를 알게 됐을 때 호출 — 축소 없이 추가만 한다(CAS 루프). */
    fun ensureLaneKnown(lane: Int) {
        while (true) {
            val current = laneNumbersRef.get()
            if (lane in current) return
            if (laneNumbersRef.compareAndSet(current, current + lane)) return
        }
    }

    fun ownsLane(lane: Int): Boolean = lane in laneNumbersRef.get()
}
