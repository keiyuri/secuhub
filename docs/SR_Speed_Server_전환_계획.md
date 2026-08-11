# SR_Speed_Server 전환 계획

레거시 윈도우 서비스(`D:\workspace\vstudio\Maintenance\GateControl\SR_Speed_Server`, C# .NET
Framework 4.8, 총 8,975줄)의 기능을 신규 `secuhub`(Kotlin/Spring Boot 멀티모듈)로 옮기는 작업의
현황과 남은 범위를 정리한 문서. `SR_Speed_Client_전환_계획.md`와 같은 형식이다.

**이 문서의 성격**: 클라이언트 계획서와 달리 서버 측은 **계획서 없이 먼저 구현이 진행됐다**
(`# securance 아키텍처 설계 및 초기 소스코드 계획.md` 3절이 사실상의 설계서 역할을 했고, 실제
이력은 `작업일지.md`에 산발적으로 기록됨). 따라서 이 문서는 "앞으로 무엇을 할지"보다 **이미
구현된 것을 실측으로 확정하고, 누락분을 드러내는 것**에 무게를 둔다 — 2026.08.11 분석 세션에서
레거시 원본과 신규 코드를 1:1 대조한 결과다.

## 1. 현재 상태 요약

| 구분 | 건수 | 비고 |
| --- | --- | --- |
| 완전 전환 | 16 | TCP 수신·분석·ACK·DB 파이프라인·Quartz 잡 3종 + 모터/스케줄/휴일 수신 저장 등 핵심 경로 |
| 부분 전환 | 1 | 게이트 타입별 코덱 분리(P9, Turn/Fast 문서-코드 불일치) |
| 미전환 | 1 | CLIENT 모드 |
| 의도적 제외 | 3 | 메모리 GC 워치독, INI 설정, 서비스 인스톨러. UDP 에코(D2)도 제외 확정(3절 참고) |
| 레거시 초과(신규) | 1 | `GATE_LOG`(0x61) 로그 파싱 — 레거시 미구현분을 신규 설계 |

**전환률: 약 94%** (완전 16 + 부분 0.5 / 전체 17.5, 의도적 제외 4건은 분모에서 제외).

> **2026-08-11 정정**: 최초 분석에서 R3(모터)/R4(스케줄)/R5(휴일) 수신 저장을 "미전환"으로
> 잘못 판정했다(아래 정오표 참고). `GatePacketPersister.persistReceivedPacket`을 실제로
> 읽지 않고 표면적인 "레거시는 전용 테이블 3개를 쓰는데 신규는 안 보인다"는 인상만으로
> 결론 내린 것이 원인이다. 실제로는 `DefaultGatePacketHandler`의 `else` 분기(모든 비상태·
> 비로그 패킷)가 이미 세 오브젝트 코드 전부를 `tb_data_rcv`에 적재하고 있었고, 이 코드는
> 2차 스프린트 커밋(`0d28faf`, 이 문서 최초 작성보다 먼저 병합됨)에 이미 존재했다. 정정
> 전 문서를 근거로 사용자가 "모터+스케줄 구현" 결정을 내렸으나, 실제로는 추가 구현이
> 필요 없어 그 결정을 취소하고 문서만 정정한다.

## 2. 컴포넌트 인벤토리 및 매핑

범례 — 상태: **완료** / **부분**(빠진 내용 명시) / **미전환** / **제외**(사유 명시)

### 2.1 서비스 수명주기 · 네트워크 계층

