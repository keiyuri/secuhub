-- ============================================================================
-- V26 — tb_data_rcv_anal.anal_data_* 33개 컬럼을 엔티티에 매핑하기 위한 존재 보장
-- (2026-08-14, DataReceiveAnalysis 엔티티 필드 추가에 대응)
--
-- [경위] V23(정정판) KDoc이 기록한 대로, 이 33개 컬럼은 이 저장소의 Flyway 이력이 만든 적이
-- 없다 — 애플리케이션이 실제로 연결하는 운영 DB는 Flyway 도입 이전부터 레거시
-- GateControl(SR_Speed_Server)의 저장 프로시저 `usp_process_analysis`가 쓰던 이 컬럼들을
-- 이미 갖고 있었고, `baseline-on-migrate`가 V1부터를 베이스라인으로 건너뛰므로 V1의
-- CREATE TABLE에도 반영되지 않았다.
--
-- 이번에 DataReceiveAnalysis 엔티티가 이 33개 컬럼을 매핑하기 시작하면서(secuhub가 저장하는
-- 행에도 이 컬럼들을 채우기 위함), 운영 DB가 아닌 환경(신규 배포, 테스트용 실 스키마 재현 등)에서
-- V1부터 전체 마이그레이션을 새로 실행할 경우 이 컬럼들이 없어 INSERT가
-- "Unknown column 'anal_data_stx'"로 실패한다. V8/V23과 동일하게 `ADD COLUMN IF NOT EXISTS`로
-- 존재만 보장한다 — 운영 DB(이미 컬럼이 있음)에서는 no-op이고, 컬럼이 없는 환경에서는 V23이
-- 정한 것과 동일한 타입/기본값으로 새로 만든다.
-- ============================================================================

ALTER TABLE tb_data_rcv_anal
    ADD COLUMN IF NOT EXISTS anal_data_stx          VARCHAR(10) NOT NULL DEFAULT '' AFTER obj_cd,
    ADD COLUMN IF NOT EXISTS anal_data_packet_len   VARCHAR(10) NOT NULL DEFAULT '' AFTER anal_data_stx,
    ADD COLUMN IF NOT EXISTS anal_data_protocol_ver VARCHAR(10) NOT NULL DEFAULT '' AFTER anal_data_packet_len,
    ADD COLUMN IF NOT EXISTS anal_data_frame_option VARCHAR(10) NOT NULL DEFAULT '' AFTER anal_data_protocol_ver,
    ADD COLUMN IF NOT EXISTS anal_data_address      VARCHAR(40) NOT NULL DEFAULT '' AFTER anal_data_frame_option,
    ADD COLUMN IF NOT EXISTS anal_data_command      VARCHAR(10) NOT NULL DEFAULT '' AFTER anal_data_address,
    ADD COLUMN IF NOT EXISTS anal_data_subcommand   VARCHAR(10) NOT NULL DEFAULT '' AFTER anal_data_command,
    ADD COLUMN IF NOT EXISTS anal_data_object_code  VARCHAR(10) NOT NULL DEFAULT '' AFTER anal_data_subcommand,
    ADD COLUMN IF NOT EXISTS anal_data_info_length  VARCHAR(10) NOT NULL DEFAULT '' AFTER anal_data_object_code,
    ADD COLUMN IF NOT EXISTS anal_data_count        VARCHAR(10) NOT NULL DEFAULT '' AFTER anal_data_info_length,
    ADD COLUMN IF NOT EXISTS anal_data_length       VARCHAR(10) NOT NULL DEFAULT '' AFTER anal_data_count,
    ADD COLUMN IF NOT EXISTS anal_data_gate_name    VARCHAR(80) NOT NULL DEFAULT '' AFTER anal_data_length,
    ADD COLUMN IF NOT EXISTS anal_data_ip           VARCHAR(20) NOT NULL DEFAULT '' AFTER anal_data_gate_name,
    ADD COLUMN IF NOT EXISTS anal_data_mac          VARCHAR(20) NOT NULL DEFAULT '' AFTER anal_data_ip,
    ALGORITHM = INPLACE,
    LOCK = NONE;
