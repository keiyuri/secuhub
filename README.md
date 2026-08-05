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
| `securance.server.mode` | `SERVER`(backend←gate) \| `CLIENT`(backend→gate) — 계획서 3.1절 |
| `securance.control.dispatch-mode` | `QUEUED` \| `DIRECT` — 프론트엔드 제어 명령 전송 방식. **연결 방향과 완전히 독립적인 축**(계획서 5.5절) |
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

## 1차 스캐폴드 범위 밖 (후속 작업)

계획서에서 명시적으로 1차 범위 밖으로 정한 항목들이다.

- **로그(Log) 처리 전체** — 레거시 `SR_Speed_Client`/`SR_Speed_Server` 모두 미구현 상태였으므로
  "이식"이 아니라 신규 설계가 필요하다(계획서 3.8절). 프로토콜 문서의 36바이트 로그 구조를
  기준으로 `LogEventCodec`(securance-protocol)과 `GateLogService`(securance-server)를 새로 만들 것.
- **Turn Gate / Fast Gate 프로토콜 코덱** — 규격 문서 미확보. `GateProtocolCodecRegistry`
  (securance-protocol)에 구현체만 추가하면 되는 확장 지점은 마련해 두었다.
- **DB 쓰기 파이프라인의 나머지 부분** — 1차는 `tb_net_state` 갱신 패턴만 `GateDbWriteQueue`로
  증명했다. `tb_data_rcv`/`tb_data_rcv_anal` 등 패킷 상세 저장은 동일 패턴으로 후속 추가.
- **`SendControlJob`/`ReqStatusJob`** — `NetCheckJob` 하나로 Quartz 잡 패턴을 증명했다. 나머지 잡은
  동일 구조(`GateConnectionRegistry`만 의존)로 추가.
- **레거시 저장 프로시저 나머지**(holiday/timezone/motor push 등) — 핵심 흐름(수신→분석→집계)만 우선 이식.
- **나머지 엔티티**(`tb_time`, `tb_calendar`, `tb_log`, `tb_data_rcv_motor/ctrl`, `tb_data_init` 등) —
  `V1__init_schema.sql`에 없음. `V2__...`로 동일 패턴 추가.
- **`GateControlService`(QUEUED/DIRECT 두 구현)** — 계획서 5.5절에 설계는 정리되어 있으나
  1차 스캐폴드 컨트롤러/서비스 구현은 아직 없음. 프론트엔드 제어 명령 API 작업 시 추가.
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
