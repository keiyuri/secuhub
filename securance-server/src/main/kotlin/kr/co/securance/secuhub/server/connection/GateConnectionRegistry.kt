package kr.co.securance.secuhub.server.connection

/**
 * `securance-scheduler`(Quartz 잡)에 노출되는 좁은 커넥션 조회/제어 인터페이스(계획서 3.6절).
 *
 * 레거시 `IConnectionRegistry`에 대응한다. 잡은 이 인터페이스만 알면 되고
 * [GateConnectionActor]/Netty 내부 구현을 직접 참조하지 않는다 — 두 모듈 간 경계를 명확히 유지한다.
 */
interface GateConnectionRegistry {

    /** 현재 연결된 모든 커넥션의 스냅샷 — `NetCheckJob`(securance-scheduler)이 순회하며 생존을 확인한다. */
    fun allConnections(): Collection<GateConnectionState>

    /** 디바이스 IP로 커넥션을 조회한다. 연결되어 있지 않으면 null. */
    fun findConnection(dtlIp: String): GateConnectionState?

    /**
     * 커넥션을 닫는다.
     * @param updateNetState 오프라인 상태를 DB에 반영할지 여부 — 재연결 레이스 가드(계획서 3.3절)를
     *   위해, 동일 IP의 더 최신 연결이 이미 존재함을 호출자가 확인한 경우 false를 전달한다.
     */
    suspend fun closeConnection(dtlIp: String, updateNetState: Boolean = true)

    /**
     * 지정한 레인으로 패킷을 전송한다(커넥션의 액터 체인에 enqueue). 커넥션이 없거나 대기열이
     * 가득 차면(계획서 3.3절 [kr.co.securance.secuhub.common.exception.GateTaskRejectedException]) false를 반환한다.
     *
     * @param trackForAck 전송한 레인을 ACK 상관관계 FIFO([GateConnectionState.recordSentLane])에
     *   기록할지 여부(Codex 어드버서리얼 리뷰 대응). 장비 ACK를 기다려 상관시켜야 하는 **제어
     *   명령** 전송만 `true`(기본값)를 쓴다. 상태 폴링([kr.co.securance.secuhub.scheduler.job.ReqStatusJob])
     *   등 ACK 상관관계가 필요 없는 전송이 이 큐를 오염시키면, 뒤이어 도착한 제어 명령 ACK가
     *   엉뚱한 레인(폴링이 임의로 고른 레인)으로 잘못 귀속될 수 있다 — 반드시 `false`를 넘긴다.
     */
    fun sendToLane(dtlIp: String, dtlLaneNo: Int, packet: ByteArray, trackForAck: Boolean = true): Boolean
}
