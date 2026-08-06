# SR_Speed_Client 전환 계획

레거시 WinForms 클라이언트(`D:\workspace\vstudio\Maintenance\GateControl\SR_Speed_Client`)의
20개 화면 중 아직 웹(`securance-web`)으로 옮겨지지 않은 **18개 화면**을 어떤 순서로, 어떤 구조로
전환할지 정리한 문서. 코드는 아직 작성하지 않았다 — 이 문서는 착수 전 합의를 위한 설계서다.

## 1. 현재 상태

| 구분 | 화면 | 상태 |
| --- | --- | --- |
| 완료 | Login | `LoginController` + `login.html` (`SecurityConfig` 연동, 브루트포스 방어 포함) |
| 완료(부분) | DashBoard | `DashboardController` + `dashboard.html` — **정적 위젯 배치만** 구현, 레거시의 TreeView/MapIcon/DataGrid/이벤트폴링/Quartz 연동 등 실시간 기능은 미이식 |
| 미착수 | 나머지 18개 | 아래 표 |

백엔드(도메인/서버/스케줄러)는 이미 `NetCheckJob`/`SendControlJob`/`ReqStatusJob`,
`GateConnectionRegistry`, `DataReceive(Analysis)`/`DataSend`/`GateDetail`/`GateGroup`/
`GateLocation`/`NetState`/`OprStatus`/`AppUser`/`CodeMaster` 엔티티가 갖춰져 있어, 프론트 18개
화면 중 다수는 **신규 백엔드 로직 없이 컨트롤러+뷰만 추가하면 되는 화면**과 **엔티티/서비스를 새로
만들어야 하는 화면**으로 나뉜다.

## 2. 화면별 인벤토리 및 매핑

범례 — 실시간성: 高(TCP/UDP 직접 또는 즉시 알람) / 中(DB 큐 기반 명령) / 低(단순 CRUD·조회)

