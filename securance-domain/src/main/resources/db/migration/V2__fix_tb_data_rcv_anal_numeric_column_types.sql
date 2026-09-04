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
-- BASELINE으로 기록된 환경(=공유 개발 DB)에서는 이 마이그레이션이 스스로 ALTER하지 않는다 —
-- 신규 DB(V1의 CREATE TABLE이 실제로 실행된 환경)에서만 ALTER를 적용한다.
--
-- [Codex 적대적 리뷰 반영, 2026-09-04] 최초 버전은 "BASELINE이면 무조건 이미 목표 타입일
-- 것"이라 가정하고 조건 없이 건너뛰었다 — 그러나 이 저장소 문서 자체가 이 공유 DB에서 같은
-- 8개 컬럼이 반복적으로 레거시 타입(TINYINT→VARCHAR로 고쳤다가 다시 TINYINT로)으로 드리프트된
-- 사례를 기록하고 있어(작업일지 0109), 그 전제가 실제로는 보장되지 않는다. BASELINE 환경에서도
-- information_schema.columns로 실제 컬럼 타입을 직접 검증해, 이미 목표 타입이면 그대로 두고
-- (no-op), 목표 타입과 다르면(=드리프트) 자동으로 고치는 대신 마이그레이션을 명시적으로
-- 실패시켜 DBA가 실측 후 수동 조치하도록 한다 — "자동 ALTER 금지"이지 "불일치를 조용히
-- 통과시킨다"는 뜻이 아니다.
-- ============================================================================

SET @is_baseline_env = (
    SELECT COUNT(*) FROM flyway_schema_history
    WHERE version = '1' AND type = 'BASELINE'
);

-- BASELINE 환경에서만 의미 있는 검사 — 8개 컬럼이 전부 목표 타입과 정확히 일치하는지
-- information_schema.columns의 COLUMN_TYPE으로 직접 확인한다(unsigned 여부까지 포함).
SET @matched_columns = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'tb_data_rcv_anal'
      AND (
        (column_name = 'desc_data_info_length' AND column_type = 'tinyint(3) unsigned') OR
        (column_name = 'desc_gate_lane_count'  AND column_type = 'tinyint(3) unsigned') OR
        (column_name = 'desc_gate_lane_number' AND column_type = 'tinyint(3) unsigned') OR
        (column_name = 'desc_inout_time'       AND column_type = 'int(10) unsigned') OR
        (column_name = 'desc_user_count'       AND column_type = 'bigint(20)') OR
        (column_name = 'desc_total_count'      AND column_type = 'bigint(20)') OR
        (column_name = 'desc_master_in_total'  AND column_type = 'bigint(20)') OR
        (column_name = 'desc_motor_count'      AND column_type = 'int(10) unsigned')
      )
);

-- 신규 DB(비-BASELINE)에서는 무조건 ALTER, BASELINE 환경에서는 절대 ALTER하지 않는다.
SET @v2_alter_sql = IF(@is_baseline_env = 0,
    'ALTER TABLE `tb_data_rcv_anal`
       MODIFY COLUMN `desc_data_info_length` tinyint(3) unsigned NOT NULL DEFAULT 0,
       MODIFY COLUMN `desc_gate_lane_count` tinyint(3) unsigned NOT NULL DEFAULT 0,
       MODIFY COLUMN `desc_gate_lane_number` tinyint(3) unsigned NOT NULL DEFAULT 0,
       MODIFY COLUMN `desc_inout_time` int(10) unsigned NOT NULL DEFAULT 0,
       MODIFY COLUMN `desc_user_count` bigint(20) NOT NULL DEFAULT 0,
       MODIFY COLUMN `desc_total_count` bigint(20) NOT NULL DEFAULT 0,
       MODIFY COLUMN `desc_master_in_total` bigint(20) NOT NULL DEFAULT 0,
       MODIFY COLUMN `desc_motor_count` int(10) unsigned NOT NULL DEFAULT 0',
    'DO 0'
);
PREPARE v2_alter_stmt FROM @v2_alter_sql;
EXECUTE v2_alter_stmt;
DEALLOCATE PREPARE v2_alter_stmt;

-- BASELINE 환경인데 8개 컬럼이 전부 목표 타입은 아닌 경우(=드리프트) — 조용히 넘어가지 않고
-- 마이그레이션을 실패시켜 DBA가 알아채게 한다. SIGNAL은 최상위 SQL에서 바로 쓸 수 없어
-- 임시 프로시저로 감싼다.
SET @v2_guard_create_sql = IF(@is_baseline_env > 0 AND @matched_columns < 8,
    'CREATE PROCEDURE `_v2_fail_on_baseline_drift`()
       SIGNAL SQLSTATE ''45000''
       SET MESSAGE_TEXT = ''V2: tb_data_rcv_anal 8개 컬럼 중 일부가 기대 타입(TINYINT/INT/BIGINT unsigned)과 다릅니다 - BASELINE DB 드리프트로 보입니다. DBA가 information_schema.columns로 실측 후 수동 조치하세요.''',
    'DO 0'
);
PREPARE v2_guard_create_stmt FROM @v2_guard_create_sql;
EXECUTE v2_guard_create_stmt;
DEALLOCATE PREPARE v2_guard_create_stmt;

SET @v2_guard_call_sql = IF(@is_baseline_env > 0 AND @matched_columns < 8,
    'CALL `_v2_fail_on_baseline_drift`()',
    'DO 0'
);
PREPARE v2_guard_call_stmt FROM @v2_guard_call_sql;
EXECUTE v2_guard_call_stmt;
DEALLOCATE PREPARE v2_guard_call_stmt;
