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
     *
     * [레인 소유권 검사] `hasAuthoritativeLaneInfo`가 true인 커넥션에 대해 `dtlLaneNo`를 소유하지
     * 않으면 전송을 거부한다 — 제어 명령처럼 특정 레인을 대상으로 하는 전송에 쓴다. 패킷 내용이
     * 레인에 종속되지 않는 전체 커넥션 대상 요청(예: 상태 조회)에는 [sendToConnection]을 쓴다.
     *
     * **반환값 주의(2026-08-13 Opus 전체 리뷰 지적)**: 이 메서드는 [sendToConnection]과 달리
     * 실제 소켓 쓰기 완료를 기다리지 않는다 — `true`는 "액터 큐에 넣는 데 성공했다(대기열이
     * 가득 차지 않았다)"는 뜻일 뿐, 물리 전송이 실제로 끝났거나 성공했다는 보장이 아니다. 호출자가
     * "전송 완료"를 전제로 후속 상태를 기록하면(예: 확인 대기 타임스탬프), 그 시점에 실제 소켓
     * write는 아직 일어나지 않았을 수 있다.
     *
     * @param trackForAck 전송한 레인을 ACK 상관관계 FIFO([GateConnectionState.recordSentLane])에
     *   기록할지 여부(Codex 어드버서리얼 리뷰 대응). 장비 ACK를 기다려 상관시켜야 하는 **제어
     *   명령** 전송만 `true`(기본값)를 쓴다. ACK 상관관계가 필요 없는 전송이 이 큐를 오염시키면,
     *   뒤이어 도착한 제어 명령 ACK가 엉뚱한 레인으로 잘못 귀속될 수 있다 — `false`를 넘긴다.
     */
    fun sendToLane(dtlIp: String, dtlLaneNo: Int, packet: ByteArray, trackForAck: Boolean = true): Boolean

    /**
     * 레인 소유권 검사 없이, 커넥션(디바이스 IP) 하나에 패킷을 전송하고 실제 소켓 쓰기가 완료될
     * 때까지 대기한다 — [sendToLane]과 달리 반환값이 "큐잉 성공"이 아니라 "물리 전송 성공"을
     * 의미한다(위 [sendToLane] 반환값 주의 참고 — 두 메서드의 계약이 다르다).
     *
     * `ReqStatusJob`의 상태 조회 요청처럼 패킷이 특정 레인이 아니라 커넥션(장치) 전체를 대상으로
     * 할 때 쓴다. `sendToLane`은 `hasAuthoritativeLaneInfo=true`인데 레인 집합이 비어 있는(예:
     * 장치가 `GATE_STATUS` 패킷에서 레인 수 0을 보고한) 특이 케이스에서 임의로 고른 대표 레인이
     * 소유권 검사에 걸려 영구적으로 전송이 거부될 수 있다 — 레인 종속적이지 않은 요청은 애초에
     * 레인 번호로 라우팅할 이유가 없으므로 이 메서드로 그 문제를 원천적으로 피한다.
     */
    suspend fun sendToConnection(dtlIp: String, packet: ByteArray): Boolean
}
