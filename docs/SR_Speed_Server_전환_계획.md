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
| 완전 전환 | 17 | TCP 수신·분석·ACK·DB 파이프라인·Quartz 잡 3종 + 모터/스케줄/휴일 수신 저장 + **CLIENT 모드**(2026-08-11 신규) 등 |
| 부분 전환 | 1 | `FAST_GATE_MOTOR`(0x50) 코덱 미구현(P11/P9 잔여분, 아래 참고) |
| 미전환 | 0 | — |
| 의도적 제외 | 3 | 메모리 GC 워치독, INI 설정, 서비스 인스톨러. UDP 에코(D2)도 제외 확정(3절 참고) |
| 레거시 초과(신규) | 1 | `GATE_LOG`(0x61) 로그 파싱 — 레거시 미구현분을 신규 설계 |

**전환률: 약 100%**(완전 17 + 부분 0.5 / 전체 17.5, 의도적 제외 4건은 분모에서 제외).

> **2026-08-12 정정(D4/P9)**: `FastGate Protocol Ver1_2020102601_01.md`(SmartGate Protocol, 상위
> 호환 규격 원본)를 직접 대조한 결과, Speed/Flap/Turn/Fast **4개 타입 모두 동일한 봉투**(Header
> 27B+Tail 4B, `GATE_STATUS`(0x4D)/`GATE_SETTING`(0x4C)/`GATE_MOTOR`(0x4B)/`TIME_ZONE`(0x54)/
> `HOLIDAY`(0x48) 객체)를 쓴다는 것이 규격 문서로 확인됐다 — Turn Gate는 상태 데이터의 GATE TYPE
> 필드 값(0x03)으로만 구분되고, Fast Gate의 Pause/Slide Open/Slide Close도 같은 0x4C 제어 봉투
> 안의 제어모드 값 확장(0x41/0x42/0x43)일 뿐이다. 즉 "Turn/Fast는 별도 프로토콜이라 미구현"이라던
> `GateTypeCodes.kt`/설계서 3.4절의 서술이 낡은 것이었고, `SpeedFlapGateProtocolCodec.
> supportedGateTypes`가 4종 전부를 지원하는 **코드 쪽이 정답**이었다 — 현장에 Turn/Fast 게이트가
> 있는지와 무관하게 규격 문서만으로 결론 낼 수 있는 문제였다(정정 전 "현장 확인 필요" 판단 철회).
> `GateTypeCodes.kt` 주석을 갱신했다. 유일하게 실제로 미구현인 것은 Fast Gate 전용 모터 설정
> Object Code `FAST_GATE_MOTOR`(0x50, 72바이트 TURN/SLIDE 모터 포지션·RPM·보정값 페이로드)뿐이며,
> 이는 P11에서 이미 별도로 추적 중이던 항목이다.

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
| S3 | `ClientModeMonitor`/`ConnectToAllDevices`/`ConnectToDevice` | `SpeedServer.cs:1388/1431/1495` (약 290줄) | **CLIENT 모드** — 백엔드가 `tb_gate_dtl`(use_yn='Y')을 읽어 게이트로 아웃바운드 접속 | **완료(2026-08-11)** | `GateTcpClient.kt` 신규 구현 — 재확인 주기 폴링 + IP 그룹핑 + 코루틴 병렬 연결. `GateTcpServer`/`GateInboundPacketProcessor`와 등록·처리·종료 경로 공유 |
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
| P9 | 게이트 타입별 코덱 분리 | — | Speed/Flap 공용, Turn/Fast는 별도 규격(추정) | **완료(2026-08-12 정정)** | 규격 문서(`FastGate Protocol Ver1_2020102601_01.md`) 대조 결과 4종 모두 동일 봉투/객체코드를 쓰는 것으로 확인 — `SpeedFlapGateProtocolCodec`이 `supportedGateTypes`에 4종 전부를 등록한 것이 정답이었다. 설계서 3.4절의 "Turn/Fast 별도 규격" 추정이 규격 미확보 시점의 낡은 서술이었다(3절 D4 참고) |
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

