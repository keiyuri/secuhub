-- V14 — tb_gate_log 컬럼 타입 정합화 (Hibernate ddl-auto=validate 실패 수정, 2026-08-12)
--
-- V7__add_gate_log.sql이 레거시 DDL 관례대로 TINYINT/SMALLINT UNSIGNED로 만들었지만,
-- GateLog 엔티티(securance-domain)는 처음부터 모든 필드를 Kotlin Int로 매핑하고 있었다 —
-- V8/V11/V13에서 이미 여러 번 겪은 것과 같은 종류의 불일치다. 이 테이블은 V10에서 같은 목적으로
-- 재설계된 tb_gate_log_event로 사실상 대체됐고 실제로 비어 있어(0행), 폭 확장이 즉시/안전하게
-- 끝난다.
ALTER TABLE tb_gate_log
    MODIFY COLUMN dtl_lane_no    INT UNSIGNED NOT NULL,
    MODIFY COLUMN event_type     INT UNSIGNED NOT NULL,
    MODIFY COLUMN object_code    INT UNSIGNED NOT NULL,
    MODIFY COLUMN code           INT UNSIGNED NOT NULL,
    MODIFY COLUMN err_code       INT UNSIGNED NOT NULL,
    MODIFY COLUMN operation_mode INT UNSIGNED NOT NULL,
    MODIFY COLUMN reader_type    INT UNSIGNED NOT NULL,
    MODIFY COLUMN reader_number  INT UNSIGNED NOT NULL,
    MODIFY COLUMN door_status    INT UNSIGNED NOT NULL,
    MODIFY COLUMN function_code  INT UNSIGNED NOT NULL;