| # | 레거시 화면 | 목적 | 실시간성 | 필요 백엔드 | 비고 |
| --- | --- | --- | --- | --- | --- |
| 1 | `SR_F_GateControl` | 장애 알림 팝업 + 리셋 | 高 | 기존(`SendControlJob`, `DataReceiveAnalysis`) 재사용 + WebSocket/SSE 푸시 | **가장 실시간성 요구가 높은 화면** — 폴링이 아니라 서버→브라우저 push 설계 필요 |
| 2 | `SR_F_GateInitMotor` | 모터 초기값 설정 | 中 | - | **제외 확정(2026-08-06)** — 레거시 호출부 없음, 전환 대상 아님 |
| 3 | `SR_F_GateReset` | 다중 게이트 일괄 리셋 | 中 | `DataSend`, `GateDetail` 재사용 | **완료(2026-08-06)** — `/gates/reset`, 리셋 실행 버튼까지 동작(`GateControlService.sendReset`, `buildModeChangeCommand("AC")` 재사용) |
| 4 | `SR_F_SetupGateGroup` | 게이트그룹/상세 CRUD | 低 | `GateGroup`/`GateDetail` 리포지토리 재사용, 서비스 신규 | 마스터데이터 관리 — 우선순위 높음(다른 화면의 전제조건) |
| 5 | `SR_F_SetupLocation` | 위치/지도 좌표 관리 | 低 | `GateLocation` 재사용 + 이미지 업로드 신규 | **완료(2026-08-06, 축소 범위)** — `/gates/locations/{id}/map`, 배치도 업로드 + 그룹 아이콘 드래그 배치. 장비별 아이콘/상위 구역맵 계층은 제외(사유는 Phase 6 항목 참고) |
| 6 | `SR_F_SetupSchedule` | 모드/타임존 일괄 적용 | 中 | `DataSend` 재사용, TimeZone 엔티티 신규 | **완료(2026-08-06)** — `/schedule`(예약 모드 일괄 적용 폼). `SR_F_Schedule.cs`(구버전, 죽은 코드)가 아니라 라이브 로직인 `SR_F_SetupSchedule.cs`를 이식 |
| 7 | `SR_F_Schedule` | 타임존 등록 + 동기화 | 中 | TimeZone 엔티티 신규 | **완료(2026-08-06)** — 6번과 한 화면(`/schedule`)으로 통합, `GateTimeZone` 엔티티(`tb_time`) 신규 |
| 8 | `SR_F_ViewAccess` | 이용자 통계 조회 | 低 | `OprStatus`/신규 통계 리포지토리 | 조회+엑셀, 패턴 공통화 1호 |
| 9 | `SR_F_ViewEvent` | 이벤트 이력 조회(9종 필터) | 低 | `DataReceiveAnalysis` 재사용 | 조회+엑셀 |
| 10 | `SR_F_ViewLog` | 통신/운영 로그 조회 | 低 | `tb_log` 신규 대신 `tb_data_rcv_anal` 재사용(#9와 공유) | **완료(2026-08-06)** — `/reports/logs`. 상세는 [작업일지.md](작업일지.md) 참고 |
| 11 | `SR_F_ViewUser` | 사용자 계정 CRUD | 低 | `AppUser` 재사용, 서비스 신규 | 관리자 전용, 권한 체크 로직 이관 필요 |
| 12 | `SR_P_GateModeChange` | 개별 게이트 모드 변경 | 中 | `DataSend` 재사용 | **완료(2026-08-06)** — `/gates/details/{id}/mode`, `GateControlCommandBuilder`로 실제 전송까지 동작 |
| 13 | `SR_P_GateSetupMotor` | 개별 모터 설정 | 中 | `DataSend` 재사용 | **완료(2026-08-06)** — `/gates/details/{id}/motor`. 레인번호는 최종 확정된 10진 그대로 인코딩 규칙 적용 |
| 14 | `SR_P_Holiday` | 휴일 등록 | - | - | **제외 확정(2026-08-06)** — 레거시 자체가 미완성 스텁, 신규 요구사항 나오기 전까지 전환 대상 아님 |
| 15 | `SR_P_Timezone` | 타임존 등록 | 中 | TimeZone 엔티티 신규 | **완료(2026-08-06)** — 6/7번과 함께 `/schedule` 화면에서 등록(저장 시 전체 게이트 자동 전송) |
| 16 | `SR_F_Overlay` | 모달 배경 딤 처리 | - | 불필요 | Bootstrap `modal-backdrop` CSS로 대체, 별도 이식 불필요 |
| 17 | `SR_F_Warning` | 화재 경고 팝업 | 高 | #1과 동일 push 채널 재사용 | #1(GateControl)과 실시간 인프라 공유 |
| 18 | `SR_F_DashBoard` 나머지 | 트리뷰/맵아이콘/메뉴/이벤트폴링 오케스트레이션 | 高 | 전체 허브 | 이미 있는 정적 대시보드를 실시간 허브로 승격하는 작업, 사실상 별도 대형 과제 |

## 3. 단계별 우선순위 제안

1. **Phase 0 — 의사결정 선행** (아래 4절 참고, 코드 작성 전 확인 필요)
2. **Phase 1 — 마스터데이터 CRUD** (#4 SetupGateGroup, #11 ViewUser): 다른 화면의 전제조건이고
   실시간 이슈가 없어 위험이 가장 낮다. 여기서 "3단 콤보 검색 + 그리드" 공통 Thymeleaf
   프래그먼트/컨트롤러 패턴을 확립한다. **완료(2026-08-06)** — `/gates/locations`,
   `/gates/groups`, `/gates/details`, `/admin/users`. 상세는 [작업일지.md](작업일지.md) 참고.
3. **Phase 2 — 조회 화면 3종** (#8 ViewAccess, #9 ViewEvent, #3 GateReset의 검색 그리드 부분):
   Phase 1에서 만든 공통 패턴을 반복 적용, 엑셀 내보내기(Apache POI) 공통화. **완료(2026-08-06)** —
   `/reports/access`, `/reports/events`, `/gates/reset`(검색·선택 그리드만, 리셋 실행 버튼은
   `GateControlService` 구현 전까지 비활성). 상세는 [작업일지.md](작업일지.md) 참고.
4. **Phase 3 — 실시간 대시보드 인프라** (#18 DashBoard 실시간화 + #1 GateControl + #17 Warning):
   WebSocket/SSE push 설계가 선행되어야 하는 묶음. **완료(2026-08-06)** — 순정 WebSocket
   (`/ws/dashboard`) + `@Scheduled` DB 폴링(요약 5초/알림 3초) 하이브리드로 구현. #1/#17은
   실시간 팝업(모달)까지, 실제 리셋 실행은 `GateControlService` 구현 전까지 미제공(#3 GateReset과
   동일 방침). 상세는 [작업일지.md](작업일지.md) 참고.
5. **Phase 4 — 개별 게이트 제어 팝업** (#12 GateModeChange, #13 GateSetupMotor): **완료(2026-08-06)** —
   레거시 원본(`SR_C_DataHandler.cs`, `SR_P_GateModeChange.cs`, `SR_P_GateSetupMotor.cs`)을 직접
   확인해 패킷을 바이트 단위로 그대로 이식(`GateControlCommandBuilder`, 사용자 확인: "레거시 그대로
   재현" — Address 미설정, DataLength 고정값(0x5D) 등 원본의 결함까지 포함). `/gates/details/{id}/mode`,
   `/gates/details/{id}/motor`에서 `tb_data_snd` 큐에 실제로 INSERT하며, 기존 `SendControlJob`이
   그대로 전송을 처리한다 — #3/#1/#17과 달리 이번 화면은 **실제 명령 전송까지 동작**한다. 시간대
   스케줄(#6/#7/#15)은 Phase 5 전까지 미선택 고정. 상세는 [작업일지.md](작업일지.md) 참고.
6. **Phase 5 — 스케줄/타임존** (#6, #7, #15 통합 설계): **완료(2026-08-06)** — `/schedule` 한
   화면으로 통합. 좌측 "타임존 등록"(#7/#15, `GateTimeZone`/`tb_time` 신규)은 저장 시 레거시와
   동일하게 활성 게이트 전체에 TIME_SYNC(`DATA_TIME`) 명령을 자동 전송하고, "전체 동기화" 버튼으로
   기존 타임존을 재전송할 수 있다. 우측 "예약 모드 일괄 적용"(#6)은 위치→그룹→게이트 3단 콤보로
   대상을 좁혀 운영/보안 모드를 일괄 전송하며, 레거시 `SR_F_Schedule.cs`(구버전, 이벤트에 연결되지
   않은 죽은 코드)가 아니라 실제 라이브 로직인 `SR_F_SetupSchedule.cs`를 이식했다. 예약 슬롯 번호는
   별도 입력이 아니라 선택한 모드 라디오 순서로 자동 계산되며(`ScheduleApplyService.USER_MODE_SLOTS`/
   `SECU_MODE_SLOTS`), 운영모드 "CD/DC/FD/DF"는 레거시 `GenerateCmdBody` switch문에 해당 코드가 없어
   전부 Normal로 처리되는 레거시 결함까지 그대로 재현된다(별도 코드 없이 기존 switch 재사용만으로
   자동 재현). 상세는 [작업일지.md](작업일지.md) 참고.
7. **Phase 6 — GateReset 실행 + SetupLocation 지도 좌표** (#3, #5): **완료(2026-08-06)** —
   - **#3 GateReset**: `GateResetController`의 "선택 리셋 실행" 버튼을 활성화. 레거시
     `SR_F_GateReset.pbReset_MouseClick`은 `SetControlCmd(boardType, ip, laneNo, "AC", "", "")`로
     리셋 명령을 만드는데, 이는 `GenerateCmdBody`의 controlType `"AC"` 분기(offset 20에 0x01)만
     다를 뿐 #12 모드변경(`GenerateCmdBody`)과 완전히 동일한 패킷 조립 경로다 — Phase 4의
     `GateControlCommandBuilder.buildModeChangeCommand(laneNo, "AC")`를 그대로 재사용해 새 프로토콜
     코드 없이 구현했다(`GateControlService.sendReset`, 타입 코드는 레거시와 동일한 `GATE_RESET`).
     체크된 레인 각각에 대해 성공/실패를 집계해 하나의 요약 플래시 메시지로 보여준다(레거시는
     실패마다 개별 팝업).
   - **#5 SetupLocation**: 지도 좌표 UI를 **축소된 범위로** 이관했다(사용자 확인 없이 자체 판단,
     Phase 5의 죽은 코드 배제 판단과 동일한 성격 — 상세 근거는
     `GateLocationController.kt`/`LocationMapController.kt` 상단 주석 참고). 레거시
     `SR_F_SetupLocation.cs`(2200줄+)는 위치별 배치도 이미지 + 그룹/장비 아이콘의 WinForms
     네이티브 드래그앤드롭(`DoDragDrop`) + 구역(전체) 지도 계층까지 구현하지만, 이번 이관은
     **위치별 배치도 이미지 업로드 + 그 위에 그룹 아이콘 배치**까지만 제공한다. 이유: (1) 현재
     스키마(`tb_gate_dtl`)에 장비별 좌표 컬럼이 없어 장비 아이콘 배치는 스키마 확장이 선행돼야
     하고, (2) WinForms `DoDragDrop`은 웹으로 1:1 이식할 수 없어 HTML5 Pointer Events 기반의
     동등한 UX(마우스 다운→이동→업 시 좌표 저장)로 재구현했다. `GateLocation`에 `locMap`/
     `locMapWidth`/`locMapHeight`(이미 스키마에 있던 컬럼, 엔티티에 매핑만 추가), `GateGroup`에
     `grpX`/`grpY`를 추가했고, 좌표는 항상 배치도 원본 이미지 픽셀 기준으로 저장한다(레거시
     `AddGroupIconToMap`의 scaleW/scaleH 환산과 동일 원리, 프론트에서 퍼센트→픽셀 환산 후 저장).
     `WebConfig`가 업로드된 이미지를 `/loc-images/` 하위 정적 리소스로 서빙한다.
8. **제외/별도 처리 확정(2026-08-06)**: #2 GateInitMotor(전환 대상 제외), #14 Holiday(전환 대상
   제외).
9. **Phase 7 — #10 SR_F_ViewLog(2026-08-06 완료)**: 4절 스펙 메모대로 새 `tb_log`/`tb_log_event`를
   만드는 대신, `V1__init_schema.sql`이 이미 "tb_log_event(=tb_log 중복)는 이식하지 않는다"고
   확정해 둔 대로 #9 ViewEvent와 같은 `tb_data_rcv_anal`을 원본으로 재사용했다(`LogReportController`
   상단 주석 참고). ViewEvent(미해결 오류 추적, resolve_yn 워크플로)와 달리 ViewLog는 정상(NOR)
   통신까지 포함한 원시 로그 열람 화면으로 구분했고, `analType`을 레거시 `log_cat`(ACCESS/PARKING/
   DATA OBJECT/SYSTEM/COMMUNICATION) 대신 그대로 노출한다 — 원본 event_type 코드가 현재 스키마에
   없어 완전히 동일한 분류로는 재현하지 못했다(자체 판단, 사용자 확인 없이 진행 — 스키마 확장 없이
   구현 가능한 범위로 한정). 조회기간 기본값(전일~내일)과 3개월 초과 제한(초과 시 안내 후 fromDate를
   전일로 되돌림), 빈 설명 행 제외는 레거시 그대로 재현했다. `/reports/logs`, 상세는
   [작업일지.md](작업일지.md) 참고.

## 4. 착수 전 확인 필요한 의사결정 — **확정(2026-08-06, 사용자 확인)**

- **#6 SetupSchedule vs #7 Schedule → 통합**. 웹에서는 하나의 화면으로 합친다. Phase 5에서
  설계/구현.
- **#2 GateInitMotor → 제외**. 전환 대상에서 뺀다(레거시 호출부 없음 확인됨, 사장 화면 확정).
- **#14 Holiday → 제외**. 레거시 자체가 미완성 스텁이라 이식 대상이 아니며, 신규 요구사항이
  나오기 전까지는 전환 대상에 넣지 않는다.
- **#1/#17/#18 실시간 알림 방식 → WebSocket + 폴링 하이브리드**. `securance-web`이 순정
  WebSocket(STOMP/SockJS 미사용 — 외부 JS 벤더 불필요)으로 브라우저에 push하되, 변경 감지는
  `securance-server`의 커넥션 액터 이벤트를 직접 구독하는 대신 **DB 폴링**(`@Scheduled`)으로
  한다. 이유: `securance-web`은 모듈 구조상 `securance-server`에 의존하지 않는다(`securance-app`
  에서만 조립됨) — 진짜 이벤트 기반 push를 하려면 모듈 경계를 깨거나 메시징(RabbitMQ)을 거쳐야
  하는데, 지금 범위에서는 과함. DB 폴링 + WebSocket push가 모듈 경계를 지키면서 레거시 UDP
  브로드캐스트를 대체하는 가장 단순한 방법이다. 상세 구현은 [작업일지.md](작업일지.md) 참고.
- **#10 ViewLog → Phase 7(2026-08-06)에서 완료**. 아래는 착수 전 남겨둔 레거시 스펙 메모이며,
  실제 구현은 새 `tb_log` 대신 `tb_data_rcv_anal` 재사용으로 방향을 바꿨다(`LogReportController`
  주석과 2절 표, 3절 9번 참고) — README의 "로그 처리 범위 밖" 명시는 `tb_log`/`tb_log_event`
  원본 테이블을 그대로 이식하지 않는다는 의미로 좁혀 해석했다.

  **(2026-08-06 기록 — 착수 전 레거시 스펙 메모)** 구현 시 실제로 참고한 `SR_F_ViewLog.cs`/
  `SR_C_MariaDB.SelectGateLog` 조사 결과:
  - 화면 흐름: 위치→그룹→게이트 3단 콤보 + 조회기간(기본 전일~내일, **최대 3개월** 제한, 초과 시
    "최근 3개월까지만 조회 가능" 안내 후 fromDate를 전일로 되돌림) + 조회/엑셀 버튼.
  - `SelectGateLog(sFrDate, sToDate, sGbn, sData)` 쿼리 소스: `TB_DATA_RCV_LOG`(신규 `tb_log` 매핑
    대상) `INNER JOIN TB_GATE_DTL`(use_yn='Y') `LEFT JOIN TB_GATE_LOC`/`TB_GATE_GRP`/`TB_LOG_EVENT`
    (`event_desc = event_type+object_code+code+err_code`로 조인해 사람이 읽을 메시지 치환).
    `rcv_date`(yyyyMMddHHmm 문자열)를 `WHERE rcv_date BETWEEN sFrDate+'0000' AND sToDate+'2359'`로
    필터. `sGbn`(LOC/GRP/DTL)에 따라 `a.loc_id`/`a.grp_id`/`a.dtl_id` 중 하나로 좁힘. `log_cat`은
    `event_type` 코드값(01=ACCESS, 08=PARKING, 10=DATA OBJECT, 18=SYSTEM, 20=COMMUNICATION,
    그 외 UNKNOWN)을 CASE로 매핑. 빈 설명(`sDesc`)은 결과에서 제외. 정렬은 원본 수신시각
    (`sort_date`) 내림차순.
  - 권한 필터: `sAuthLoc`/`sAuthGrp`가 "ALL"이 아니면 `FIND_IN_SET`으로 loc_id/grp_id 화이트리스트
    제한 — 웹 이관 시 Spring Security 권한 필드로 대체(계획서 5절 "권한 체크" 패턴 참고).
  - 엑셀 내보내기(`SelectGateLogExcel`)는 조건절 없이 권한 범위 전체를 대상으로 하는 별도 쿼리 —
    컬럼이 한글 헤더로 직접 매핑되어 있어(`발생일`/`발생 위치`/`설치 장소` 등) `ExcelExportService`
    설계 시 헤더 정의를 그대로 재사용 가능.
  - 신규 필요 자원: `tb_log`(또는 기존 `TB_DATA_RCV_LOG`를 그대로 매핑) 엔티티, `GateLogService`,
    `LogEventCodec`(event_type/object_code/code/err_code → 사람이 읽을 메시지 변환, `TB_LOG_EVENT`
    참조), 3단 콤보 재사용(`fragments/search/location-cascade.html`), 엑셀 export 공용 서비스.

## 5. 공통 아키텍처 패턴 (Phase 1에서 확립)

- **검색 폼**: 지역→그룹→게이트 3단 콤보 (여러 화면에서 반복) → Thymeleaf 프래그먼트
  `fragments/search/location-cascade.html` + 공용 JS(`/static/js/location-cascade.js`)로 표준화.
- **그리드**: DevExpress/DataGridView → 서버사이드 페이징 테이블(Thymeleaf + 소량 JS) 또는
  기존 `securance-web` 프론트 스택(README 언급: adminlte-react 호환 마크업)에 맞춰 결정.
- **엑셀 내보내기**: `SR_C_Excel.DtToExcel` → Apache POI 기반 `ExcelExportService` 공용화(컨트롤러당
  개별 구현 금지).
- **권한 체크**: 레거시 `sAuthCtrl`/`sAuthAdmin` 전역 변수 → Spring Security `@PreAuthorize`/
  세션의 `AppUser` 권한 필드로 대체.
- **컨트롤/서비스/리포지토리 계층**: 화면당 `XxxController`(securance-web) +
  `XxxService`(securance-web 또는 필요 시 securance-server) + 기존 `securance-domain` 리포지토리.

## 6. 다음 단계

1. 4절의 의사결정 항목을 현업/사용자와 확인.
2. Phase 1(#4, #11)부터 실제 구현 세션 착수 — 공통 패턴 확립.
3. Phase 3 착수 전 실시간 push 방식(WebSocket vs SSE vs 폴링) 스파이크로 결론.