- ~~**D1. CLIENT 모드(S3)를 구현할 것인가?**~~ **해소(2026-08-11)** — 사용자 확인 결과 현장에
  백엔드가 먼저 접속해야 하는 게이트가 있어 **구현 필요**로 확정, `GateTcpClient` 신규 구현 완료
  (S3 정오표 참고).
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
- ~~D4. Turn Gate / Fast Gate 코덱(P9) — 문서와 코드가 어긋나 있다~~ **해결 완료(2026-08-12)**
  `FastGate Protocol Ver1_2020102601_01.md`(SmartGate Protocol) 규격 문서를 직접 대조해 4종
  게이트가 완전히 동일한 봉투/객체코드를 쓴다는 것을 확인했다 — 코드(`SpeedFlapGateProtocolCodec.
  supportedGateTypes` 4종 전부 지원)가 정답이었고, "Turn/Fast는 별도 프로토콜" 서술 쪽이 규격
  미확보 시절의 낡은 추정이었다. "현장에 Turn/Fast 게이트가 있는지 확인이 선행돼야 한다"던 판단은
  철회한다 — 규격 문서만으로 결론 낼 수 있는 문제였다(현장 여부와 무관). `GateTypeCodes.kt` 주석
  정정 완료. 유일한 실제 미구현분은 Fast Gate 전용 `FAST_GATE_MOTOR`(0x50) 모터 코덱이며 P11로
  계속 추적한다.
- **D5. 데이터 보관/정리(retention) 정책**
  레거시에도 없던 기능이라 "전환 누락"은 아니지만, 신규 코드베이스에도 `tb_data_rcv` /
  `tb_data_rcv_anal` / `tb_gate_log`를 정리하는 잡·쿼리가 **전무**하다(`retention`/`purge`/
  `cleanup` grep 0건). 상태 패킷이 초 단위로 쌓이는 구조라 무한 증가하며, 설계서 4.5절
  "보관/백업"이 미구현 상태로 남아 있다. 보존 기간 정책 결정이 필요하다.

## 4. 단계별 우선순위 제안

- ~~**Phase S1 — 수신 저장 공백 메우기(R3, R4)**~~: **취소(2026-08-11) — 이미 완료돼 있었다.**
  D3 정정 참고.
