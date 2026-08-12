-- V11 — tb_opr_status에 통행량 집계 쓰기 파이프라인(OprStatusPersister, 2026-08-12 B6)이
-- 필요로 하는 컬럼을 추가한다.
--
-- V1__init_schema.sql이 만든 tb_opr_status는 읽기(AccessReportController) 관점의 컬럼만 갖고
-- 있었다 — 이 테이블에 실제로 쓰는 코드가 지금까지 없었기 때문이다(B6 조사에서 발견). 레거시
-- 저장 프로시저 usp_process_status(92_DB_Script/20260805/securance_gate/usp_process_status.sql)를
-- 이식하면서 그 프로시저가 채우는 나머지 컬럼(dtl_type/dtl_no/opr_gate_type/opr_user_mode/
-- opr_security_mode/opr_inout_time)을 여기서 보강한다. 레거시 DDL(92_DB_Script/20260805/
-- securance_gate/tb_opr_status.sql)과 컬럼명·타입을 맞췄다.
--
-- 2026-08-12 수정: 실제 개발 DB(192.168.0.91)의 tb_opr_status는 V1 베이스라인 때부터 이미 이
-- 6개 컬럼을 레거시 스키마 그대로 갖고 있었다(마이그레이션 작성 당시 검증한 DB와 차이,
-- V8/V10과 동일한 패턴). ADD COLUMN에 IF NOT EXISTS를 붙여 이미 있는 컬럼은 건너뛰고, 없는
-- 환경(예: 로컬 신규 DB)에서는 그대로 추가되게 한다(MariaDB 10.0.2+ 지원).
ALTER TABLE tb_opr_status
    ADD COLUMN IF NOT EXISTS dtl_type          TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '게이트 종류(1:Speed,2:Flap,3:Turn,4:Fast)' AFTER dtl_lane_no,
    ADD COLUMN IF NOT EXISTS dtl_no            INT UNSIGNED NOT NULL DEFAULT 1 COMMENT 'Serial 연결 번호(TCP 경로는 미사용, 항상 0)' AFTER dtl_id,
    ADD COLUMN IF NOT EXISTS opr_gate_type     VARCHAR(20) NULL COMMENT 'GateStatusAnalyzer.describeGateType 결과' AFTER grp_id,
    ADD COLUMN IF NOT EXISTS opr_user_mode     VARCHAR(20) NULL COMMENT '운영 모드 코드(문자열)' AFTER opr_gate_type,
    ADD COLUMN IF NOT EXISTS opr_security_mode VARCHAR(20) NULL COMMENT '보안 등급 코드(문자열)' AFTER opr_user_mode,
    ADD COLUMN IF NOT EXISTS opr_inout_time    INT NOT NULL DEFAULT 0 COMMENT '인증 후 미진출 시 닫힘 시간' AFTER opr_security_mode;
