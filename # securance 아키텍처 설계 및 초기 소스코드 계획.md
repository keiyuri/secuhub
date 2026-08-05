# securance 아키텍처 설계 및 초기 소스코드 계획

## Context

`securance`(아티팩트 원 요청명 `secuhub`)는 기존 .NET 기반 SpeedGate 생태계(`SR_Speed_Server` Windows 서비스 + `SR_Speed_Client` WinForms 모니터 + MariaDB 저장 프로시저 중심 파이프라인)를 대체할 Kotlin/Spring Boot 웹 애플리케이션이다. 목표는 (1) 쉽게 확장 가능한 아키텍처와 기본 소스코드를 만드는 것, (2) 프론트엔드는 우선 Thymeleaf로, 추후 React/Next.js로 이관 가능하도록 구조를 잡는 것, (3) 개발·운영 모두 **Windows 환경**에서 이루어지도록 하는 것이다(Docker는 현재 사용하지 않고 추후 도입 예정이므로, 로컬 실행/배포 방식은 Windows 네이티브로 설계).

세 개의 참조 프로젝트를 병렬 서브에이전트로 심층 분석했다:
- **`SR_Speed_Server`** (C#, 게이트 TCP 통신 백엔드) — 연결 관리, DB 쓰기 파이프라인, Quartz 잡, 프로토콜 처리 방식을 파악.
- **`92_DB_Script/20260805/securance_gate`** (MariaDB 스키마, 54개 SQL 파일) — 테이블/함수/프로시저/뷰 전체와 데이터 흐름(수신→분석→집계→백업) 파악.
- **`adminlte-react-0.3.0`** (React 컴포넌트 라이브러리) — AdminLTE 4 + Bootstrap 5.3 순정 마크업 위에 구축되어 있어, 위젯/레이아웃 DOM 구조를 Thymeleaf로 1:1 이식 가능함을 확인. 추후 React 전환 시 무변경 스왑 가능.

이 세 분석 결과를 바탕으로 신규 시스템의 모듈 구조, 게이트 연동 방식, DB 엔티티 설계, 대시보드 위젯 시스템을 아래와 같이 설계한다.

---

## 1. 프로젝트 구조 — Gradle 멀티모듈

- Kotlin DSL, JDK 21 툴체인, `group = "kr.co.securance"`, 루트 `rootProject.name = "secuhub"` (원 요청 아티팩트명 유지)
- 패키지 베이스: `kr.co.securance.secuhub`
- 하위 모듈명은 `securance-*` 프리픽스 사용(요청 반영: gateway → server, secuhub → securance). 즉 루트 프로젝트명(`secuhub`)과 하위 모듈 프리픽스(`securance-*`)가 다르다 — 의도된 설정.
- Version Catalog(`gradle/libs.versions.toml`)로 의존성 버전 통합 관리

```
secuhub/                          (rootProject.name)
├── settings.gradle.kts
├── build.gradle.kts            (공통 플러그인/설정)
├── gradle/libs.versions.toml
├── securance-common/            (Spring 비의존, 순수 Kotlin — 최소 공용 유틸)
├── securance-protocol/          (SpeedGate 바이너리 프로토콜 코덱 — common 의존, Spring 비의존)
├── securance-domain/            (JPA 엔티티/리포지토리 + Flyway)
├── securance-server/            (게이트 TCP 연동 전용: Netty 서버/클라이언트, 커넥션 레지스트리/액터 — 舊 gateway)
├── securance-scheduler/         (Quartz 잡 전용: NetCheckJob/SendControlJob/ReqStatusJob)
├── securance-web/               (Spring MVC + Thymeleaf)
└── securance-app/               (실행 가능한 부트 앱: server+scheduler+web 조립)
```

7개 모듈로 시작하되, `securance-app` 하나만 `bootJar`로 배포하여 **운영은 단일 프로세스**로 유지한다(1차 배포 단순화). 각 관심사(TCP 연동/스케줄링/프로토콜 코덱/웹)가 이미 물리적으로 분리된 모듈이므로, 트래픽이 커지면 코드 변경 없이 `securance-server`(혹은 `securance-scheduler`)만 별도 배포로 분리 가능 — "쉽게 확장 가능한 아키텍처" 요구사항 충족.

**모듈 분리 원칙** (요청 반영: 게이트 TCP 연동, Quartz 잡, 프로토콜 코덱을 각각 별도 모듈로 분리):
- `securance-protocol`: 프로토콜 상수/인코더·디코더/체크섬/패킷 diff — Spring/Netty에 의존하지 않는 순수 Kotlin이라 어떤 런타임(서버/배치/CLI 도구)에서도 재사용·단위테스트 가능.
- `securance-server`: 순수 TCP 연동(연결 수립/해제, 커넥션 레지스트리, 커넥션당 액터)만 담당 — Quartz/스케줄링 로직을 포함하지 않는다. 레거시 `SR_Speed_Server`와 대응하는 이름.
- `securance-scheduler`: Quartz 잡만 담당. `securance-server`가 노출하는 좁은 인터페이스(레거시 `IConnectionRegistry` 대응, 예: `GateConnectionRegistry`)를 통해서만 연결 상태에 접근 — 잡이 서버 내부 구현을 몰라도 되게 한다.
- 전체를 기동하는 실행 모듈은 이름 충돌을 피해 `securance-app`으로 명명(server/scheduler/web/domain을 조립).

---

## 2. 기술 스택 (요청 항목 반영)

| 항목 | 선택 |
|---|---|
| 언어/런타임 | Kotlin 2.x, JDK 21 |
| DB | MariaDB 10.11 (JDBC: `mariadb-java-client`), Flyway 마이그레이션 |
| 웹 | Spring Web(MVC) + Spring WebFlux(Reactor Netty — 게이트 TCP 서버/클라이언트 구현에 사용) |
| 배치 | Quartz (`spring-boot-starter-quartz`) |
| 메시징 | RabbitMQ (Spring AMQP) — `securance.messaging.rabbitmq.enabled` 설정으로 on/off |
| 캐시 | Redis (Spring Data Redis) — `securance.cache.redis.enabled` 설정으로 on/off |
| 인증 | Spring Security (폼 로그인, `tb_users` 기반) |
| 프론트 | Thymeleaf + Layout Dialect (1차), React/Next.js(추후, adminlte-react 마크업과 호환되게 설계) |
| 비동기 | kotlinx-coroutines (게이트 커넥션별 액터 모델) |
| Backend framework | Spring Boot 4.1 (Spring Framework 6.x) |

---

## 3. 게이트 연동 설계 (`securance-server` + `securance-scheduler` + `securance-protocol`) — SR_Speed_Server 분석 반영

### 3.1 모드 전환
- `securance.server.mode: SERVER | CLIENT` (설정 기반)
  - **SERVER** (backend ← gate): Reactor Netty `TcpServer` bind. 기존 `StartTcpListener`/`AcceptCallback` 패턴 대응.
  - **CLIENT** (backend → gate): `tb_gate_dtl`(use_yn='Y')을 IP 단위로 그룹핑 후 주기적으로 `TcpClient`로 연결 시도. 기존 `ClientModeMonitor`/`ConnectToAllDevices` 대응.

### 3.2 단일/다중 연결
- `tb_gate_grp.link_type` 컬럼(1=1:1, 2=1:N)이 이미 이 설정을 DB에 갖고 있음 — 신규 시스템에서도 이 컬럼을 그대로 사용.
- 실제로는 **IP당 TCP 연결 1개, 그 연결이 최대 32개 레인(lane) 상태를 실어나름**(프로토콜의 `LOCAL GATE LANE COUNT 0~32`, 74바이트 레인 블록). "multi = IP당 최대 32 connections"는 연결 개수가 아니라 **레인 개수**를 의미 — 설계서에 이 해석을 명시.
- `GateConnectionState.laneNumbers: MutableSet<Int>`로 레인 추적, `0x4D` 상태 패킷만 set을 authoritative하게 교체하고 그 외 패킷은 union-add만 허용 (C# `ClsAsyncObj.LaneNumbers`/`HasAuthoritativeLaneInfo` 로직 이식 — 레인 축소로 제어 명령이 유실되는 버그를 원천 차단).

**용어 정정**: 이 시스템에서 물리 장비를 가리키는 용어는 "게이트웨이(gateway)"가 아니라 **"게이트(gate)"**다 — 클래스/모듈/API 전반에서 `Gateway` 대신 `Gate`를 사용한다(예: `GateConnectionState`, `GateConnectionActor`, `GateControlService` 등, 이미 3절 전반에 반영됨).

**레인(Lane)과 물리적 게이트(Gate) 유닛의 관계** (도메인 규칙, 엔티티·시각화 설계에 반영 필요): N개 레인은 물리적으로 **N+1개의 게이트(차단바) 유닛**로 구성된다 — 인접한 두 레인이 차단바 하나를 공유하는 구조(펜스포스트 방식). 예: 1레인 = 게이트 2개, 5레인 = 게이트 6개, 32레인(프로토콜 최대치) = 게이트 33개. 즉 `gateCount = laneCount + 1`.
- `tb_gate_grp.lane_cnt`(레인 수)와 실제 물리적 게이트(차단바) 개수는 다르다는 점을 4.3절 엔티티 설계, 5.2절 맵/위젯 시각화(배치도 렌더링), 3.2절 레인 추적 로직 모두에서 명시적으로 구분해야 한다.
- `GateGroup` 엔티티에 파생 프로퍼티 `physicalGateCount = laneCount + 1`을 두거나, 뷰 계층에서 계산하도록 설계(저장하지 않고 계산값으로 유지 — 레인 수 변경 시 불일치 방지).

### 3.3 커넥션당 직렬 처리 (액터 모델)
- C# 분석에서 가장 핵심적인 설계: **커넥션 1개당 순차 처리 체인**(`ClsAsyncObj.EnqueueProcessing`)이 수신 파싱/ACK 송신/제어 송신/DB 콜백을 모두 직렬화하면서, 커넥션 간에는 병렬 처리.
- Kotlin 이식: 디바이스 IP(커넥션 키)마다 `Channel<GateTask>`(bounded capacity, `onBufferOverflow = SUSPEND` 또는 명시적 거부) + 전담 코루틴 1개. 백프레셔 초과 시 거부를 예외/실패로 명확히 표현(침묵 성공 금지 — C#의 `ChannelRejectedException` 대응).
- 재연결 경쟁 방지: 오프라인 상태 DB 반영 전 동일 IP의 더 최신 연결이 이미 있는지 확인(레이스 가드) — C#에서 반복 등장한 패턴, Kotlin에서도 동일 원칙 적용.

### 3.4 프로토콜 코덱 (`securance-protocol`) — 게이트 타입별 프로토콜 분리 필수

**중요한 도메인 사실**: 4.3절의 게이트 타입(1=Speed, 2=Flap, 3=Turn, 4=Fast) 중 **Speed Gate와 Flap Gate는 동일한 프로토콜**(`SpeedGate Protocol Ver1_20250813_01.md`에서 분석한 바로 그 프로토콜)을 쓰지만, **Turn Gate와 Fast Gate는 각각 별도의 프로토콜 규격**을 사용한다. 따라서 코덱을 `SpeedGate` 전용으로 단일화하지 않고 **게이트 타입별로 교체 가능한 구조**로 설계한다.

- `GateProtocolCodec` 인터페이스(공통 계약: `decode(ByteArray): GatePacket`, `encode(GateCommand): ByteArray`, `verifyChecksum(...)` 등) + `GateProtocolCodecRegistry`(게이트 타입 → 코덱 구현 매핑, `dtl_type` 기준 조회).
- `SpeedFlapGateProtocolCodec` — 분석 완료된 프로토콜 문서 기반 구현. **Speed Gate(1)와 Flap Gate(2) 둘 다 이 구현을 공유**한다(레거시도 동일 프로토콜을 사용했음을 DB 분석의 `desc_gate_type` 값 'SR-1400'/'Flap' 등에서 확인).
  - `SpeedGateProtocolConstants.kt` — `ClsConst.cs`를 포팅: 섹션 길이(Header 27/Tail 4/DataInfo 45/Status 74/Log 36), STX/ETX, CMD1/CMD2, Object Code(4C/4D/4B/54/48 등).
  - `SpeedGatePacketDecoder.kt` — Netty `ByteToMessageDecoder` 스타일. STX 스캔 → 2바이트 길이 필드 → ETX(08 03) 검증 → 불일치 시 1바이트씩 드롭하며 재동기화. C# `ExtractPackets` 로직 이식.
  - `SpeedGatePacketCodec.kt` — 체크섬(XOR+SUM), BCD 날짜 인코딩/디코딩, 헤더 빌더. `ClsCommon.MakeReqStatusDataWithDateTime`/`CheckData` 대응.
  - `PacketDiffer.kt` — 순수 함수로 이전/현재 패킷을 비교해 변경 여부 판단(`ClsPacketAnalyzer.AnalyzePacketChanges` 대응). 변경 없으면 ACK만, DB 쓰기는 스킵 — 이 최적화를 그대로 유지.
- `TurnGateProtocolCodec` / `FastGateProtocolCodec` — **1차 스캐폴드 미구현**. 해당 프로토콜 규격 문서가 아직 제공되지 않았으므로, 인터페이스만 정의해두고(`GateProtocolCodecRegistry`에 미등록 상태로 시작) 규격 문서 확보 후 후속 스프린트에서 구현. `securance-server`의 `GateConnectionActor`는 연결 수립 시 대상 게이트의 `dtl_type`으로 코덱을 조회하되, 등록되지 않은 타입은 명시적으로 "미지원 게이트 타입" 오류를 내고 연결을 거부하도록 설계(무음 실패 금지).
- 이 구조 덕분에 향후 게이트 타입이 추가되어도(4.3절) `GateProtocolCodecRegistry`에 새 구현체를 등록하는 것만으로 확장 가능 — 기존 SERVER/CLIENT 연결 로직, DB 쓰기 파이프라인(3.5절), Quartz 잡(3.6절)은 변경 불필요.

### 3.5 DB 쓰기 파이프라인
- 디바이스 IP로 파티셔닝된 N개의 워커 코루틴(기본 8개, `DatabaseWriterService`의 8-shard 큐 대응)으로 비동기 저장.
- "처리 중(pending) → 확정(committed)" 2단계 커밋: DB 쓰기 성공 후에만 `lastPacket` 갱신 — 실패 시 변경분 영구 유실 방지(원본의 `PendingRcvPacket`/`LastRcvPacket` 패턴).

### 3.6 Quartz 잡 (`securance-scheduler`, `securance-server`와 분리된 모듈)
- `NetCheckJob`(연결 생존 확인), `SendControlJob`(대기 중인 제어 명령 전송 — `securance.control.dispatch-mode=QUEUED`일 때만 활성, 5.5절 참고), `ReqStatusJob`(상태 폴링, `STATUS_REQ_TYPE != AUTOSEND`일 때만). 모두 `@DisallowConcurrentExecution`, 설정값으로 주기 조정.
- `securance-scheduler`는 `securance-server`가 노출하는 좁은 인터페이스(`GateConnectionRegistry` — 레거시 `IConnectionRegistry` 대응: 커넥션 조회/종료/제어 전송 메서드만 공개)에만 의존한다. 잡이 `GateConnectionActor`나 Netty 내부 구현을 직접 참조하지 않음 — 두 모듈 간 경계를 명확히 유지.
- (JVM에서는 불필요) `ClsQuartzJobGC` 같은 수동 GC 워치독은 이식하지 않음 — JVM GC가 이미 이를 담당.
- `SendControlJob`의 활성화 여부는 `securance.server.mode`(SERVER/CLIENT, 3.1절)와는 무관하다 — 두 설정은 완전히 독립적인 축이다(5.5절).

### 3.7 확장성 목표 — 동시 연결 1,000개 이상 지원

백엔드↔게이트 동시 연결 **1,000개 이상**을 명시적 목표로 설계·검증한다.

- **I/O 계층**: Reactor Netty(WebFlux 기반)는 NIO 이벤트루프(코어 수 비례, 보통 수 개~수십 개 스레드)로 소켓을 멀티플렉싱하므로, 연결 수가 늘어도 OS 스레드 수는 거의 늘지 않는다 — connection-per-thread 방식이 아님을 SERVER/CLIENT 양쪽 구현 모두에서 보장(레거시 C#의 APM 콜백 모델과 동일한 원리, JVM에서는 Reactor Netty가 표준으로 제공).
- **커넥션당 액터(3.3절)**: OS 스레드가 아닌 **경량 코루틴**으로 구현하므로 1,000개 이상의 `Channel`+코루틴이 공유 디스패처(스레드풀) 위에서 동작해도 메모리/컨텍스트 스위칭 비용이 낮음. 코루틴 디스패처 크기는 `securance.server.actor-dispatcher-parallelism`(기본값: CPU 코어 수 × 2)로 설정 가능하게 하여 부하 테스트로 튜닝.
- **커넥션 레지스트리**: `ConcurrentHashMap<String, GateConnectionState>` — O(1) 조회, 1,000+ 규모에서도 병목 아님(레거시 `ConcurrentDictionary` 대응).
- **Quartz 잡 팬아웃 동시성**: 레거시는 `SemaphoreSlim(16)`으로 고정돼 있었으나, 신규 시스템은 `securance.scheduler.job-concurrency`(기본값 64, 1,000+ 연결 규모에 맞춰 상향 조정 가능)로 설정화 — `NetCheckJob`/`ReqStatusJob`/`SendControlJob` 공통 적용(`securance-scheduler` 모듈 설정).
- **DB 쓰기 파이프라인(3.5절)**: 샤드 워커 수를 `securance.server.db-writer-shards`(기본 8, 1,000+ 디바이스 규모에서는 16~32 권장)로 설정화. 디바이스 연결 수와 DB 커넥션 풀 크기는 독립적임을 명시 — 쓰기는 공유 워커 풀을 통해서만 나가므로 디바이스 1,000개가 DB 커넥션 1,000개를 소비하지 않는다.
- **CLIENT 모드 아웃바운드 연결**: Windows에서 1,000+ 개의 아웃바운드 TCP 연결을 열 경우 임시 포트(ephemeral port) 고갈 가능성이 있으므로, Windows 레지스트리 `MaxUserPort`/`TcpTimedWaitDelay` 튜닝 가이드를 `README.md`/운영 문서에 명시(코드 변경 아님, 배포 체크리스트 항목).
- **SERVER 모드 accept backlog**: 레거시의 `Listen(2048)`에 대응해 Netty 서버 `SO_BACKLOG`를 설정 가능하게 노출(`securance.server.accept-backlog`, 기본 2048).
- 8절 검증에 1,000+ 동시 연결 부하 테스트를 추가한다.

### 3.8 로그 처리 — 레거시 미구현 기능, 신규 설계 필요

`SR_Speed_Client`(WinForms 모니터)와 `SR_Speed_Server`(TCP 백엔드) **양쪽 모두 로그(Log) 처리가 미완성 상태**임을 확인했다. 즉 이 영역은 "레거시를 그대로 이식"할 대상이 아니라 **secuhub/securance에서 새로 설계·구현해야 하는 기능**으로 취급한다.

- **서버 측**: `usp_rcv_log_anlz`/`tb_data_rcv_log`/`tb_log_seq` 경로(2절 DB 분석에서 확인된 로그 시퀀스 채번 + 이벤트 로그 파싱)와 `SpeedServer.ProcessReceiveData`의 로그 리전 파싱(패킷 내 log-count만큼의 36바이트 블록)은 존재하지만, 레거시 코드베이스 자체에서 완결된 기능으로 동작하지 않는다 — 신규 시스템에서 프로토콜 문서(로그 구조 36바이트: Event Type/Object Code/Code/Err Code/Operation Mode/Module Number/Reader Number/Door Status/Function Code/Event Time/User Data1·2)를 1차 소스로 삼아 `LogEventCodec`(`securance-protocol`)과 `GateLogService`(`securance-server`)를 새로 설계한다.
- **클라이언트(모니터 UI) 측**: `SR_Speed_Client`에도 로그 조회/표시 기능이 없거나 불완전 — `securance-web`의 대시보드/이벤트 화면(5.2절 위젯, `uvw_anlz_event` 대응)을 로그 뷰어의 신규 구현 대상으로 명시하고, 별도 "로그/이벤트 이력 조회" 화면(필터: 기간/게이트/이벤트타입)을 1차 스캐폴드 이후 후속 작업으로 추가한다.
- **영향**: 7절 "1차 범위에서 제외" 목록에 로그 처리 전체를 별도 항목으로 명시하고, 후속 스프린트에서 프로토콜 문서 기반 재설계 + 레거시 대비 회귀 없는 검증(실제 게이트 로그 샘플로 파싱 검증)을 진행한다.

---

## 4. DB 계층 (`securance-domain`) — 스키마 분석 반영

### 4.1 마이그레이션
- Flyway `V1__init_schema.sql`을 `securance_gate` 54개 SQL 파일 기준으로 포팅.
- 레거시는 FK 없이 논리적 관계만 존재(`FOREIGN_KEY_CHECKS=0`) → 신규 스키마에서는 `tb_gate_loc → tb_gate_grp → tb_gate_dtl` 계층에 실제 FK 추가.
- `char(1)` Y/N 컬럼은 `AttributeConverter<Boolean, String>`(`YnConverter`)로 매핑. 단 `tb_code.use_yn`은 `tinyint(1)`이라 예외 처리 필요.
- `tb_data_rcv_anal`의 2개 생성 컬럼(`has_status_event`, `has_error_event`)은 네이티브 generated column으로 유지, JPA에서는 `insertable=false updatable=false`로 읽기 전용 매핑.

### 4.2 엔티티 설계상 결정 (명시적 이견 수렴 지점)
- **`tb_data_rcv_anal_evt/_plm/_state`는 통합하지 않고 유지**할지, 또는 **단일 `tb_data_rcv_anal` + `anal_tp` 구분자로 통합**할지 — 원본은 4개 테이블에 중복 저장(샤딩+통합 테이블 이중 기록). 신규 시스템은 **통합 테이블 하나만 사용**(중복 저장 제거, 조회는 `anal_tp` 인덱스로 커버)을 기본안으로 제안.
- **레거시/중복 테이블 미이식**: `tb_log_event`(= `tb_log` 중복), `tb_data_rcv_state`(= `tb_data_rcv_anal_state`로 대체됨).
- 자연키 `(dtl_ip, dtl_lane_no)`가 실제 조인 키로 쓰이는 경우가 많음(뷰들이 `dtl_id`가 아닌 이 튜플로 조인) → 엔티티에도 `@Table(uniqueConstraints=...)`로 이 자연키를 보존.

### 4.3 대표 엔티티 (1차 스캐폴드에 포함)
`GateLocation`(tb_gate_loc), `GateGroup`(tb_gate_grp, `linkType` 필드로 3.2절 단일/다중 표현), `GateDetail`(tb_gate_dtl), `CodeMaster`(tb_code — 게이트 타입 등 확장 가능 코드값), `NetState`(tb_net_state), `DataReceive`/`DataReceiveAck`/`DataReceiveFail`, `DataReceiveAnalysis`(통합), `DataSend`, `OprStatus`, `AppUser`(tb_users). 나머지 테이블(tb_time, tb_calendar, tb_log, tb_data_rcv_motor/ctrl, tb_data_init 등)은 동일 패턴으로 후속 추가 — 계획서에 목록만 남기고 1차 스캐폴드에서 전부 구현하지 않음.

**게이트 타입(`dtl_type`) — 확장 가능한 코드값으로 설계**: 현재 1=Speed Gate, 2=Flap Gate, 3=Turn Gate, 4=Fast Gate로 분류되며, 향후 타입이 추가될 수 있음이 확정 요구사항이다. 따라서 `dtl_type`을 하드코딩 Kotlin `enum class`로 고정하지 않고, **`tb_code` 마스터 테이블(코드그룹 예: `GATE_TYPE`)을 통해 관리**한다.
- `GateDetail.dtlType: Int`(원시 코드값, DB 컬럼 그대로) + `CodeMaster` 조인으로 표시명(`code_nm`)·아이콘 등 부가정보 조회 — 신규 게이트 타입 추가 시 코드 데이터만 INSERT하면 되고 애플리케이션 재배포가 필요 없다.
- 코드 상수(1/2/3/4)는 `securance-protocol`의 `SpeedGateProtocolConstants`에도 프로토콜 레벨 상수로 동일하게 유지(프로토콜 자체는 고정 바이트 값을 쓰므로) — 단, UI 표시/필터링/위젯(5.2절)에서는 항상 `tb_code` 조회 결과를 사용해 신규 타입이 코드 변경 없이 자동 노출되도록 설계.
- 1차 스캐폴드에는 `tb_code` 초기 데이터(V1 마이그레이션의 시드 데이터)로 4종 게이트 타입을 등록하고, `GateDetail` ↔ `CodeMaster` 조인 조회 예시 1개를 포함한다.

### 4.4 뷰 → 서비스 계층
- `uvw_anlz_error/event/problem`, `uvw_snd_control`, `uvw_user_cnt`는 DB 뷰 대신 리포지토리 `@Query` + 서비스 레이어 로직으로 이식(테스트 용이성, DB 이식성 확보). 1차 스캐폴드에서는 `uvw_anlz_error` 대응 쿼리 1개만 구현해 패턴 증명, 나머지는 동일 방식으로 후속.

### 4.5 보관/백업
- `EVT_DATA_RCV_DEL` 이벤트 + `usp_backup_*`(동적 `CREATE TABLE ... SELECT`) 대신 `securance-scheduler` 내 Quartz 잡(`RetentionJob`)으로 이관, MariaDB 네이티브 `PARTITION BY RANGE` 적용 검토(1차는 기존 방식대로 삭제+아카이브 잡만 구현, 파티셔닝은 후속 과제로 명시).

---

## 5. 웹/대시보드 (`securance-web`) — adminlte-react 분석 반영

### 5.1 레이아웃 프래그먼트
adminlte-react가 순정 AdminLTE 4 / Bootstrap 5.3 마크업만 사용함을 확인했으므로, 동일 클래스 구조를 Thymeleaf 프래그먼트로 그대로 작성 — 추후 React 전환 시 시각적 변경 없이 컴포넌트만 스왑 가능.

- `layout/head.html`, `layout/topbar.html`, `layout/sidebar.html`(재귀 메뉴 프래그먼트, `List<MenuNode>` 모델), `layout/footer.html`, `layout/content.html`(`app-content-header` + `app-content`)
- Body 클래스 계약 그대로 사용: `layout-fixed`, `sidebar-expand-lg`, `fixed-header`, `sidebar-collapse`, `sidebar-mini` 등.
- 정적 자산은 CDN이 아닌 로컬 번들: `adminlte.css`(4.1.x), `bootstrap.bundle.min.js`, `bootstrap-icons`, `sortablejs` → `src/main/resources/static/vendor/`.
- 다크모드: `<html data-bs-theme="light|dark">` + `localStorage['lte-theme']`(React 라이브러리와 동일 키 사용 — 이관 호환성).
- 카드 접기/삭제/최대화는 **자체 JS 재구현 없이 순정 `adminlte.js`의 `data-lte-toggle`** 사용 (React 라이브러리는 이를 재구현했지만, Thymeleaf는 그럴 필요 없음).

### 5.2 위젯 시스템 (요청 15번: "위젯 추가/수정 가능한 관리자 대시보드")
- 위젯 프래그먼트: `widgets/small-box.html`, `widgets/info-box.html`, `widgets/card.html` — adminlte-react의 props 시그니처(title/text/icon/theme/url 등)를 Thymeleaf 프래그먼트 파라미터로 그대로 이식.
- `DashboardWidget` 엔티티(위젯ID, 소유자/역할, 위젯타입, 컬럼, 정렬순서, 접힘여부, config JSON) + `WidgetRegistry`(위젯타입 → 프래그먼트 + 데이터 서비스 매핑).
- 배치/순서 변경: SortableJS(`handle:'.card-header'`, `group:'shared'`, 컬럼은 `.connectedSortable`) — adminlte-react와 동일 패턴. `/api/dashboard/widgets/reorder` REST 엔드포인트로 순서 영속화.
- 1차 위젯 종류: 게이트 상태 요약(`uvw_anlz_error` 대응), 통행량 카운트(`uvw_user_cnt` 대응), 최근 알람, 연결 상태 개요.

### 5.3 실시간 갱신
- `GateEventPublisher` 인터페이스 + 2개 구현체: `RabbitGateEventPublisher`(RabbitMQ 활성 시), `LocalGateEventPublisher`(비활성 시 인프로세스 이벤트버스) — `@ConditionalOnProperty`로 선택.
- WebSocket/STOMP 엔드포인트로 대시보드에 실시간 알람/상태 push.

### 5.4 인증
- Spring Security 폼 로그인, `tb_users`의 `auth_view/auth_ctrl/auth_admin`(Y/N)을 역할로 매핑.

### 5.5 게이트 제어 명령 흐름 — 연결 모드와 완전히 독립된 별도 설정

**중요한 설계 원칙**: "백엔드↔게이트 통신 모드"(`securance.server.mode`: SERVER|CLIENT, 3.1절 — 어느 쪽이 TCP 연결을 여는가)와 "프론트엔드의 게이트 제어 명령 전송 모드"는 **서로 다른 관심사이며 독립적으로 설정·운영**한다. 하나가 다른 하나를 결정하지 않는다 — 예를 들어 백엔드가 CLIENT 모드(게이트로 접속해 나감)여도 제어 명령은 큐잉 방식으로 운영할 수 있고, 반대로 SERVER 모드여도 즉시 전송 방식을 쓸 수 있다.

별도 설정 키를 둔다: `securance.control.dispatch-mode: QUEUED | DIRECT`

- **QUEUED** (`프론트엔드 → DB 저장 → 백엔드 → 게이트`):
  `프론트엔드 → (REST) securance-web → tb_data_snd INSERT(snd_yn='N', chk_yn='N') → securance-scheduler의 SendControlJob(3.6절, Quartz 폴링) → GateConnectionRegistry로 대상 디바이스의 활성 커넥션(연결 모드가 SERVER든 CLIENT든 무관) 조회/매칭 후 게이트로 전송 → 응답 수신 시 tb_data_snd UPDATE + tb_data_rcv_ack 저장`.
  레거시 `ClsQuartzJobSendControl`/`uvw_snd_control` 패턴 계승.
- **DIRECT** (`프론트엔드 → 게이트 → DB 저장`):
  `프론트엔드 → (REST) securance-web → securance-server 내부 호출/REST(GateControlService.sendAndAwait) → 대상 디바이스의 GateConnectionActor(3.3절) 커넥션 체인에 즉시 enqueue(연결 모드 무관, 이미 맺어진 소켓을 그대로 사용) → 게이트 응답 대기(타임아웃 적용) → 성공 시 tb_data_snd/tb_data_rcv_ack에 결과 저장 후 응답 반환`.
- 두 경로 모두 최종적으로 `tb_data_snd`(및 필요 시 `tb_data_rcv_ack`)에 기록되어 감사/이력 추적 가능. `GateControlService` 도메인 인터페이스 뒤에 `QueuedGateControlService` / `DirectGateControlService` 두 구현을 두고 `securance.control.dispatch-mode` 값으로 `@ConditionalOnProperty` 선택 — 컨트롤러/프론트엔드 코드는 어떤 구현이 활성인지 몰라도 되게 한다.
- `SendControlJob`(QUEUED 전용)은 `securance.control.dispatch-mode=QUEUED`일 때만 스케줄되며, `securance.server.mode`(SERVER/CLIENT) 값과 무관하게 동작한다 — 두 축이 완전히 직교(orthogonal)함을 코드 구조로도 보장.

---

## 6. 설정 기반 옵션 (요청 14번: Redis/RabbitMQ는 설정으로 결정)

```yaml
securance:
  server:
    mode: SERVER               # SERVER | CLIENT — 백엔드↔게이트 TCP 연결 방향
  control:
    dispatch-mode: QUEUED      # QUEUED | DIRECT — 프론트엔드 제어 명령 전송 방식(연결 방향과 독립, 5.5절)
  messaging:
    rabbitmq:
      enabled: false
  cache:
    redis:
      enabled: false
```
- 의존성(스타터)은 항상 클래스패스에 포함하고, `@ConditionalOnProperty`로 빈 활성화 여부만 제어 — 재배포 없이 설정 변경만으로 on/off 전환 가능.
- DB 자격증명은 레거시(ini 평문)와 달리 환경변수/Spring Config로 외부화 — 명시적 개선사항.

---

## 7. 1차 스캐폴드 산출물 (계획 승인 후 실제 생성)

1. 루트 `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, 7개 모듈 `build.gradle.kts`
2. `securance-common`: 공용 유틸/예외/설정 프로퍼티 클래스(프로토콜 코드는 포함하지 않음)
3. `securance-protocol`: `SpeedGateProtocolConstants.kt`, `SpeedGatePacketCodec.kt`, `SpeedGatePacketDecoder.kt`, `PacketDiffer.kt` + 단위테스트(프로토콜 문서의 예시 바이트 배열 기반 라운드트립 테스트)
4. `securance-domain`: Flyway `V1__init_schema.sql`(4.3절 대표 엔티티 대응 테이블), 대표 엔티티/리포지토리 5~6개
5. `securance-server`: `ServerModeConfig`, `GateTcpServer`(Reactor Netty 스켈레톤), `GateConnectionActor`, `GateConnectionRegistry`(scheduler에 노출할 인터페이스) — Quartz 의존성 없음
6. `securance-scheduler`: `NetCheckJob`(Quartz 잡 패턴의 완전한 예시 1개), `securance-server`의 `GateConnectionRegistry`만 의존
7. `securance-web`: `DashboardController`, 레이아웃/위젯 프래그먼트, `SecurityConfig`
8. `securance-app`: `SecuranceApplication.kt`, `application.yml`(모든 설정 키 문서화 포함)
9. Windows 로컬 개발/배포 스크립트(Docker 미사용, 7.1절 참고)
10. `README.md`: 모듈 경계 설명, 실행 방법(Windows 기준), 후속 작업 목록(나머지 엔티티/프로시저 이식, React 전환 매핑표)

### 7.1 Windows 네이티브 개발/운영 (Docker 미사용)

레거시 `SR_Speed_Server`가 Windows 서비스(`ServiceBase` + `ProjectInstaller.cs`)로 운영되던 것과 동일한 운영 방식을 유지한다. Docker는 추후 도입 예정이므로 1차 산출물은 Docker 의존을 두지 않는다.

- **로컬 개발 DB**: `docker-compose.yml` 대신 Windows용 MariaDB 10.11 MSI(또는 이미 운영 중인 `192.168.0.91` 개발 DB)에 직접 연결. `application-local.yml`에 접속 정보 분리.
- **RabbitMQ/Redis(옵션)**: 기본값 `enabled: false`이므로 로컬 개발에는 필요 없음. 필요 시 Windows용 설치본(RabbitMQ Windows installer, Redis는 공식 Windows 미지원이므로 Memurai 또는 WSL 사용)을 안내 문서에 기재하되, 1차 스캐폴드 실행에는 영향 없음.
- **빌드 산출물**: `./gradlew :securance-app:bootJar` → 단일 실행 가능 JAR.
- **Windows 서비스 등록**: [WinSW](https://github.com/winsw/winsw)(레거시가 사용한 `ServiceBase` 계열과 동등한 표준 방식)로 `securance-app.jar`를 Windows 서비스로 래핑.
  - `securance-app-service.xml`(WinSW 설정: `java -jar securance-app.jar --spring.profiles.active=prod`, 로그 경로, 자동 재시작 정책)을 산출물에 포함.
  - 설치: `securance-app-service.exe install` / 시작: `securance-app-service.exe start` — 레거시 `ProjectInstaller.cs`가 하던 역할을 대체.
- **PowerShell 스크립트**: `scripts/run-local.ps1`(로컬 실행), `scripts/build.ps1`(빌드), `scripts/install-service.ps1`(WinSW 서비스 설치)을 1차 산출물에 포함해 Bash/Docker 없이도 전 과정을 Windows에서 수행 가능하게 함.

### 1차 범위에서 제외(명시)
- 레거시 저장 프로시저 전체 1:1 포팅 — 핵심 흐름(수신→분석→집계)만 우선 이식, 나머지(holiday/timezone/motor push)는 동일 패턴으로 후속.
- **로그(Log) 처리 전체**(3.8절) — 레거시가 미구현 상태이므로 "이식"이 아닌 "신규 설계"가 필요. 1차 스캐폴드에는 포함하지 않고, 프로토콜 문서 기반 재설계를 후속 스프린트에서 별도로 진행.
- **Turn Gate / Fast Gate 프로토콜 코덱**(3.4절) — 규격 문서 미확보로 1차 스캐폴드는 Speed/Flap Gate(공유 프로토콜)만 구현. `GateProtocolCodecRegistry` 확장 지점만 마련.
- React/Next.js 프론트엔드 — 요청대로 추후 진행, 단 Thymeleaf 마크업은 이관 호환되게 작성.
- 위젯/권한 관리 UI — Security는 연동하되 관리 화면은 후속.
- Docker/컨테이너화 — 명시적으로 추후 과제. 1차는 Windows 네이티브 실행/서비스 등록까지만.

---

## 8. 검증 방법 (Windows 기준)

- `./gradlew.bat build` (PowerShell) — 전 모듈 컴파일.
- `./gradlew.bat :securance-protocol:test` — 프로토콜 코덱 라운드트립 테스트(체크섬, BCD 날짜 인코딩 등 프로토콜 문서 예시값 기준).
- 로컬/개발 MariaDB(Windows 설치본 또는 기존 개발 서버)에 대해 Flyway 마이그레이션이 깨끗하게 적용되는지 확인(`./gradlew.bat :securance-domain:flywayMigrate` 또는 앱 기동 시 자동 적용).
- `./gradlew.bat :securance-app:bootRun` (`securance.server.mode=SERVER`, rabbitmq/redis `enabled=false`)로 기동 후 `/dashboard`가 위젯 레이아웃과 함께 렌더링되는지 확인(데이터 없으면 빈 상태로).
- **동시 연결 부하 테스트**(3.7절 목표 검증): 간단한 목(mock) TCP 클라이언트 도구로 1,000개 이상의 동시 연결을 `securance-server`(SERVER 모드)에 붙여 연결 성립/유지/`NetCheckJob` 생존 확인이 정상 동작하는지, 힙/스레드 사용량이 안정적인지 확인. CLIENT 모드는 동일 도구를 목 게이트 서버로 띄워 1,000+ 아웃바운드 연결을 검증.
- WinSW로 패키징한 서비스가 `services.msc`에 등록되고 정상 시작/중지되는지 확인.