- ~~**Phase S2 — CLIENT 모드 결정 및 처리(S3)**~~: **완료(2026-08-11)** — `GateTcpClient` 신규
  구현 완료(D1/S3 정오표 참고). 실제 게이트 장비 접속 검증은 6절 "검증되지 않은 영역" 참고.
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
| B3 | `securance-web/realtime/DashboardPushService.kt:68` | ~~`alertType = if (error.descFireAlarm != null) "FIRE" else "FAULT"`~~ **수정 완료(2026-08-11, 커밋 941b403)** — `isNotBlank()` 판정으로 교체, 리셋 버튼도 함께 배선 | ~~상~~ 완료 |
| B4 | `securance-web/templates/gates/location-map.html:105` | ~~`th:inline="javascript"` 누락으로 좌표가 항상 (0,0) 저장~~ **수정 완료(2026-08-11, 커밋 0d208d7)** | ~~상~~ 완료 |
| B5 | `MenuProvider.kt`, `dashboard.html:19,21,23` | ~~죽은 링크 3건~~ **수정 완료(2026-08-11, 커밋 aee9f51)** — `/control/history` 신규 화면 제작, 나머지 2건은 기존 화면으로 재연결 | ~~중~~ 완료 |
| B6 | `securance-web/dashboard/DashboardService.kt:36` | ~~`todayTrafficCount = 0` 하드코딩~~ **수정 완료(2026-08-12)** — 재조사 결과 진짜 원인은 훨씬 컸다: `tb_opr_status`(통행량 원본 테이블, `/reports/access` 화면도 같은 테이블을 읽음)에 secuhub 어디에서도 **쓰기 자체가 없었다**(신규 발견한 회귀 — `/reports/access`도 지금까지 항상 빈 결과였다). 레거시는 이 테이블을 애플리케이션이 아니라 MariaDB 저장 프로시저 `usp_process_status`(`92_DB_Script/20260805/securance_gate/usp_process_status.sql`)로 채웠다 — 그 로직(분 단위 delta 집계, PREV 조회, 재수신 UPDATE 분기)을 바이트 오프셋까지 그대로 이식해 [OprStatusPersister](../securance-server/src/main/kotlin/kr/co/securance/secuhub/server/db/OprStatusPersister.kt)로 구현했고, `DefaultGatePacketHandler`가 상태 변경 감지 시 `GatePacketPersister.persistStatusAnalysis`와 나란히 호출한다. 대시보드 위젯 집계식(`sumUserCountToday`)은 `uvw_user_cnt`를 참조하는 레거시 C# 코드를 끝내 찾지 못해 **최선 추정**임을 코드 주석에 명시했다(`OprStatusRepository.sumUserCountToday` KDoc 참고) — 원래의 "과도한 억측 금지" 우려는 쓰기 로직(SP 이식)에는 더 이상 해당하지 않지만, 이 최종 집계식만은 여전히 미검증 상태다. `V11__opr_status_write_columns.sql`로 스키마 보강, `OprStatusPersisterTest`로 delta 계산 검증 | ~~중~~ 완료(집계식은 미검증) |
| B7 | `tb_data_snd.snd_type_cd`/`snd_data_tp` | ~~코드 체계 이원화~~ **수정 완료(2026-08-12)** — 레거시 `SR_C_MariaDB.InsertSendData`를 직접 확인한 결과 `sDataType`(레거시 `SendData(..,"GATE_RESET")`) 인자는 `snd_type_cd`가 아니라 **`snd_data_tp`**로 저장되고, `snd_type_cd`("send ENGINE cd")는 레거시가 애초에 어느 INSERT 경로에서도 채운 적이 없는 컬럼이었다(문서 서술 오류 정정 — "레거시 리포트 호환"이 걸린 컬럼은 `snd_data_tp` 하나뿐). `server.control.QueuedGateControlService`가 이미 `snd_type_cd=legacyCode`/`snd_data_tp=RESET_GATE 등`을 올바르게 병행 기록하고 있었던 반면, `web.gate.GateControlController.sendReset`은 `snd_type_cd="GATE_RESET"`만 기록해 `GateControlDispatcher.resolveFaultsIfReset`의 `SpeedGateControlCommand.ofLegacyCode` 조회가 항상 null이 되어 **ACK 후 자동 장애 해제가 동작하지 않는 실질적 버그**였다 — `snd_type_cd="AC"`(legacyCode)로 고치고 `snd_data_tp="RESET_GATE"`를 추가해 두 진입 경로를 일치시켰다. `MODE_$combined`/`MOTOR_INIT`/`MOTOR_CHANGE`는 리셋이 아니라 `resolveFaultsIfReset` 영향이 없어 그대로 둠 | ~~중~~ 완료 |
| B8 | `dashboard.html:26~32` | ~~small-box 프래그먼트가 `<h3>`에 id를 못 붙여 DOM 순서 인덱스로 `rt-*` id를 사후 부착~~ **수정 완료(2026-08-12)** — `dashboard-widgets.js`의 `Sortable.create`는 `.connectedSortable`(2행의 카드 컬럼)에만 적용되고 1행 SmallBox에는 적용되지 않아 "순서 변경 시 엉뚱한 박스 갱신" 서술은 실제로는 발생하지 않는 시나리오였다(정정). 다만 사후 부착 방식 자체는 불필요한 취약점이라 `small-box.html` 프래그먼트에 `id` 파라미터를 추가해 `<h3>`가 직접 id를 갖도록 근본 수정 — `card.html`의 `cardId` 파라미터와 동일 패턴 | ~~중~~ 완료 |
| B9 | ~~`securance-web/security/DevAutoLoginFilter.kt`~~ | ~~프로퍼티 하나로 DB 조회 없이 VIEW+CONTROL+ADMIN 전권을 부여하는 필터가 남아 있음~~ **제거 완료(2026-08-12)** — 파일 삭제, `SecurityConfig`의 `devAutoLoginFilterProvider` 배선/`addFilterBefore` 제거, `application.yml`·`application-prod.yml`의 `securance.security.dev-auto-login-enabled` 키 제거. 필요해지면 이 커밋 이전 git 이력에서 복원 가능 | ~~**상**~~ 완료 |
| B10 | 낡은 주석 다수 | `GatePacketHandler.kt:11`("상세 파싱·저장은 후속 작업" — 실제로는 `GatePacketPersister`로 구현 완료), `SecurityConfig.kt:62`("`/admin/**`은 매칭 경로 없음" — `/admin/users` 존재). `dashboard.html:82`/`dashboard-realtime.js:60`의 "GateControlService 미구현" 주석은 B3 수정과 함께 정정 완료 | 하 |

## 6. 검증되지 않은 영역(공통 리스크)

- 신규 서버 모듈은 단위 테스트가 충실하다(`PacketDifferTest`, `DefaultGatePacketHandlerTest`,
  `GateConnectionActorTest`, `SendControlJob` 계열 등). 다만 **실제 게이트 장비를 붙인 통합 검증
  기록은 작업일지에 없다** — 실수신 hex 패킷 샘플 기반 검증(2026.08.07 0003/0005/0006)까지가
  최대다.
- 동시 연결 1,000개 부하 테스트 미실시(설계서 3.7/8절 목표).
- **CLIENT 모드(`GateTcpClient`, 2026-08-11 신규)는 실제 게이트 장비로 검증되지 않았다** —
  단위 테스트도 아직 없다(`GateTcpServerTest`처럼 임의 포트에 리스너를 띄우고 `GateTcpClient`가
  거기로 접속하는 통합 테스트를 추가하는 편이 다음 단계로 적절하다). Windows 임시 포트 고갈
  튜닝(`MaxUserPort`/`TcpTimedWaitDelay`)도 CLIENT 모드가 실제로 켜지는 배포에서는 함께 점검이
  필요하다.
