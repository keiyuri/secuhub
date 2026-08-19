# secuhub (securance)

레거시 SpeedGate 생태계(`SR_Speed_Server` Windows 서비스 + `SR_Speed_Client` WinForms 모니터 +
MariaDB 저장 프로시저 파이프라인)를 대체하는 Kotlin/Spring Boot 웹 애플리케이션.

설계 배경과 근거는 [`.claude/plans/functional-forging-tulip.md`](.claude/plans/functional-forging-tulip.md)에
자세히 정리되어 있다(승인된 아키텍처 계획서). 이 문서는 "지금 이 저장소가 실제로 어떻게 생겼고,
어떻게 실행하는지"를 다룬다.

## 모듈 구조

```
secuhub/                          (rootProject.name — 원 요청 아티팩트명 유지)
├── securance-common/              게이트 타입 코드/공용 예외/16진 유틸 — Spring 비의존
├── securance-protocol/            SpeedGate 바이너리 프로토콜 코덱 — Spring 비의존
├── securance-domain/              JPA 엔티티/리포지토리 + Flyway 마이그레이션
├── securance-server/              게이트 TCP 연동 전용(Reactor Netty, 커넥션 액터) — Quartz 없음
├── securance-scheduler/           Quartz 잡 전용 — securance-server의 GateConnectionRegistry만 의존
├── securance-web/                 Spring MVC + Thymeleaf 대시보드
└── securance-app/                 위 전부를 조립해 기동하는 유일한 실행 모듈(bootJar)
```

각 모듈이 왜 이렇게 나뉘었는지, 게이트 연동/DB/웹 설계의 근거는 계획서 1~7절을 참고할 것.

## 빌드 / 실행 (Windows 기준, Docker 미사용)

```powershell
# 빌드
./scripts/build.ps1

# 로컬 실행 (application-local.yml 필요 — 아래 참고)
Copy-Item securance-app\src\main\resources\application-local.yml.example `
          securance-app\src\main\resources\application-local.yml
