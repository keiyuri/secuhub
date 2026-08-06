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
     * [레인 소유권 검사] `hasAuthoritativeLaneInfo`가 true인 커넥션에 대해 `dtlLaneNo`를 소유하지
     * 않으면 전송을 거부한다 — 제어 명령처럼 특정 레인을 대상으로 하는 전송에 쓴다. 패킷 내용이
     * 레인에 종속되지 않는 전체 커넥션 대상 요청(예: 상태 조회)에는 [sendToConnection]을 쓴다.
     */
    fun sendToLane(dtlIp: String, dtlLaneNo: Int, packet: ByteArray): Boolean

    /**
     * 레인 소유권 검사 없이, 커넥션(디바이스 IP) 하나에 패킷을 전송한다.
     *
     * `ReqStatusJob`의 상태 조회 요청처럼 패킷이 특정 레인이 아니라 커넥션(장치) 전체를 대상으로
     * 할 때 쓴다. `sendToLane`은 `hasAuthoritativeLaneInfo=true`인데 레인 집합이 비어 있는(예:
     * 장치가 `GATE_STATUS` 패킷에서 레인 수 0을 보고한) 특이 케이스에서 임의로 고른 대표 레인이
     * 소유권 검사에 걸려 영구적으로 전송이 거부될 수 있다 — 레인 종속적이지 않은 요청은 애초에
     * 레인 번호로 라우팅할 이유가 없으므로 이 메서드로 그 문제를 원천적으로 피한다.
     */
    fun sendToConnection(dtlIp: String, packet: ByteArray): Boolean
}
