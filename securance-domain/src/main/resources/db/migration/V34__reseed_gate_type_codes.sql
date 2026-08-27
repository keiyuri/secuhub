-- V34 — GATE_TYPE 코드 재시딩 (게이트그룹/게이트 상세 수정 화면의 "게이트 타입" 선택 불가 버그 수정,
-- 2026-08-27)
--
-- 실제 개발 DB(`securance_gate`, 레거시 데이터가 이미 있던 환경)를 붙여 재현해 보니
-- `flyway_schema_history`에 V1이 "<< Flyway Baseline >>"으로만 기록돼 있었다 — 즉 이 DB는
-- 레거시 데이터가 이미 있는 상태에서 Flyway를 처음 붙였을 때 `baseline-on-migrate=true`(로컬
-- 개발 편의용, application.yml 주석 참고)가 V1을 "실제 실행"이 아니라 "이미 적용된 것으로 표시"만
-- 하고 건너뛴 것이다. 그 결과 V1 안에 있던 `tb_code`(GATE_TYPE) 시드 INSERT가 한 번도 실행되지
-- 않았고, `GateTypeCodeService.gateTypes()`(`code_grp = 'GATE_TYPE'` 조회)가 항상 빈 목록을
-- 반환해 게이트그룹/게이트 상세 등록·수정 모달의 "게이트 타입" `<select>`에 "선택하세요"
-- 플레이스홀더 외에는 옵션이 하나도 뜨지 않았다(신규 등록도 동일하게 막히지만, 특히 기존 값이
-- 이미 있어 아무것도 안 건드려도 되는 수정 화면에서 "선택이 안 된다"로 체감된다).
--
-- 실제로 이 DB에는 같은 게이트 타입 데이터가 `code_grp = 'GATE'`(코드값 자체는 1~4로 동일)로
-- 남아 있었다 — 레거시 이관 스크립트가 V1과 무관하게 별도로 넣어둔 것으로 보이며, 코드 어디에서도
-- `'GATE'` 그룹을 참조하지 않으므로(전체 검색 결과 무참조) 죽은 데이터다. 그대로 두고
-- `GATE_TYPE`을 별도로 채운다 — 어떤 다른 기능이 `'GATE'`를 쓰고 있을지 확신할 수 없는 상태에서
-- 관련 없어 보이는 레거시 행을 지우는 과도한 정리는 하지 않는다(계획서 전반의 "확인되지 않은
-- 추측으로 기존 데이터를 건드리지 않는다" 원칙).
--
-- `INSERT IGNORE`로 멱등하게 만든다 — V1이 정상적으로 실행됐던 환경(신규 DB)에서는 이미 같은
-- PK(`code_grp`, `code_cd`)가 존재하므로 조용히 스킵되고, 이번처럼 베이스라인으로 건너뛴
-- 환경에서만 실제로 값이 채워진다.
INSERT IGNORE INTO tb_code (code_grp, code_cd, code_nm, code_val, code_desc, disp_order, use_yn) VALUES
    ('GATE_TYPE', '1', 'Speed Gate', 'SR-1400', 'Speed/Flap Gate 공유 프로토콜', 1, 1),
    ('GATE_TYPE', '2', 'Flap Gate', 'FLAP', 'Speed/Flap Gate 공유 프로토콜', 2, 1),
    ('GATE_TYPE', '3', 'Turn Gate', 'TURN', '별도 프로토콜 — 1차 스캐폴드 미구현(계획서 3.4절)', 3, 1),
    ('GATE_TYPE', '4', 'Fast Gate', 'FAST', '별도 프로토콜 — 1차 스캐폴드 미구현(계획서 3.4절)', 4, 1);
