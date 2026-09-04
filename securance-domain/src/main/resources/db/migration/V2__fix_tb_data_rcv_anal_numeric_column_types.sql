-- ============================================================================
-- tb_data_rcv_anal 8개 컬럼을 개발 DB(192.168.0.26:28031) 실측 타입으로 정합화
-- (2026-09-04, Codex 리뷰 P1 반영 — V1을 직접 고치는 대신 순방향 마이그레이션으로 분리)
--
-- V1의 CREATE TABLE은 desc_data_info_length 등 8개 컬럼을 VARCHAR/INT(signed)로 정의하지만,
-- 실제 개발 DB(BASELINE 이력)는 진작에 TINYINT/INT/BIGINT(unsigned 계열)로 운영되고 있었다
-- (작업일지 0106~0109 참고). 엔티티([DataReceiveAnalysis.kt])도 이 실측 타입에 맞춰 Int/Long으로
-- 함께 수정했으므로, hibernate.ddl-auto=validate가 기동을 막지 않으려면 신규 DB도 V1 직후 이
-- 타입으로 맞춰야 한다.
--
-- BASELINE(레거시) DB 자동 ALTER 금지 원칙(docs/flyway-migration-recovery.md)에 따라, V1이
-- BASELINE으로 기록된 환경(=이미 이 실측 타입을 갖고 있는 공유 개발 DB)에서는 이 마이그레이션을
-- 스스로 건너뛴다 — 신규 DB(V1의 CREATE TABLE이 실제로 실행된 환경)에서만 적용된다.
-- ============================================================================

SET @is_baseline_env = (
    SELECT COUNT(*) FROM flyway_schema_history
    WHERE version = '1' AND type = 'BASELINE'
);
SET @v2_sql = IF(@is_baseline_env > 0,
    'DO 0',
    'ALTER TABLE `tb_data_rcv_anal`
       MODIFY COLUMN `desc_data_info_length` tinyint(3) unsigned NOT NULL DEFAULT 0,
       MODIFY COLUMN `desc_gate_lane_count` tinyint(3) unsigned NOT NULL DEFAULT 0,
       MODIFY COLUMN `desc_gate_lane_number` tinyint(3) unsigned NOT NULL DEFAULT 0,
       MODIFY COLUMN `desc_inout_time` int(10) unsigned NOT NULL DEFAULT 0,
       MODIFY COLUMN `desc_user_count` bigint(20) NOT NULL DEFAULT 0,
       MODIFY COLUMN `desc_total_count` bigint(20) NOT NULL DEFAULT 0,
       MODIFY COLUMN `desc_master_in_total` bigint(20) NOT NULL DEFAULT 0,
       MODIFY COLUMN `desc_motor_count` int(10) unsigned NOT NULL DEFAULT 0'
);
PREPARE v2_stmt FROM @v2_sql;
EXECUTE v2_stmt;
DEALLOCATE PREPARE v2_stmt;
