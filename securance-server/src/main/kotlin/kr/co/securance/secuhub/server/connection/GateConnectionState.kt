package kr.co.securance.secuhub.server.connection

import kr.co.securance.secuhub.domain.repository.GateLaneInfo
import kr.co.securance.secuhub.protocol.GateProtocolCodec
import kr.co.securance.secuhub.protocol.PacketReassembler
import reactor.netty.Connection
import reactor.netty.NettyOutbound
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

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
    /**
     * 연결 수립 시 `tb_gate_dtl`에서 **1회만** 조회해 캐시한 이 IP의 전체 레인 식별 정보.
     *
     * 레거시 `ClsAsyncObj.GateLaneInfo`(M-8 성능 수정)에 대응한다. 수신 패킷마다 loc_id/grp_id를
     * 재조회하면 고빈도 경로에서 N+1 조회가 발생하므로, DB 쓰기 작업은 이 캐시 값만 사용한다.
     */
    val laneInfo: List<GateLaneInfo> = emptyList(),
) {
    /** 레인 번호로 캐시된 식별 정보를 찾는다. `tb_gate_dtl`에 등록되지 않은 레인이면 null. */
    fun laneInfoOf(dtlLaneNo: Int): GateLaneInfo? = laneInfo.firstOrNull { it.dtlLaneNo == dtlLaneNo }

    /**
     * 대표 레인 정보(가장 낮은 레인 번호). 레인 번호를 아직 특정하지 못한 시점의
     * loc_id/grp_id fallback으로 쓴다 — 같은 IP의 모든 레인은 동일한 위치/그룹에 속한다.
     */
    val primaryLaneInfo: GateLaneInfo? get() = laneInfo.firstOrNull()

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

    /**
     * 이 커넥션으로 보낸 레인 번호의 FIFO 대기열(Codex 리뷰 P1 대응).
     *
     * SpeedGate 프로토콜의 ACK 프레임에는 어떤 명령에 대한 응답인지 식별할 필드가 전혀 없다
     * (Header + 7바이트 타임싱크 DataInfo + Tail뿐 — [kr.co.securance.secuhub.protocol.SpeedGatePacketCodec.buildAck]
     * 참고). 유일하게 신뢰할 수 있는 단서는 "이 소켓은 TCP라 순서가 보장되고, 장비는 받은 순서대로
     * 처리·응답한다"는 가정뿐이다 — 이 큐가 그 가정을 코드로 표현한다.
     *
     * 물리 전송 성공 시점([kr.co.securance.secuhub.server.connection.GateConnectionRegistryImpl.sendToLane])에
     * [recordSentLane]으로 채우고, ACK 수신 시점([kr.co.securance.secuhub.server.tcp.DefaultGatePacketHandler])에
     * [pollSentLane]으로 하나씩 꺼내 상관시킨다. 이전에는 ACK의 대상 레인을 항상 [primaryLaneInfo]
     * (사실상 "대표 레인 고정")로 귀속시켜, 다중 레인 소켓에서 레인 2로 보낸 명령의 ACK가 레인 1의
     * 것으로 잘못 확인 처리되고 레인 2의 명령은 계속 재전송되다 실패 확정되는 문제가 있었다.
     * 같은 레인에 명령을 연속 발행하면 첫 ACK가 그중 어느 것에 대한 응답인지까지는 여전히 구분하지
     * 못하지만(프로토콜 자체의 한계), 최소한 **다른 레인**으로 잘못 귀속되는 일은 없앤다.
     */
    private val sentLaneQueue = ConcurrentLinkedQueue<Int>()

    /** 물리 전송 성공 직후 호출 — 큐가 무한히 자라지 않도록 상한을 둔다. */
    fun recordSentLane(dtlLaneNo: Int) {
        sentLaneQueue.add(dtlLaneNo)
        while (sentLaneQueue.size > MAX_TRACKED_SENDS) {
            sentLaneQueue.poll()
        }
    }

    /** ACK 수신 시점에 가장 오래전에 보낸 레인을 꺼낸다. 추적된 전송이 없으면 null. */
    fun pollSentLane(): Int? = sentLaneQueue.poll()

    companion object {
        /** 이 상한을 넘는 오래된 미확인 전송 기록은 버린다(장기 가동 시 메모리 누수 방지). */
        private const val MAX_TRACKED_SENDS = 64
    }
}