# application-local.yml을 열어 MariaDB 접속 정보를 채운 뒤:
./scripts/run-local.ps1
```

또는 Gradle 직접 사용:

```powershell
./gradlew.bat build                          # 전 모듈 컴파일 + 테스트
./gradlew.bat :securance-protocol:test        # 프로토콜 코덱 단위테스트만
./gradlew.bat :securance-app:bootRun          # 로컬 기동(기본 프로필)
./gradlew.bat :securance-app:bootJar          # 실행 가능 JAR 생성 (securance-app/build/libs/securance-app.jar)
```

### 정적 JS 회귀 테스트 (`securance-web/static/js/*.js`)

`securance-web/src/main/resources/static/js/`의 JS는 빌드 파이프라인이 없는 순정 `<script>` 태그
파일이다(번들러/모듈 시스템 없음). Gradle 빌드와는 독립적으로 Vitest + jsdom으로 회귀 테스트한다:

```powershell
cd securance-web
npm install   # 최초 1회
npm test      # vitest run
```

테스트 대상 JS 파일 맨 아래에는 `typeof module !== 'undefined'`로 가드된 테스트 전용 export
블록이 있다 — 브라우저의 `<script>` 로딩(`module` 전역 없음)에는 전혀 영향을 주지 않고, Node/
Vitest 환경에서만 내부 함수를 노출한다.

### Windows 서비스로 등록 (WinSW)

레거시 `SR_Speed_Server`가 Windows 서비스로 운영되던 것과 동일한 방식이다.

1. [WinSW 릴리스](https://github.com/winsw/winsw/releases)에서 실행 파일을 받아
   `scripts/winsw/securance-app-service.exe`로 저장 (라이선스상 이 저장소에는 포함하지 않음).
2. `./scripts/build.ps1` 로 `securance-app.jar`을 빌드하고 `scripts/winsw/`로 복사.
3. 관리자 권한 PowerShell에서 `./scripts/install-service.ps1 -Action install` 후 `-Action start`.

## 필수 사전 준비물

- **JDK 21**
- **MariaDB 10.11** (Windows 설치본 또는 기존 개발 서버) — `application-local.yml`에 접속 정보 설정
- RabbitMQ/Redis는 **선택**(`securance.messaging.rabbitmq.enabled` / `securance.cache.redis.enabled`,
  기본값 `false`) — 로컬 개발에는 필요 없음

## 핵심 설정 키 (`application.yml`)

| 키 | 설명 |
|---|---|
| `securance.server.mode` | `SERVER`(backend←gate)만 구현되어 있음. `CLIENT`(backend→gate)로 설정하면 `GateTcpServer`가 기동 자체를 실패시킨다 — 아직 구현체(`GateTcpClient`)가 없음(계획서 3.1절) |
| `securance.server.db-writer-shards` | DB 비동기 쓰기 파티션 수 (1,000+ 디바이스면 16~32 권장) |
| `securance.scheduler.job-concurrency` | Quartz 잡 팬아웃 동시성 상한 |
| `securance.messaging.rabbitmq.enabled` | RabbitMQ 활성화 여부 |
| `securance.cache.redis.enabled` | Redis 활성화 여부 |

## 검증한 것 / 검증하지 않은 것

- `./gradlew.bat build` — 전 모듈 컴파일 및 단위테스트 통과 확인 완료(체크섬/BCD 인코딩,
  프레임 재조립, 패킷 diff, 커넥션 액터 백프레셔, YnConverter 등).
- `:securance-app:bootJar` — 실행 가능 JAR 패키징 확인 완료.
- **실제 MariaDB에 대한 Flyway 마이그레이션 적용 / `bootRun` 기동은 이 환경에 DB가 없어 미검증** —
  로컬에서 `application-local.yml` 설정 후 `./scripts/run-local.ps1`로 직접 확인 필요.
- 1,000+ 동시 연결 부하 테스트(계획서 3.7/8절)는 미실시 — 별도 목(mock) TCP 클라이언트 도구로 검증 필요.

## 보안 전제 — 게이트 연결의 IP 기반 신뢰 모델

`GateTcpServer`는 접속해온 소스 IP가 `tb_gate_dtl`에 등록돼 있는지만 확인하고 연결을 수락한다
(토큰/인증서/공유키 등 애플리케이션 계층 인증 없음, [GateTcpServer.kt](securance-server/src/main/kotlin/kr/co/securance/secuhub/server/tcp/GateTcpServer.kt) 참고).
이는 **의도적인 설계 결정**이다 — 게이트 하드웨어(임베디드 장비)가 자체적으로 인증서/토큰을 지원하지
않는다는 전제 하에, IP 기반 신뢰 + 네트워크 계층 격리(방화벽/VLAN으로 게이트 세그먼트를 백엔드와
분리)로 충분하다고 판단했다(2026-08-05 코드 리뷰에서 재확인).

**따라서 배포 시 다음이 반드시 지켜져야 한다**:
- 게이트가 연결되는 네트워크 세그먼트는 신뢰할 수 없는 네트워크(인터넷, 일반 사무 LAN)와 분리되어야
  한다 — 그렇지 않으면 `tb_gate_dtl`에 등록된 IP를 스푸핑/탈취해 위조 패킷을 주입할 수 있다.
- 향후 게이트 하드웨어가 인증서/토큰을 지원하게 되면, `GateTcpServer.handleConnection`의
  게이트 조회 단계에 애플리케이션 계층 인증을 추가하는 것을 권장한다.

## 2차 스프린트 구현 범위

- **`SendControlJob` + QUEUED 디스패치** — `GateControlDispatcher`가 `tb_data_snd`를 폴링해
  전송하고, 장비 ACK(Object Code 0x4C)를 확인해 `chk_yn`을 확정한다. ACK 미수신 시
  `ack-timeout-seconds` 후 재전송하고 `max-send-attempts` 초과 시 실패(`chk_yn='F'`)로 확정한다.
- **`tb_data_rcv_anal` 적재** — `GateStatusAnalyzer`가 레인 상태 블록(74바이트)을 분석해
  장애/이벤트로 분류하고 `GatePacketPersister`가 적재한다. 레거시 DB 트리거
  `utrg_data_rcv_anlz`를 애플리케이션으로 끌어올린 것이며, 트리거가 1번 레인만 분석하던
  한계를 해소해 **모든 레인**을 분석한다.
- **리셋/장애 해제** — `GateFaultResolutionService`가 `resolve_yn`을 갱신한다.
  운영자 리셋(장비 ACK 확인 후)과 장비 자동 복구(장애 비트 해제 시) 두 경로를 모두 지원한다.
- **게이트 제어 화면** — `/gates/control`(Thymeleaf)에서 모드 변경/보안 등급/리셋을 실행한다.
- **수신 로그(GATE_LOG) 처리** — `LogEventCodec`/`GateLogService`가 GATE_STATUS에 실려오는 로그
  블록을 재조립해 저장하고, 수신 로그 조회 화면에서 확인할 수 있다.

## 1차 스캐폴드 범위 밖 (후속 작업)

계획서에서 명시적으로 1차 범위 밖으로 정한 항목들이다.

- ~~**로그(Log) 처리 전체**~~ — 레거시가 미구현이라 신규 설계가 필요했던 항목(계획서 3.8절).
  `LogEventCodec`(securance-protocol)과 `GateLogService`(securance-server)로 구현 완료.
- ~~**Turn Gate / Fast Gate 프로토콜 코덱**~~ — `FastGate Protocol Ver1_2020102601_01.md`
  (SmartGate Protocol) 규격 문서 확보 후 대조한 결과 Speed/Flap/Turn/Fast 4종 게이트가 완전히
  동일한 봉투를 쓴다는 것을 확인했다(`SpeedFlapGateProtocolCodec.supportedGateTypes` 4종 지원이
  이미 정답이었음). Fast Gate 전용 확장 Object Code `FAST_GATE_MOTOR`(0x50, 72바이트 Turn/Slide
  모터 페이로드)도 `FastGateMotorCodec`으로 구현 완료(2026-08-13) — 화면/서비스 연동은 아직 없음.
- **레거시 저장 프로시저 나머지**(holiday/timezone/motor push 등) — 핵심 흐름(수신→분석→집계)만 우선 이식.
- **나머지 엔티티**(`tb_time`, `tb_calendar`, `tb_log`, `tb_data_rcv_motor/ctrl`, `tb_data_init` 등) —
  `V1__init_schema.sql`에 없음. `V2__...`로 동일 패턴 추가.
- **스케줄/타임존/휴일/모터 설정 명령** — 제어 명령은 운영 모드 11종 + 리셋 5종 +
  보안 등급/시간 데이터 인코딩까지 구현했다(2차 스프린트). Object Code 0x54(TimeZone)/
  0x48(Holiday)/0x4B(Motor)를 쓰는 별도 설정 명령은 해당 화면 이식 시 추가.
- **위젯 추가/삭제 UI, 순서 영속화 API**(`/api/dashboard/widgets/reorder`) — 현재는 정적 배치.
- **React/Next.js 프론트엔드** — Thymeleaf 마크업은 adminlte-react와 동일 클래스 구조로 작성해
  이관 호환되게 해 두었다.
- **bootstrap-icons 폰트 자산** — 용량 문제로 1차 스캐폴드에는 포함하지 않음.
  [bootstrap-icons 릴리스](https://github.com/twbs/icons/releases)에서 `bootstrap-icons.min.css` +
  `fonts/`를 받아 `securance-web/src/main/resources/static/vendor/bootstrap-icons/`에 추가하면
  이미 작성된 `bi bi-*` 클래스가 그대로 동작한다.
- **Docker 컨테이너화** — 명시적으로 추후 과제(계획서 7.1절). 지금은 Windows 네이티브 실행/서비스
  등록까지만 다룬다.

## 게이트 타입 확장 (`tb_code`)

게이트 타입(1=Speed, 2=Flap, 3=Turn, 4=Fast)은 Kotlin enum이 아니라 `tb_code`(코드그룹
`GATE_TYPE`) 마스터 테이블로 관리한다. 신규 타입 추가는 코드 데이터만 INSERT하면 되고
애플리케이션 재배포가 필요 없다 — 단, 해당 타입의 프로토콜 코덱이 아직 없다면
`GateProtocolCodecRegistry`에 구현체를 등록해야 실제 연결이 가능하다(계획서 3.4/4.3절).