| # | 레거시 | 위치 | 기능 | 상태 | 신규 대응 |
| --- | --- | --- | --- | --- | --- |
| S1 | `SpeedServer.OnStart/OnStop` | `SpeedServer.cs:267/361` | 서비스 기동·종료, 리스너/스케줄러/DB Writer 순차 기동 | **완료** | Spring Boot 기동 + `GateServerConfiguration` |
| S2 | `StartTcpListener`/`AcceptCallback`/`HandleNewClient` | `SpeedServer.cs:426/476/524` | TCP 리스너 bind, accept 콜백, IP별 커넥션 등록(`_ipConnectLocks`로 동일 IP 재연결 경쟁 차단) | **완료** | `GateTcpServer.kt` (Reactor Netty) |
| S3 | `ClientModeMonitor`/`ConnectToAllDevices`/`ConnectToDevice` | `SpeedServer.cs:1388/1431/1495` (약 290줄) | **CLIENT 모드** — 백엔드가 `tb_gate_dtl`(use_yn='Y')을 읽어 게이트로 아웃바운드 접속 | **미전환** | 없음. `GateTcpServer.start()`가 `check(mode == SERVER)`로 **기동 실패**시킴 |
| S4 | `OnUDPServer` | `SpeedServer.cs:1677` | `GetServerPort`에서 UDP 데이터그램을 그대로 되돌려주는 **에코 응답기**(생존 확인용) | **미전환** | 없음 — 3절 D2 참고 |
| S5 | `SetKeepAlive` | `SpeedServer.cs:1314` | 소켓 TCP KeepAlive 설정 | **완료** | Netty 채널 옵션 |
| S6 | `CloseClientSocket` | `SpeedServer.cs:1251` | 연결 종료 + 오프라인 DB 반영(더 최신 연결 존재 시 반영 생략하는 레이스 가드 포함) | **완료** | `GateConnectionRegistryImpl` |
| S7 | `IConnectionRegistry` | `IConnectionRegistry.cs` | 스케줄러가 커넥션에 접근하는 좁은 인터페이스(조회/종료/전송) | **완료** | `GateConnectionRegistry.kt` |
| S8 | `ClsAsyncObj` | `ClsAsyncObj.cs` | 커넥션당 상태 + 순차 처리 체인(`EnqueueProcessing`), `LaneNumbers`/`HasAuthoritativeLaneInfo` | **완료** | `GateConnectionState.kt` + `GateConnectionActor.kt`(코루틴 액터) |

### 2.2 프로토콜 · 패킷 처리

| # | 레거시 | 위치 | 기능 | 상태 | 신규 대응 |
| --- | --- | --- | --- | --- | --- |
| P1 | `ExtractPackets` | `SpeedServer.cs:658` | STX 스캔 → 길이 필드 → ETX 검증 → 불일치 시 1바이트 드롭 재동기화 | **완료** | `SpeedGatePacketReassembler.kt` |
| P2 | `ClsPacketAnalyzer.AnalyzePacketChanges` | `ClsPacketAnalyzer.cs` | Header/Info/Status/Tail 변경 감지(변경 없으면 DB 쓰기 스킵) | **완료** | `PacketDiffer.diff` |
| P3 | `CheckStatusSubSections` | 〃 | 레인 상태 블록 하위 구간별 변경 플래그 | **완료** | `PacketDiffer.diffLane` |
| P4 | `IsBytesEqual` | 〃 | 바이트 구간 비교 유틸 | **완료** | `PacketDiffer.regionEquals` |
| P5 | `IsNotConnected` | 〃 | 레인 74바이트 중 레인번호·에러코드 제외 72바이트 전부 0 → 물리 센서 미연결 판정 | **완료** | `PacketDiffer.isLaneConnected` — 2026-08-07(6)에 **미이식 발견 후 조치** |
| P6 | `ClsCommon.MakeReqStatusDataWithDateTime`/`CheckData` | `ClsCommon.cs` | 헤더 빌더, 체크섬(XOR+SUM), BCD 날짜 인코딩 | **완료** | `SpeedGatePacketCodec.kt` |
| P7 | `ClsConst` 상수 | `ClsConst.cs` | 섹션 길이(Header 27/Tail 4/DataInfo 45/Status 74/Log 36), STX/ETX, CMD1/CMD2, Object Code | **완료** | `SpeedGateProtocolConstants.kt` |
| P8 | `SendAckData` | `SpeedServer.cs:1337` | 수신 패킷에 대한 ACK 응답 송신 | **완료** | `DefaultGatePacketHandler`(ACK 경로) |
| P9 | 게이트 타입별 코덱 분리 | — | Speed/Flap 공용, Turn/Fast는 별도 규격 | **부분** | `GateProtocolCodecRegistry` + `SpeedFlapGateProtocolCodec` 1개만 존재. 이 코덱이 `supportedGateTypes`에 **4종(Speed/Flap/Turn/Fast) 전부를 등록**하고 있어, 설계서 3.4절 및 `GateTypeCodes.kt` 주석의 "Turn/Fast 미구현" 서술과 코드가 어긋난다 — 3절 D4 참고 |
| P10 | Object Code 정의 범위 | `ClsConst.cs` | — | **부분** | `SpeedGateProtocolConstants.ObjectCode`에 **15종 상수**가 정의돼 있으나 실제 인코딩/파싱 구현이 있는 것은 **0x4D/0x4C/0x4B/0x46/0x54/0x61의 6종**뿐. 나머지 9종(0x4E/0x47/0x4F/0x52/0x55/0x48/0x57/0x50 등)은 **상수만 있고 빌더·파서 없음** |
| P11 | `FAST_GATE_MOTOR`(0x50) | — | FastGate 모터 설정 | **미전환** | 상수만 정의, 페이로드 코덱 없음 |

