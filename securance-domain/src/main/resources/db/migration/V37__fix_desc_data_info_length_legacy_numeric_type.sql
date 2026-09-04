-- ============================================================================
-- V37 — tb_data_rcv_anal.desc_* 6개 컬럼 레거시 숫자형(TINYINT UNSIGNED 등) 재발 수정 (2026-09-03)
--
-- [증상] 로컬 DB를 처음부터 재구성한 환경에서 bootRun 시 Hibernate 스키마 검증이 다음과
-- 같이 실패했다(20260903-01.txt):
--   Schema validation: wrong column type encountered in column [desc_data_info_length]
--   in table [tb_data_rcv_anal]; found [tinyint unsigned (Types#TINYINT)], but
--   expecting [varchar(10) (Types#VARCHAR)]
--
-- [원인] V36(`V36__sync_indexes_from_dev_baseline.sql`) 코멘트에서 이미 확인된 것처럼,
-- 다수 환경의 flyway_schema_history는 V1 행이 BASELINE 타입이다 — V1__init_schema.sql의
-- CREATE TABLE 문이 실제로 실행된 적이 없고, Flyway 이전부터 존재하던 레거시 스키마
-- (desc_* 6개 컬럼이 TINYINT UNSIGNED/INT로 남아있는 GateControl 계열 스키마 복사본)를
-- 그대로 기준점으로 baseline 잡았을 뿐이다. 이 6개 컬럼을 VARCHAR로 되돌리는 조치는 원래
-- V27(`V27__fix_desc_numeric_columns_to_varchar.sql`)이 담당했으나, V2~V32를 V1로
-- 스쿼시하면서 "신규 DB는 처음부터 V1의 CREATE TABLE로 VARCHAR로 생성된다"는 전제 아래
-- 사라졌다 — BASELINE된 레거시 환경(2026-09-03 로컬 DB 재구성 시 재현)에서는 이 전제가
-- 성립하지 않아 동일 결함이 재발했다.
--
-- [조치] V27과 동일하게 MODIFY COLUMN으로 타입만 VARCHAR로 되돌린다. 6개 컬럼 모두
-- information_schema.COLUMNS로 현재 DATA_TYPE을 먼저 확인해, 이미 VARCHAR인 컬럼은
-- 건드리지 않는다(V1 CREATE TABLE이 실제로 실행된 신규 환경에서는 전부 no-op — 멱등).
-- ALGORITHM 절은 명시하지 않는다 — V23/V27에서 확인된 대로 MariaDB가 이런 타입 변경에
-- ALGORITHM=INPLACE를 거부하고 COPY를 요구할 수 있으므로, 생략해 자동으로 고르게 한다.
--
-- [2026-09-03 수정 — DELIMITER/프로시저 방식 폐기] 최초 버전은 V36과 같은 `DELIMITER $$ /
-- CREATE PROCEDURE ... CALL ...` 패턴으로 작성했으나, 로컬 DB(localhost:28031)에 실제
-- 적용해보니 `PROCEDURE ... does not exist (Error Code: 1305)`로 CALL 단계에서 실패했다
-- (20260903-01.txt) — V36은 이미 다른 환경에서 먼저 적용되어 남은 흔적일 뿐, 이 방식이
-- 모든 환경에서 안정적이라는 근거는 아니었다. V27/V33이 실제로 검증된 `PREPARE ... FROM
-- @sql; EXECUTE ...; DEALLOCATE PREPARE ...` 동적 SQL 방식으로 대체해 프로시저 생성/호출
-- 자체를 없앤다 — 컬럼당 하나씩, 총 6번 반복한다.
-- ============================================================================

SET @v37_1 = (SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tb_data_rcv_anal'
                AND COLUMN_NAME = 'desc_data_info_length' AND DATA_TYPE <> 'varchar');
SET @v37_1_sql = IF(@v37_1 > 0,
    'ALTER TABLE tb_data_rcv_anal MODIFY COLUMN desc_data_info_length VARCHAR(10) NOT NULL DEFAULT ''''',
    'DO 0');
PREPARE v37_1_stmt FROM @v37_1_sql;
EXECUTE v37_1_stmt;
DEALLOCATE PREPARE v37_1_stmt;

SET @v37_2 = (SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tb_data_rcv_anal'
                AND COLUMN_NAME = 'desc_gate_lane_count' AND DATA_TYPE <> 'varchar');
SET @v37_2_sql = IF(@v37_2 > 0,
    'ALTER TABLE tb_data_rcv_anal MODIFY COLUMN desc_gate_lane_count VARCHAR(20) NOT NULL DEFAULT ''''',
    'DO 0');
PREPARE v37_2_stmt FROM @v37_2_sql;
EXECUTE v37_2_stmt;
DEALLOCATE PREPARE v37_2_stmt;

SET @v37_3 = (SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tb_data_rcv_anal'
                AND COLUMN_NAME = 'desc_gate_lane_number' AND DATA_TYPE <> 'varchar');
SET @v37_3_sql = IF(@v37_3 > 0,
    'ALTER TABLE tb_data_rcv_anal MODIFY COLUMN desc_gate_lane_number VARCHAR(20) NOT NULL DEFAULT ''''',
    'DO 0');
PREPARE v37_3_stmt FROM @v37_3_sql;
EXECUTE v37_3_stmt;
DEALLOCATE PREPARE v37_3_stmt;

SET @v37_4 = (SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tb_data_rcv_anal'
                AND COLUMN_NAME = 'desc_inout_time' AND DATA_TYPE <> 'varchar');
SET @v37_4_sql = IF(@v37_4 > 0,
    'ALTER TABLE tb_data_rcv_anal MODIFY COLUMN desc_inout_time VARCHAR(20) NOT NULL DEFAULT ''''',
    'DO 0');
PREPARE v37_4_stmt FROM @v37_4_sql;
EXECUTE v37_4_stmt;
DEALLOCATE PREPARE v37_4_stmt;

SET @v37_5 = (SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tb_data_rcv_anal'
                AND COLUMN_NAME = 'desc_user_count' AND DATA_TYPE <> 'varchar');
SET @v37_5_sql = IF(@v37_5 > 0,
    'ALTER TABLE tb_data_rcv_anal MODIFY COLUMN desc_user_count VARCHAR(20) NOT NULL DEFAULT ''''',
    'DO 0');
PREPARE v37_5_stmt FROM @v37_5_sql;
EXECUTE v37_5_stmt;
DEALLOCATE PREPARE v37_5_stmt;

SET @v37_6 = (SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tb_data_rcv_anal'
                AND COLUMN_NAME = 'desc_total_count' AND DATA_TYPE <> 'varchar');
SET @v37_6_sql = IF(@v37_6 > 0,
    'ALTER TABLE tb_data_rcv_anal MODIFY COLUMN desc_total_count VARCHAR(20) NOT NULL DEFAULT ''''',
    'DO 0');
PREPARE v37_6_stmt FROM @v37_6_sql;
EXECUTE v37_6_stmt;
DEALLOCATE PREPARE v37_6_stmt;
