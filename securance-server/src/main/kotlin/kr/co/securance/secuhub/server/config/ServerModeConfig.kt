package kr.co.securance.secuhub.server.config

import org.springframework.boot.context.properties.ConfigurationProperties

/** 백엔드↔게이트 TCP 연결 방향(계획서 3.1절). 어느 쪽이 소켓을 여는지를 결정하며, [DispatchMode]와는 독립적인 축이다. */
enum class GatewayMode {
    /** backend ← gate: 게이트가 백엔드로 접속해 들어온다. */
    SERVER,

    /** backend → gate: 백엔드가 게이트로 접속해 나간다. */
    CLIENT,
}

/**
 * `securance.server.*` 설정 프로퍼티(계획서 3.1/3.7절).
 *
 * 연결 방향(SERVER/CLIENT)과 확장성 관련 튜닝 값을 담는다.
 *
 * Opus 전체 리뷰 지적: 예전 주석은 제어 명령 전송 방식(QUEUED/DIRECT)이 `securance.control.dispatch-mode`
 * 설정으로 실제 토글 가능한 것처럼 적혀 있었지만, 그 설정을 바인딩하는 `@ConfigurationProperties`
 * 클래스가 애초에 존재하지 않아 값을 아무리 바꿔도 아무 효과가 없었다(운영자가 DIRECT로 바꿔도
 * 조용히 무시됨). 현재 실제 구현은 [kr.co.securance.secuhub.scheduler] 모듈의 `SendControlJob`
 * 폴링(큐 적재 → 주기 조회 → 전송) 한 가지뿐이라, 그 죽은 설정 항목은 application.yml에서 제거했다
 * (`docs/작업일지.md` 참고). DIRECT 경로가 실제로 필요해지면 그때 이 클래스에 새 프로퍼티를 추가한다.
 */
@ConfigurationProperties(prefix = "securance.server")
data class ServerModeConfig(
    /** SERVER | CLIENT — 계획서 3.1절. */
    val mode: GatewayMode = GatewayMode.SERVER,

    /** SERVER 모드에서 바인딩할 주소. */
    val host: String = "0.0.0.0",

    /** SERVER 모드에서 바인딩할 포트. */
    val port: Int = 28010,

    /** CLIENT 모드에서 게이트에 접속할 때 사용할 포트. */
    val clientPort: Int = 1005,

    /** CLIENT 모드에서 미연결 장비를 재확인하는 주기(초). */
    val clientReconnectIntervalSeconds: Long = 10,

    /** SERVER 모드 accept backlog — 레거시 `Listen(2048)` 대응(계획서 3.7절). */
    val acceptBacklog: Int = 2048,

    /**
     * 이 시간(초) 동안 소켓에서 아무것도 못 읽으면 죽은 커넥션으로 간주해 닫는다(적대적 리뷰 지적).
     * `SO_KEEPALIVE`만으로는 OS 기본 유휴시간(보통 2시간)이 지나야 감지되므로, half-open 커넥션
     * (케이블 단절/전원 차단/NAT 타임아웃)을 더 빨리 정리하려면 애플리케이션 레벨 read timeout이
     * 필요하다 — `GateTcpServer`가 [io.netty.handler.timeout.ReadTimeoutHandler]로 적용한다.
     */
    val idleTimeoutSeconds: Long = 90,

    /**
     * 커넥션당 액터(3.3절)가 공유할 코루틴 디스패처의 병렬도.
     * 기본값은 호출 시점의 CPU 코어 수 × 2 (계획서 3.7절).
     */
    val actorDispatcherParallelism: Int = Runtime.getRuntime().availableProcessors() * 2,

    /** 커넥션당 액터의 처리 대기열 최대 길이 — 초과 시 [kr.co.securance.secuhub.common.exception.GateTaskRejectedException]. */
    val actorQueueCapacity: Int = 2000,

    /** DB 비동기 쓰기 파이프라인 샤드 수(계획서 3.5/3.7절). 1,000+ 디바이스 규모에서는 16~32 권장. */
    val dbWriterShards: Int = 8,

    /**
     * 코드 리뷰 지적(2026-08-14): [io.netty.handler.timeout.ReadTimeoutHandler]는 인바운드(수신)
     * 유휴만 감지하고 아웃바운드(송신) 쓰기가 멈춘 경우는 잡지 못한다. 원격이 TCP 수신을 멈추거나
     * 소켓 송신 버퍼가 계속 가득 차 있으면 `outbound.sendByteArray(...)`가 완료되지 않아 해당
     * 커넥션의 [kr.co.securance.secuhub.server.connection.GateConnectionActor] 워커 코루틴이
     * 이 값을 넘겨 무기한 블로킹되고, 뒤이은 ACK 회신/제어 명령 전송이 전부 밀린다 — 이 시간(초)
     * 안에 소켓 쓰기가 끝나지 않으면 타임아웃시켜 연결을 닫는다(정리는 기존 `onDispose` 가드가
     * 담당 — [kr.co.securance.secuhub.server.tcp.GateTcpServer.registerDisposeGuard] 참고).
     */
    val writeTimeoutSeconds: Long = 15,
) {
    init {
        // 코드 리뷰 지적(2026-08-28): actorDispatcherParallelism은 사용처(GateTcpServer/GateTcpClient)에서
        // coerceAtLeast(1)로 방어되지만 dbWriterShards에는 동일한 방어가 없었다. 0으로 잘못 설정해도
        // 기동 자체는 성공(빈 샤드 리스트)해, 운영자가 첫 게이트 패킷이 들어와 GateDbWriteQueue의
        // `partitionKey.hashCode() % shardCount` 계산에서 ArithmeticException이 나서야 뒤늦게
        // 설정 실수를 알게 됐다 — RabbitCredentialGuard와 같은 취지로, 기동 시점에 즉시 막는다.
        require(dbWriterShards >= 1) { "securance.server.db-writer-shards는 1 이상이어야 합니다: $dbWriterShards" }

        // 코드 리뷰 지적(2026-08-28): actorQueueCapacity는 그대로 kotlinx.coroutines의
        // Channel(capacity=...)에 전달되는데, 그 생성자는 0(RENDEZVOUS)/-1(UNLIMITED)/-2(CONFLATED)를
        // 특수 의미로 해석한다. 0으로 설정하면 "제출 즉시 거부"가 아니라 RENDEZVOUS 채널(수신자가
        // 대기 중이 아니면 항상 실패)이 되어 거의 모든 패킷이 드롭되고, 음수로 설정하면 반대로
        // 무제한 큐가 되어 GateTaskRejectedException으로 보호하려던 백프레셔 설계 의도가 조용히
        // 무력화된다. 양의 정수만 "유한 버퍼" 의미로 쓰이도록 기동 시점에 막는다.
        require(actorQueueCapacity >= 1) {
            "securance.server.actor-queue-capacity는 1 이상이어야 합니다(0/음수는 kotlinx.coroutines " +
                "Channel의 특수 용량 값과 충돌합니다): $actorQueueCapacity"
        }
    }
}
