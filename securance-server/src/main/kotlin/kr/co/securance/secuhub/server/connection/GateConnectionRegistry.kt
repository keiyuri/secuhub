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
     * [dtlIp]의 현재 커넥션이 [expected]와 **동일한 인스턴스일 때만** 원자적으로 닫는다.
     *
     * `findConnection(dtlIp) === expected` 확인 후 별도로 [closeConnection]을 호출하는 방식은
     * 확인과 제거 사이에 재연결이 끼어들 수 있는 TOCTOU(check-then-act) 레이스가 있다 — 그 틈에
     * 새 커넥션이 등록되면, 뒤늦게 도착한 이전 소켓의 dispose 콜백이 새 커넥션을 지우고
     * 오프라인으로 잘못 기록해버린다. 이 메서드는 조회와 제거를 하나의 원자적 연산으로 묶어
     * 그 레이스를 원천 차단한다(계획서 3.3절 재연결 레이스 가드).
     *
     * @return 실제로 닫았으면 true, 이미 다른 커넥션으로 교체되어 있어 아무 것도 하지 않았으면 false.
     */
    suspend fun closeConnectionIfCurrent(dtlIp: String, expected: GateConnectionState, updateNetState: Boolean = true): Boolean

    /**
     * 지정한 레인으로 패킷을 전송한다(커넥션의 액터 체인에 enqueue). 커넥션이 없거나 대기열이
     * 가득 차면(계획서 3.3절 [kr.co.securance.secuhub.common.exception.GateTaskRejectedException]) false를 반환한다.
     */
    fun sendToLane(dtlIp: String, dtlLaneNo: Int, packet: ByteArray): Boolean
}