### 2.3 수신 데이터 처리 — **가장 큰 누락 지점**

레거시 `SpeedServer.ProcessReceiveData`(`SpeedServer.cs:803`)의 `switch`는 오브젝트 코드
**5종**을 처리한다. 신규 `DefaultGatePacketHandler`의 분기는 **3종**뿐이다.

| # | Object Code | 레거시 처리 | 상태 | 신규 대응 |
| --- | --- | --- | --- | --- |
| R1 | `0x4D` Gate Status | `InsertReceiveStatusData` → `tb_data_rcv`/`tb_data_rcv_anal`/`tb_net_state`/`tb_opr_status` | **완료** | `DefaultGatePacketHandler` + `GatePacketPersister` + `GateStatusAnalyzer` |
| R2 | `0x4C` Setting Data | 설정 응답 수신 · 제어 명령 ACK | **완료** | `DefaultGatePacketHandler` (ACK 큐 소비는 `0x4C`에서만) |
| R3 | `0x4B` Motor Data | `InsertReceiveMotorData` (`ClsMariaDB.cs:568`) → **`TB_DATA_RCV_MOTOR`**(`motor_header`/`motor_data`/`motor_tail` 분리 저장) | **완료(2026-08-11 정정)** | `GatePacketPersister.persistReceivedPacket`의 `else` 분기(`GATE_MOTOR`) → `tb_data_rcv`에 header/data/tail 원시 적재. 레거시의 전용 `TB_DATA_RCV_MOTOR` 테이블 대신 R1과 같은 `tb_data_rcv`로 통합(의도적 차이, 아래 정오표 참고) |
| R4 | `0x54` Schedule/TimeZone | `InsertReceiveScheduleData` (`ClsMariaDB.cs:573`) → `TB_DATA_RCV` | **완료(2026-08-11 정정)** | 〃(`TIME_ZONE` 분기) — 레거시도 이 오브젝트 코드는 애초에 `TB_DATA_RCV`를 썼으므로 테이블 자체는 레거시와 동일 |
| R5 | `0x48` Holiday | `InsertReceiveHolidayData` (`ClsMariaDB.cs:578`) → `TB_DATA_RCV` | **완료(2026-08-11 정정)** | 〃(`HOLIDAY` 분기) — 클라이언트 #14 Holiday 제외 결정(화면 자체가 스텁)과 무관하게 수신 저장은 이미 구현되어 있었다 |
| R6 | `0x61` Gate Log | 레거시는 **로그를 별도 Object Code로 받지 않았다** — 상태 패킷 꼬리에 붙은 36바이트 블록을 header[23,24] 개수만큼 잘라 SP `usp_rcv_log_anlz`로 넘기는 방식이었고, 그마저 완결되지 않았다 | **완료(신규 설계)** | `SpeedGateLogCodec` + `LogEventCodec` + `GateLogService` — 임베디드/독립 두 형태 모두 처리, **레거시를 초과 달성** |
| R7 | `0x4E` Gate Data None, `0x50` Fast Motor | **레거시도 미매핑**(`default` 분기로 빠져 Warn 로그만) | **해당 없음** | 레거시가 처리하지 않았으므로 전환 누락이 아니다 |

