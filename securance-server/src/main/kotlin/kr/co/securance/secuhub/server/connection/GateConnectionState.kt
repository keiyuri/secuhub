package kr.co.securance.secuhub.server.connection

import kr.co.securance.secuhub.protocol.GateProtocolCodec
import kr.co.securance.secuhub.protocol.PacketReassembler
import reactor.netty.Connection
import reactor.netty.NettyOutbound
import java.util.concurrent.ConcurrentHashMap

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

    /** 이 소켓이 실어나르는 레인 번호 집합(최대 32개, 계획서 3.2절). */
    private val laneNumbers = ConcurrentHashMap.newKeySet<Int>()

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

    fun laneSnapshot(): Set<Int> = laneNumbers.toSet()

    /** `0x4D` 상태 패킷 수신 시 호출 — 레인 집합을 authoritative하게 교체한다. */
    fun replaceLaneNumbers(lanes: Collection<Int>) {
        laneNumbers.clear()
        laneNumbers.addAll(lanes)
        hasAuthoritativeLaneInfo = true
    }

    /** `0x4D` 이외의 패킷에서 레인 번호를 알게 됐을 때 호출 — 축소 없이 추가만 한다. */
    fun ensureLaneKnown(lane: Int) {
        laneNumbers.add(lane)
    }

    fun ownsLane(lane: Int): Boolean = laneNumbers.contains(lane)
}
