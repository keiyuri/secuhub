-- ============================================================================
-- V30 — tb_data_rcv_anal.dtl_type_cd + anal_data_* 19개 컬럼을 엔티티 매핑을 위해 존재 보장
-- (2026-08-18, DataReceiveAnalysis 엔티티 필드 추가에 대응)
--
-- [경위] V23/V26이 정리한 대로 anal_data_* 계열 컬럼은 이 저장소의 Flyway 이력이 만든 적이
-- 없다 — 애플리케이션이 실제로 연결하는 운영 DB는 Flyway 도입 이전부터 레거시
-- GateControl(SR_Speed_Server)의 저장 프로시저 `usp_process_analysis`가 쓰던 이 컬럼들을
-- 이미 갖고 있었고, `baseline-on-migrate`가 V1부터를 베이스라인으로 건너뛰므로 V1의
-- CREATE TABLE에도 반영되지 않았다.
--
-- V23/V26은 그중 헤더 파생 14개(anal_data_stx~anal_data_mac)만 다뤘다. 2026-08-18 dev DB
-- information_schema 재조회로, 같은 이유로 아직 다루지 않은 20개가 더 있음을 확인했다:
--   1) dtl_type_cd — `dtl_type`의 문자열 코드값(예: "1"). 실 데이터 조회로 secuhub가 저장한
--      행(dtl_type=1/2)만 이 컬럼이 NULL임을 확인했다.
--   2) anal_data_gate_lane_count/gate_lane_number/gate_type/user_mode/security_mode/
--      inout_time/user_count/total_count/operation_sensor_status1/safety_sensor_status/
--      operation_sensor_status2/optical_sensor_status/output_status/motor_operation_count/
--      master_in_total_count/gate_operation_status/check_sum/packet_checksum/etx — 레인 상태
--      블록(74바이트, [kr.co.securance.secuhub.protocol.GateStatusAnalyzer.StatusOffset])과
--      Tail(4바이트)에서 그대로 뽑아낸 raw hex 컬럼들. desc_* 컬럼과 짝을 이루지만 값은 decoded
--      문자열이 아니라 원본 byte의 hex다.
--
-- [조치] V26과 동일하게 `ADD COLUMN IF NOT EXISTS`로 존재만 보장한다 — 운영 DB(이미 컬럼이
-- 있음)에서는 no-op이고, 컬럼이 없는 환경(신규 배포, 테스트용 실 스키마 재현 등)에서는 dev DB
-- information_schema가 보고한 것과 동일한 타입/기본값으로 새로 만든다. ALGORITHM/LOCK 절은
-- V23이 정한 이유(MariaDB 11.8의 ALGORITHM=INPLACE 제약)와 동일하게 생략해 자동 선택에 맡긴다.
-- ============================================================================

ALTER TABLE tb_data_rcv_anal
    ADD COLUMN IF NOT EXISTS dtl_type_cd                      VARCHAR(10) NULL AFTER dtl_type,
    ADD COLUMN IF NOT EXISTS anal_data_gate_lane_number        CHAR(2)     NOT NULL DEFAULT '' AFTER anal_data_mac,
    ADD COLUMN IF NOT EXISTS anal_data_gate_lane_count         CHAR(2)     NOT NULL DEFAULT '' AFTER anal_data_gate_lane_number,
    ADD COLUMN IF NOT EXISTS anal_data_gate_type               CHAR(2)     NOT NULL DEFAULT '' AFTER anal_data_gate_lane_count,
    ADD COLUMN IF NOT EXISTS anal_data_user_mode               CHAR(2)     NOT NULL DEFAULT '' AFTER anal_data_gate_type,
    ADD COLUMN IF NOT EXISTS anal_data_security_mode           CHAR(2)     NOT NULL DEFAULT '' AFTER anal_data_user_mode,
    ADD COLUMN IF NOT EXISTS anal_data_inout_time               CHAR(2)     NOT NULL DEFAULT '' AFTER anal_data_security_mode,
    ADD COLUMN IF NOT EXISTS anal_data_user_count               CHAR(2)     NOT NULL DEFAULT '' AFTER anal_data_inout_time,
    ADD COLUMN IF NOT EXISTS anal_data_total_count              CHAR(8)     NOT NULL DEFAULT '' AFTER anal_data_user_count,
    ADD COLUMN IF NOT EXISTS anal_data_operation_sensor_status1 CHAR(8)     NOT NULL DEFAULT '' AFTER anal_data_total_count,
    ADD COLUMN IF NOT EXISTS anal_data_safety_sensor_status     CHAR(8)     NOT NULL DEFAULT '' AFTER anal_data_operation_sensor_status1,
    ADD COLUMN IF NOT EXISTS anal_data_operation_sensor_status2 CHAR(8)     NOT NULL DEFAULT '' AFTER anal_data_safety_sensor_status,
    ADD COLUMN IF NOT EXISTS anal_data_optical_sensor_status    VARCHAR(50) NOT NULL DEFAULT '' AFTER anal_data_operation_sensor_status2,
    ADD COLUMN IF NOT EXISTS anal_data_output_status            VARCHAR(40) NOT NULL DEFAULT '' AFTER anal_data_optical_sensor_status,
    ADD COLUMN IF NOT EXISTS anal_data_motor_operation_count    VARCHAR(20) NOT NULL DEFAULT '' AFTER anal_data_output_status,
    ADD COLUMN IF NOT EXISTS anal_data_master_in_total_count    VARCHAR(20) NOT NULL DEFAULT '' AFTER anal_data_motor_operation_count,
    ADD COLUMN IF NOT EXISTS anal_data_gate_operation_status    VARCHAR(50) NOT NULL DEFAULT '' AFTER anal_data_master_in_total_count,
    ADD COLUMN IF NOT EXISTS anal_data_check_sum                CHAR(4)     NOT NULL DEFAULT '' AFTER anal_data_gate_operation_status,
    ADD COLUMN IF NOT EXISTS anal_data_packet_checksum          CHAR(2)     NOT NULL DEFAULT '' AFTER anal_data_check_sum,
    ADD COLUMN IF NOT EXISTS anal_data_etx                      CHAR(2)     NOT NULL DEFAULT '' AFTER anal_data_packet_checksum;