> **레거시 저장 조건 주의**: R2~R5의 저장 분기는 `changeState.IsStatusChanged && (cmd==0x05||0x06)` 조건
> **안에** 있다. 즉 레거시도 Status 영역에 변화가 없으면 설정/모터/스케줄/휴일을 저장하지 않았다.
> 신규 구현 시 이 조건을 그대로 따를지, 오브젝트 코드별로 독립 판단할지 결정이 필요하다(D3).

> **주의**: R3~R5는 "보내는 쪽"(제어 명령 송신)은 이미 구현돼 있다(`GateControlCommandBuilder`,
> `TimeZoneCommandBuilder`). 누락된 것은 **장비가 되돌려 보낸 설정값을 DB에 되받아 적는 경로**다.
> 즉 웹 화면에서 모터/스케줄을 바꿔 보낼 수는 있으나, 장비의 실제 현재값을 읽어와 표시·대조하는
> 기능이 없다.

### 2.4 DB 계층

| # | 레거시 | 기능 | 상태 | 신규 대응 |
| --- | --- | --- | --- | --- |
| D1 | `DatabaseWriterService` (323줄) | **8샤드** 큐 + 샤드별 전담 워커 스레드, `partitionKey`(보통 IP) 해시로 순서 보장, 큐 깊이 경고(500)/상한(100,000) | **완료** | `GateDbWriteQueue.kt` |
| D2 | `DbWriteOperation` | 쓰기 작업 단위(성공/실패 콜백) | **완료** | 〃 |
| D3 | `EnqueuePacketDbWrites` + `PendingRcvPacket`/`LastRcvPacket` | DB 쓰기 성공 후에만 `lastPacket` 갱신하는 2단계 커밋(실패 시 변경분 영구 유실 방지) | **완료** | `GatePacketPersister` |
| D4 | `DbFactory`/`DbProviderBase`/`IDbProvider` + `ClsMariaDB`(1,395줄)/`ClsPostgreDB`(938줄) | MariaDB/PostgreSQL 이중 지원 추상화 | **완료** | Spring Data JPA(`securance-domain`) — 방언 추상화가 JPA로 대체됨 |

### 2.5 Quartz 스케줄러

레거시 `ClsQuartzScheduler.StartQuartzSchedule`이 등록하는 트리거 **4종**.

