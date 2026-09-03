-- ============================================================================
-- V36 — Flyway 마이그레이션 이력과 실제 개발 DB(192.168.0.26:28031) 스키마의 인덱스 드리프트 해소.
--
-- 배경(2026-09-01 DB 진단): V1의 installed_rank=1 행이 BASELINE 타입이다 — Flyway 도입 이전에
-- 이미 존재하던 스키마를 그대로 기준점으로 찍었을 뿐, V1__init_schema.sql이 실제로 실행되어
-- 만들어진 것이 아니다. 그 결과 개발 DB에는 실존하지만 V1~V35 어떤 마이그레이션 파일에도 없는
-- 인덱스가 다수 확인됐다(SHOW CREATE TABLE 대조, V1 스쿼시 이전 기준 — 현재는 V2~V32가 V1로
-- 스쿼시됐다). 이 파일 하나만으로 새 환경을 구성하면 이 인덱스들이 만들어지지 않아, 이미 이
-- 인덱스에 의존하는 쿼리(리포지토리 @Query, GateDbWriteQueue hot path 등)가 신규/재구축
-- 환경에서 훨씬 느리게 동작한다.
--
-- 이 마이그레이션은 개발 DB의 현재 상태를 "있는 그대로" 코드로 옮기는 것이 목적이며, 인덱스
-- 설계를 새로 하지 않는다(기존 인덱스 재설계/정리는 별도 후속 작업으로 분리 — 성급한 DROP은
-- 검증 없이 하지 않는다).
--
-- DDL 재시도 안전성: MariaDB DDL은 암묵적으로 즉시 커밋되므로, 다건의 ALTER TABLE 중간에
-- 연결이 끊기면 Flyway가 V36 전체를 실패로 기록해도 이미 적용된 인덱스는 테이블에 남는다.
-- 재시도 시 "Duplicate key name"으로 막히지 않도록 각 인덱스마다 information_schema.statistics
-- 존재 확인 가드를 두어, 어느 지점에서 재실행하든 최종 상태가 "18개 인덱스 모두 존재"로
-- 수렴하게 한다.
--
-- [2026-09-03 수정 1 — tb_data_rcv_log 레거시 테이블 부재 가드]
-- tb_data_rcv_log는 애플리케이션 코드 어디에서도 매핑되지 않는 죽은 레거시 테이블이다
-- ("# securance 아키텍처 설계 및 초기 소스코드 계획.md" 127행 — 레거시 코드베이스 자체에서도
-- 완결된 기능으로 동작하지 않았고, 신규 시스템은 이를 LogEventCodec/GateLogService로
-- 대체했다). 이 파일이 "실측"으로 옮겨온 개발 DB(192.168.0.26)에는 그 레거시 테이블이 여전히
-- 남아 있어 인덱스가 실존했지만, 이 저장소의 어떤 마이그레이션도 tb_data_rcv_log를 만들지
-- 않으므로, 이 테이블이 없는 환경(신규 로컬/CI 등)에서 아래 블록이 "Table doesn't exist"로
-- V36 전체를 깨뜨리고 있었다(2026-09-03 로컬 DB를 처음부터 재구성하며 발견). 테이블 존재
-- 여부를 먼저 확인해 없으면 그 블록만 건너뛴다 — 이미 테이블이 있는 환경(개발 DB)에서는 이
-- 가드가 없을 때와 완전히 동일한 SQL을 실행하므로 실행 경로 자체는 바뀌지 않는다.
--
-- [2026-09-03 수정 2 — tb_data_rcv_anal.user_mode_cd/security_mode_cd 컬럼 생성을 이 파일로 흡수]
-- 애초에 별도 파일 V35_1__add_tb_data_rcv_anal_mode_cd_columns.sql로 분리했었다("이미 적용된
-- 마이그레이션은 수정하지 않는다"는 원칙, V33 KDoc 참고). 그런데 V35.1은 V36(=36)보다 낮은
-- 버전이라, 이미 V36까지 적용된 환경(192.168.0.26)에 나중에 V35.1이 새로 나타나면 Flyway가
-- `Detected resolved migration not applied to database: 35.1`로 검증에 실패한다
-- (out-of-order 마이그레이션 — application.yml에 spring.flyway.out-of-order=true가 없으므로
-- 기본값 false로 막힌다). 이 오류는 flyway repair로 해결되지 않는다(repair는 체크섬/실패
-- 이력만 정리할 뿐, 건너뛴 버전을 적용해주지 않는다).
--
-- 위 tb_data_rcv_log 가드 추가로 인해 V36은 이번 수정으로 어차피 체크섬이 바뀌어
-- 기적용 환경에서 flyway repair가 필요한 상태였다 — 그렇다면 컬럼 생성도 별도 버전으로
-- 쪼개지 말고 이 파일에 합쳐 넣는 편이 낫다: repair는 한 번만 하면 되고, 버전 사이 구멍이
-- 생기지 않아 out-of-order 문제 자체가 사라진다. 컬럼은 인덱스보다 먼저 생성해야 하므로
-- (V36이 이 컬럼들에 IDX_USER_MODE/IDX_SECURITY_MODE 인덱스를 건다) 프로시저 정의보다 앞에
-- 최상위 문장으로 둔다. 경위: 86d672f(2026-08-26)에서 DataReceiveAnalysis 엔티티에
-- userModeCd/securityModeCd 매핑(@Column(name = "user_mode_cd")/@Column(name =
-- "security_mode_cd"))을 추가하고 테스트용 schema.sql(domain/web)에는 컬럼을 반영했지만,
-- 정작 실제 환경에 적용되는 이 db/migration 아래에는 해당 컬럼을 만드는 마이그레이션을
-- 빠뜨렸다 — 개발 DB(192.168.0.26)에는 그 커밋 검증 과정에서 수동으로 ALTER를 해뒀던 것으로
-- 보이고, 그 수동 변경이 Flyway 이력에는 전혀 남지 않아 이 저장소만으로는 재현 불가능한
-- 상태였다(2026-09-03 로컬 DB를 처음부터 재구성하며 "Key column 'user_mode_cd' doesn't
-- exist" 오류로 발견). 컬럼 스펙은 테스트 schema.sql(securance-domain/src/test/resources/
-- schema.sql, securance-web/src/test/resources/schema.sql)과 동일하게 맞춘다: desc_user_mode/
-- desc_security_mode 바로 뒤에 VARCHAR(10) NULL로 추가.
--
-- 주의: `ADD COLUMN IF NOT EXISTS`는 컬럼이 이미 있으면 타입/길이/NULL 제약을 검증하지 않고
-- 조용히 건너뛴다 — 개발 DB(192.168.0.26)에 수동으로 만들어진 컬럼의 실제 타입을 이 마이그
-- 레이션이 강제하지는 못한다는 뜻이다. 여기서는 위 엔티티 매핑(length = 10)과 테스트
-- schema.sql이 가리키는 스펙을 그대로 신뢰한다.
--
-- ⚠️ 배포 영향: 이 파일을 수정했으므로, 이미 (수정 전) V36을 성공 적용한 환경(192.168.0.26
-- 등)에서는 체크섬 불일치로 기동이 막힌다. 그 환경에서는 `flyway repair` 실행 후 재기동해야
-- 한다(컬럼/인덱스는 모두 IF NOT EXISTS 가드라 repair 후 재적용해도 no-op).
-- ============================================================================
ALTER TABLE tb_data_rcv_anal
    ADD COLUMN IF NOT EXISTS user_mode_cd VARCHAR(10) NULL AFTER desc_user_mode,
    ADD COLUMN IF NOT EXISTS security_mode_cd VARCHAR(10) NULL AFTER desc_security_mode;

DROP PROCEDURE IF EXISTS _v36_sync_dev_baseline_indexes;

DELIMITER $$
CREATE PROCEDURE _v36_sync_dev_baseline_indexes()
BEGIN
    DECLARE v36_rcv_log_exists INT DEFAULT 0;

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
    -- 애플리케이션이 쓰지 않는 죽은 레거시 테이블이라 새 환경에는 존재하지 않을 수 있다
    -- (파일 상단 2026-09-03 수정 1 참고) — 테이블이 있는 환경에서만 인덱스를 맞춘다.
    SELECT COUNT(*) INTO v36_rcv_log_exists
        FROM information_schema.tables
        WHERE table_schema = DATABASE() AND table_name = 'tb_data_rcv_log';

    IF v36_rcv_log_exists > 0 THEN
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
DROP PROCEDURE IF EXISTS _v36_sync_dev_baseline_indexes;
