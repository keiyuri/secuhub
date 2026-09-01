-- ============================================================================
-- V36 — Flyway 마이그레이션 이력과 실제 개발 DB(192.168.0.26:28031) 스키마의 인덱스 드리프트 해소.
--
-- 배경(2026-09-01 DB 진단): V1의 installed_rank=1 행이 BASELINE 타입이다 — Flyway 도입 이전에
-- 이미 존재하던 스키마를 그대로 기준점으로 찍었을 뿐, V1__init_schema.sql이 실제로 실행되어
-- 만들어진 것이 아니다. 그 결과 개발 DB에는 실존하지만 V1~V35 어떤 마이그레이션 파일에도 없는
-- 인덱스가 다수 확인됐다(SHOW CREATE TABLE 대조). 이 파일 하나만으로 새 환경을 구성하면 이
-- 인덱스들이 만들어지지 않아, 이미 이 인덱스에 의존하는 쿼리(리포지토리 @Query, GateDbWriteQueue
-- hot path 등)가 신규/재구축 환경에서 훨씬 느리게 동작한다.
--
-- 이 마이그레이션은 개발 DB의 현재 상태를 "있는 그대로" 코드로 옮기는 것이 목적이며, 인덱스
-- 설계를 새로 하지 않는다(기존 인덱스 재설계/정리는 별도 후속 작업으로 분리 — 성급한 DROP은
-- 검증 없이 하지 않는다).
--
-- V22/docs/flyway-migration-recovery.md 컨벤션 적용: MariaDB DDL은 암묵적으로 즉시 커밋되므로,
-- 다건의 ALTER TABLE 중간에 연결이 끊기면 Flyway가 V36 전체를 실패로 기록해도 이미 적용된
-- 인덱스는 테이블에 남는다. 재시도 시 "Duplicate key name"으로 막히지 않도록 각 인덱스마다
-- information_schema.statistics 존재 확인 가드를 두어, 어느 지점에서 재실행하든 최종 상태가
-- "18개 인덱스 모두 존재"로 수렴하게 한다.
-- ============================================================================
DELIMITER $$
CREATE PROCEDURE _v36_sync_dev_baseline_indexes()
BEGIN
    -- tb_data_rcv_anal ---------------------------------------------------
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema = DATABASE() AND table_name = 'tb_data_rcv_anal' AND index_name = 'IDX_DATA_ANAL_ERR') THEN
        ALTER TABLE tb_data_rcv_anal ADD INDEX IDX_DATA_ANAL_ERR (dtl_ip, dtl_lane_no, anal_date, err_type, resolve_yn);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema = DATABASE() AND table_name = 'tb_data_rcv_anal' AND index_name = 'IDX_DATA_ANAL_DATE') THEN
        ALTER TABLE tb_data_rcv_anal ADD INDEX IDX_DATA_ANAL_DATE (anal_date, dtl_ip, dtl_lane_no);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema = DATABASE() AND table_name = 'tb_data_rcv_anal' AND index_name = 'IDX_QUERY_OPTIMIZED') THEN
        ALTER TABLE tb_data_rcv_anal ADD INDEX IDX_QUERY_OPTIMIZED (dtl_ip, dtl_lane_no, anal_date, anal_tp, err_type);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema = DATABASE() AND table_name = 'tb_data_rcv_anal' AND index_name = 'IDX_ERROR_RESOLVE') THEN
        ALTER TABLE tb_data_rcv_anal ADD INDEX IDX_ERROR_RESOLVE (err_type, resolve_yn, anal_date);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema = DATABASE() AND table_name = 'tb_data_rcv_anal' AND index_name = 'IDX_TYPE_CODE') THEN
        ALTER TABLE tb_data_rcv_anal ADD INDEX IDX_TYPE_CODE (dtl_type_cd);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema = DATABASE() AND table_name = 'tb_data_rcv_anal' AND index_name = 'IDX_USER_MODE') THEN
        ALTER TABLE tb_data_rcv_anal ADD INDEX IDX_USER_MODE (user_mode_cd);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema = DATABASE() AND table_name = 'tb_data_rcv_anal' AND index_name = 'IDX_SECURITY_MODE') THEN
        ALTER TABLE tb_data_rcv_anal ADD INDEX IDX_SECURITY_MODE (security_mode_cd);
    END IF;

    -- tb_data_rcv_log ------------------------------------------------------
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema = DATABASE() AND table_name = 'tb_data_rcv_log' AND index_name = 'idx_rcv_log_dup_check') THEN
        ALTER TABLE tb_data_rcv_log ADD INDEX idx_rcv_log_dup_check (dtl_ip, lane_no, log_raw);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema = DATABASE() AND table_name = 'tb_data_rcv_log' AND index_name = 'idx_rcv_log_dtl_rcvdate') THEN
        ALTER TABLE tb_data_rcv_log ADD INDEX idx_rcv_log_dtl_rcvdate (dtl_id, rcv_date);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema = DATABASE() AND table_name = 'tb_data_rcv_log' AND index_name = 'idx_rcv_log_grp_rcvdate') THEN
        ALTER TABLE tb_data_rcv_log ADD INDEX idx_rcv_log_grp_rcvdate (grp_id, rcv_date);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema = DATABASE() AND table_name = 'tb_data_rcv_log' AND index_name = 'idx_rcv_log_loc_rcvdate') THEN
        ALTER TABLE tb_data_rcv_log ADD INDEX idx_rcv_log_loc_rcvdate (loc_id, rcv_date);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema = DATABASE() AND table_name = 'tb_data_rcv_log' AND index_name = 'idx_rcv_log_rcvdate') THEN
        ALTER TABLE tb_data_rcv_log ADD INDEX idx_rcv_log_rcvdate (rcv_date);
    END IF;

    -- tb_data_rcv ------------------------------------------------------------
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema = DATABASE() AND table_name = 'tb_data_rcv' AND index_name = 'IDX_DATA_RCV_01') THEN
        ALTER TABLE tb_data_rcv ADD INDEX IDX_DATA_RCV_01 (rcv_date, dtl_ip, dtl_lane_no);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema = DATABASE() AND table_name = 'tb_data_rcv' AND index_name = 'IDX_DATA_RCV_02') THEN
        ALTER TABLE tb_data_rcv ADD INDEX IDX_DATA_RCV_02 (mod_date);
    END IF;

    -- tb_opr_status ------------------------------------------------------------
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema = DATABASE() AND table_name = 'tb_opr_status' AND index_name = 'IDX_OPR_DATE_LOC') THEN
        ALTER TABLE tb_opr_status ADD INDEX IDX_OPR_DATE_LOC (opr_date, loc_id, grp_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema = DATABASE() AND table_name = 'tb_opr_status' AND index_name = 'IDX_OPR_DATE_DTL') THEN
        ALTER TABLE tb_opr_status ADD INDEX IDX_OPR_DATE_DTL (opr_date, dtl_ip, dtl_lane_no);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema = DATABASE() AND table_name = 'tb_opr_status' AND index_name = 'IDX_OPR_STATUS_DAILY') THEN
        ALTER TABLE tb_opr_status ADD INDEX IDX_OPR_STATUS_DAILY (use_yn, opr_date, loc_id, grp_id, dtl_ip);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema = DATABASE() AND table_name = 'tb_opr_status' AND index_name = 'IDX_OPR_DTL_DATE') THEN
        ALTER TABLE tb_opr_status ADD INDEX IDX_OPR_DTL_DATE (dtl_id, dtl_ip, dtl_lane_no, use_yn, opr_date);
    END IF;
END$$
DELIMITER ;
CALL _v36_sync_dev_baseline_indexes();
DROP PROCEDURE _v36_sync_dev_baseline_indexes;