| # | 레거시 잡 | 주기 | 기능 | 상태 | 신규 대응 |
| --- | --- | --- | --- | --- | --- |
| Q1 | `ClsQuartzJobNetCheck` (216줄) | `GetIntervalNetCheck`초 | 커넥션 생존 확인, `tb_net_state` 갱신 | **완료** | `NetCheckJob.kt` |
| Q2 | `ClsQuartzJobReqStatus` (201줄) | `GetIntervalReqStatus`초 (조건부 등록 — `STATUS_REQ_TYPE != AUTOSEND`일 때만) | 상태 요청 패킷 능동 폴링, 응답 없으면 재연결 가드 후 커넥션 종료 | **완료** | `ReqStatusJob.kt` |
| Q3 | `ClsQuartzJobSendControl` (567줄, 최대) | `GetIntervalSendControl`초 | `tb_data_snd` 대기 명령 폴링→전송. 재전송 가드 5초, 스테일 TTL 5분, 전송 체인 대기 타임아웃 5초, **ACK 확인 후에만 DB 확정** | **완료** | `SendControlJob.kt` + `GateControlDispatcher.kt` |
| Q4 | `ClsQuartzJobGC` (130줄) | 1분 고정 | 가용 메모리 감시 후 수동 `GC.Collect` 호출(최소 1분 간격 쿨다운) | **제외** | 없음 — JVM GC가 담당(설계서 3.6절에 명시된 의도적 제외) |

### 2.6 레거시 대비 **개선**된 지점 (회귀 아님, 의도적 차이)

전환 과정에서 레거시 동작을 그대로 옮기지 않고 의식적으로 바꾼 곳이다. 후임자가 "누락"으로
오판하지 않도록 명시한다.

| 항목 | 레거시 | 신규 | 사유 |
| --- | --- | --- | --- |
| 제어 명령 조회 창 | `TB_DATA_SND`를 `reg_date BETWEEN NOW()-5분 AND NOW()+5분`으로 조회 — **±5분 창을 벗어난 명령은 영구히 처리되지 않고 유실** | `findPendingCommands` + `nextAttemptAt` 기반 재시도(최대 3회) | 레거시의 유실 특성을 제거 |
| 명령 선점 | `_inFlightSendIds` 인메모리 가드 + 5초 쿨다운 | `DataSend.version` **DB 낙관적 잠금**(`claimForSend`) | 다중 인스턴스 안전성 |
| 장애 해제 시점 | 전송 **직후** 해제 — 장비가 못 받아도 화면에서 장애가 사라짐 | QUEUED는 **장비 ACK 확인 후** 해제, DIRECT는 전송 등록 시점 | 레거시 결함 수정 |
| ACK 회신 | 조건부 | **항상 회신** | 레거시 ACK 누락 버그 수정 |
| 상태 분석 | DB 트리거 `utrg_data_rcv_anlz` | 앱 계층 `GateStatusAnalyzer`, **다중 레인 지원** | 트리거 제거 + 단일 레인 한계 해소 |
| ACK 타입 | `MakeACKDataAddTime(sAckType, …)`가 `"S"/"F"/"R"` 인자를 받지만 **패킷에 전혀 반영하지 않음**(성공/재전송 ACK가 바이트 수준에서 동일) | `RESEND` 경로 의도적 미이식 | 레거시에서 죽은 인자였음이 확인됨 |

### 2.7 설정 · 배포

| # | 레거시 | 상태 | 신규 대응 |
| --- | --- | --- | --- |
| C1 | `ClsIni` (996줄) + `Inis/`, `securance.cfg`, `App.config` | **제외(대체)** | `application.yml` + `@ConfigurationProperties`(`ServerModeConfig`, `SchedulerProperties`, `ControlProperties`, `LocationProperties`) |
| C2 | `ProjectInstaller` (윈도우 서비스 등록) | **제외(대체)** | `scripts/install-service.ps1`(WinSW) |
| C3 | `quartz.properties` | **완료** | `SchedulerConfiguration.kt` |
| C4 | `log4net.config` | **완료** | Spring Boot Logback |

## 3. 의사결정 필요 항목

착수 전 확인이 필요한 항목. 클라이언트 계획서 4절과 같은 성격이다.

