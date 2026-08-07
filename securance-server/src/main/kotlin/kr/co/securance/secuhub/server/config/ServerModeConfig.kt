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
    val port: Int = 9000,

    /** CLIENT 모드에서 게이트에 접속할 때 사용할 포트. */
    val clientPort: Int = 9000,

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
)