- **D1. CLIENT 모드(S3)를 구현할 것인가?**
  레거시는 SERVER/CLIENT 양방향을 모두 지원했고 약 290줄이 이 로직에 쓰였다. 신규는 SERVER만
  구현하고 CLIENT 설정 시 기동을 실패시킨다(2026-08-07(2) 조치 — 침묵 실패 방지 목적이지 구현이
  아니다). **현장 게이트 중 백엔드가 먼저 접속해야 하는 장비가 있는지** 확인이 필요하다. 없다면
  `GatewayMode` enum에서 CLIENT를 제거해 죽은 설정을 없애는 편이 낫고, 있다면 `GateTcpClient`
  신규 구현이 필요하다(설계서 3.1절에 이미 설계는 있음).
- **D2. UDP 에코 응답기(S4) → 제외 권고(근거 확보됨)**
  레거시 서버의 UDP 기능은 받은 데이터그램을 그대로 되돌려주는 **에코**뿐이며, 프로토콜 파싱도
  DB 저장도 없다(`NET_PRTC_TYPE=UDP`일 때만 기동하고 ini 기본값은 TCP). 결정적으로 **수신 측인
  레거시 클라이언트의 UDP 처리 본문이 비어 있다** — `SR_F_DashBoard.cs`가 포트 28010에서
  브로드캐스트를 수신하지만 `"PLM"` 분기 본문이 주석("FREEZE-3 수정 … 제거")만 남아 **아무 동작도
  하지 않는다**. 즉 UDP는 **송신·수신 양쪽 모두 죽은 기능**이다.
  클라이언트의 나머지 UDP 사용처인 `SendControlCmdUDP`(포트 1460 제어 명령 직접 전송)는 브라우저가
  UDP를 쏠 수 없어 구조적으로 이식 불가하며, 서버 경유 전송(`tb_data_snd` 큐 / DIRECT)으로 이미
  대체됐다. **결론: 전환 대상에서 제외한다.** 남은 확인 사항은 게이트 장비 자체가 UDP 에코를
  생존 확인에 쓰는지 여부 하나뿐이다.
- ~~**D3. 모터/스케줄/휴일 수신 저장(R3~R5)을 구현할 것인가?**~~ **해소(2026-08-11) — 이미 구현되어
  있었다.** 최초 분석이 `GatePacketPersister`의 `else` 분기를 놓쳐 "미전환"으로 오판했다(R3~R5
  정오표 참고). 세 오브젝트 코드 모두 `tb_data_rcv`에 원시 저장되고 있어 추가 구현이 필요 없다.
  다만 **모터/스케줄 값 자체를 필드 단위로 파싱해 보여주는 화면은 없다** — 레거시도 마찬가지였다
  (`TB_DATA_RCV_MOTOR`의 `m_data01~08`/`s_data01~08` 파싱 컬럼은 레거시 C# 코드 어디에서도 채워진
  적이 없다, `92_DB_Script` 스키마 대조 확인). 즉 "보낸 값이 장비에 반영됐는지 화면에서 확인"하는
  기능은 레거시에도 없었으므로 전환 누락이 아니다 — 화면화가 필요하면 별도 신규 기능으로 다뤄야
  한다.
- **D4. Turn Gate / Fast Gate 코덱(P9) — 문서와 코드가 어긋나 있다**
  설계서 3.4절과 `GateTypeCodes.kt` 주석은 "Turn/Fast는 별도 프로토콜이라 미구현"이라고 하는데,
  실제 `SpeedFlapGateProtocolCodec.supportedGateTypes`는 **4종 전부를 지원한다고 선언**하고 있다.
  즉 Turn/Fast 게이트가 접속하면 미지원 오류가 아니라 **Speed/Flap 코덱으로 파싱을 시도**한다.
  둘 중 하나가 틀렸다 — (1) 실제로 4종이 같은 봉투를 쓴다면 주석을 정정해야 하고, (2) 아니라면
  `supportedGateTypes`를 좁혀 `UnsupportedGateTypeException`이 나도록 되돌려야 한다. **현장에
  Turn/Fast 게이트가 있는지** 확인이 선행되어야 한다.
- **D5. 데이터 보관/정리(retention) 정책**
  레거시에도 없던 기능이라 "전환 누락"은 아니지만, 신규 코드베이스에도 `tb_data_rcv` /
  `tb_data_rcv_anal` / `tb_gate_log`를 정리하는 잡·쿼리가 **전무**하다(`retention`/`purge`/
  `cleanup` grep 0건). 상태 패킷이 초 단위로 쌓이는 구조라 무한 증가하며, 설계서 4.5절
  "보관/백업"이 미구현 상태로 남아 있다. 보존 기간 정책 결정이 필요하다.

## 4. 단계별 우선순위 제안

- ~~**Phase S1 — 수신 저장 공백 메우기(R3, R4)**~~: **취소(2026-08-11) — 이미 완료돼 있었다.**
  D3 정정 참고.
- **Phase S2 — CLIENT 모드 결정 및 처리(S3)**: **우선순위 최상으로 승격** — 2026-08-11 사용자 확인
  결과 현장에 백엔드가 먼저 접속해야 하는 게이트가 있어 **구현 필요**로 확정. `GateTcpClient` 신규
  구현 착수(설계서 3.1절 설계 참고).
- **Phase S3 — 대시보드 알림 팝업 ↔ 리셋 배선**: 서버 측이 아니라 웹 측 작업이지만, 서버의
  `GateControlService`/`GateFaultResolutionService`가 이미 완성돼 있는데 화면만 연결이 안 된
  상태라 여기 함께 적는다. 상세는 `SR_Speed_Client_전환_계획.md` 2절 #1/#17 항목 참고.
- **Phase S4 — Turn/Fast 코덱(P9)**: 규격 문서 확보 후.
- **Phase S5 — 부하 검증**: 설계서 3.7절의 동시 연결 1,000개 목표가 아직 실측 검증되지 않았다
  (설계서 8절에 검증 항목으로만 존재). 목 TCP 클라이언트로 실측 필요.

## 5. 실코드 결함 — 전환 누락과 별개로 즉시 확인 필요 (2026.08.11 실측)

전환 여부와 무관하게, **이번 코드 대조 과정에서 발견된 신규 코드 자체의 결함**이다. 계획 항목이
아니라 버그이므로 별도로 모아둔다.

| # | 위치 | 내용 | 심각도 |
| --- | --- | --- | --- |
| B1 | `securance-domain/src/main/resources/db/migration/` | **Flyway 버전 번호 중복** — `V2`(align_entity_schema / fix_dashboard_query_indexes), `V3`(add_timezone / data_snd_optimistic_lock), `V4`(add_data_snd_pending_index / gate_log_events)가 각각 2개씩. Flyway는 동일 버전 중복 시 `Found more than one migration with version N`으로 **기동 시점에 실패**한다. `spring.flyway.enabled: true`이므로 **클린 DB 기동이 불가능할 가능성이 높다**. 병합 커밋(`4b0f13d`)에서 두 라인이 합쳐지며 발생한 것으로 보인다 | **최상** |
| B2 | `ServerModeConfig.kt` vs `application.yml` | 게이트 TCP 포트 기본값 불일치 — 코드 기본값 **28010**, yml **9000**. `client-port`도 코드 1005 vs yml 9000. yml이 있으면 yml이 이기지만, 기본값에 의존하는 테스트/배포에서 어긋난다 | 중 |
| B3 | `securance-web/realtime/DashboardPushService.kt:68` | `alertType = if (error.descFireAlarm != null) "FIRE" else "FAULT"` — 그런데 `DataReceiveAnalysis.descFireAlarm`은 **non-null String getter**라 조건이 항상 참. **모든 실시간 알림이 "화재 경고"로 표시된다**(#17 Warning과 #1 GateControl 구분이 무너짐). 같은 파일 `alertDescription()`의 `?: "오류 상세 미확인"` 폴백도 같은 이유로 절대 동작하지 않고 빈 문자열을 반환한다. `DashboardService.kt:47`은 같은 필드를 `isNotBlank()`로 올바르게 처리하고 있어 **두 곳의 판정이 불일치** | **상** |
| B4 | `securance-web/templates/gates/location-map.html:105` | `<script>` 태그에 **`th:inline="javascript"` 누락**. 내부 `const locMapWidth = /*[[${location.locMapWidth}]]*/ 0;`이 인라인 처리되지 않아 리터럴 `0`으로 남고, `savePosition()`의 `Math.round((leftPercent/100) * 0)` → **드래그한 그룹 아이콘 좌표가 항상 (0,0)으로 저장**된다(#5 SetupLocation 기능 무력화). 대조군: `gate-control.html:110`은 속성이 제대로 붙어 있음 | **상** |
| B5 | `MenuProvider.kt`, `dashboard.html:19,21,23` | **죽은 링크 3건** — `/gates/net-state`, `/gates/errors`, `/control/history`를 사이드바·SmallBox가 링크하지만 **매핑된 컨트롤러가 전 프로젝트에 없다**(클릭 시 404) | 중 |
| B6 | `securance-web/dashboard/DashboardService.kt:36` | `todayTrafficCount = 0` **하드코딩** — 대시보드 "금일 통행량" 위젯이 항상 0 | 중 |
| B7 | `tb_data_snd.snd_type_cd` | **코드 체계 이원화** — `web.gate.GateControlService`는 `"GATE_RESET"`/`"MODE_CCLM"`/`"MOTOR_INIT"`를, `server.control.*`는 `command.legacyCode`(`"AC"`/`"OP"`/`"CL"`)를 쓴다. 같은 컬럼에 두 체계가 섞여 레거시 리포트 호환이 깨진다 | 중 |
| B8 | `dashboard.html:26~32` | small-box 프래그먼트가 `<h3>`에 id를 못 붙여, **DOM 순서 인덱스로 `rt-*` id를 사후 부착**한다. 그런데 `dashboard-widgets.js`가 SortableJS로 위젯 순서를 바꿀 수 있어, **순서 변경 시 실시간 갱신값이 엉뚱한 박스에 들어간다** | 중 |
| B9 | `securance-web/security/DevAutoLoginFilter.kt` | 프로퍼티 하나(`dev-auto-login-enabled=true`)로 **DB 조회 없이 VIEW+CONTROL+ADMIN 전권을 부여**하는 필터가 남아 있다. 기본값·prod 모두 false이나, 파일 KDoc의 "전환 종료 후 제거" 지시가 미이행 상태 | **상**(운영 반입 전 제거 필수) |
| B10 | 낡은 주석 다수 | `GatePacketHandler.kt:11`("상세 파싱·저장은 후속 작업" — 실제로는 `GatePacketPersister`로 구현 완료), `SecurityConfig.kt:62`("`/admin/**`은 매칭 경로 없음" — `/admin/users` 존재), `dashboard.html:82` / `dashboard-realtime.js:60`("GateControlService 미구현" — 실제로는 구현 완료). 후임자가 오판할 수 있다 | 하 |

## 6. 검증되지 않은 영역(공통 리스크)

- 신규 서버 모듈은 단위 테스트가 충실하다(`PacketDifferTest`, `DefaultGatePacketHandlerTest`,
  `GateConnectionActorTest`, `SendControlJob` 계열 등). 다만 **실제 게이트 장비를 붙인 통합 검증
  기록은 작업일지에 없다** — 실수신 hex 패킷 샘플 기반 검증(2026.08.07 0003/0005/0006)까지가
  최대다.
- 동시 연결 1,000개 부하 테스트 미실시(설계서 3.7/8절 목표).
- Windows 임시 포트 고갈 튜닝(`MaxUserPort`/`TcpTimedWaitDelay`)은 CLIENT 모드 전용 이슈라
  D1 결정 전까지는 무의미하다.
