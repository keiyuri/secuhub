-- ============================================================================
-- tb_data_snd/tb_opr_status/tb_opr_status_outbox 컬럼 누락 수정
-- (2026-09-08, 사용자 요청 — tb_net_state.mod_date, tb_data_snd의 snd_server_cd/reg_user/mod_user,
--  tb_opr_status의 dtl_no/opr_lane_no/opr_user_mode_desc/opr_security_mode_desc 데이터 누락·이상 점검)
--
-- 운영 DB(192.168.0.26:28031, securance_gate) 실측 결과 이 세 테이블은 V1 baseline이 기록한
-- CREATE TABLE보다 실제로 컬럼이 더 많다 — V1이 실행되지 않고 BASELINE으로만 마킹된 공유 DB라서
-- (docs/flyway-migration-recovery.md), V1 작성 당시 이 컬럼들의 존재를 놓쳤던 것으로 보인다.
-- 그 결과 엔티티도 이 컬럼들을 매핑하지 못했고(DataSend/OprStatus), Kotlin 신규 서버 경로가 쓰는
-- 행은 레거시 화면/SP가 쓰던 행과 달리 이 컬럼들이 항상 비어(NULL/기본값) 있었다.
--
-- V2와 동일한 패턴을 따른다: BASELINE 환경(공유 운영/개발 DB)에서는 이 컬럼들이 이미 존재해야
-- 정상이므로 information_schema로 확인만 하고 ALTER하지 않는다(자동 ALTER 금지 원칙). 이미
-- 존재하지 않으면(=드리프트) 마이그레이션을 실패시켜 DBA가 수동 조치하게 한다. 신규(비-BASELINE)
-- DB에서는 V1의 CREATE TABLE이 실제로 실행됐으므로 이 컬럼들이 없다 — 운영 DB 실측 타입 그대로
-- ALTER로 추가한다.
--
-- 컬럼 자체의 누락을 메우는 것과 별개로, 실제 데이터 적재 로직 수정(OprStatusPersister.kt,
-- NetStateRepository.kt, DataSend 생성 지점들, GateControlDispatcher.kt)은 이 세션에서 함께
-- 반영했다 — 컬럼만 추가하고 쓰는 코드가 없으면 여전히 비어 있을 것이기 때문이다.
--
-- tb_opr_status_outbox.dtl_no는 레거시 유산이 아니라 이 프로젝트가 만든 테이블에 이번에 새로
-- 도입하는 컬럼이다(2026-08-12 도입한 테이블) — 1)/2)와 달리 "BASELINE DB에는 원래 있었을
-- 것"이라는 전제가 성립하지 않으므로 BASELINE 여부와 무관하게 컬럼이 없으면 항상 ALTER한다
-- (최초 버전은 1)/2)와 같은 패턴을 그대로 복사해 BASELINE 환경에서 컬럼이 없으면 마이그레이션을
-- 실패시켰는데, Codex 적대적 리뷰가 이를 "코드/마이그레이션만 배포하는 정상 환경에서 Flyway가
-- 막혀 기동조차 안 되는 배포 차단 결함"으로 지적해 수정했다 — 아래 3) 참고).
-- ============================================================================

SET @is_baseline_env = (
    SELECT COUNT(*) FROM flyway_schema_history
    WHERE version = '1' AND type = 'BASELINE'
);

-- ---------------------------------------------------------------------------
-- 1) tb_data_snd: dtl_no / snd_server_cd / reg_user / mod_user
-- ---------------------------------------------------------------------------
SET @snd_matched = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'tb_data_snd'
      AND column_name IN ('dtl_no', 'snd_server_cd', 'reg_user', 'mod_user')
);

SET @v4_snd_alter_sql = IF(@is_baseline_env = 0,
    'ALTER TABLE `tb_data_snd`
       ADD COLUMN `dtl_no` int(11) unsigned DEFAULT 1 AFTER `dtl_type`,
       ADD COLUMN `snd_server_cd` varchar(20) NOT NULL DEFAULT '''' COMMENT ''send server code'' AFTER `snd_server`,
       ADD COLUMN `reg_user` varchar(20) DEFAULT NULL AFTER `version`,
       ADD COLUMN `mod_user` varchar(20) DEFAULT NULL AFTER `reg_date`',
    'DO 0'
);
PREPARE v4_snd_alter_stmt FROM @v4_snd_alter_sql;
EXECUTE v4_snd_alter_stmt;
DEALLOCATE PREPARE v4_snd_alter_stmt;

SET @v4_snd_guard_create_sql = IF(@is_baseline_env > 0 AND @snd_matched < 4,
    'CREATE OR REPLACE PROCEDURE `_v4_fail_on_baseline_drift_data_snd`()
       SIGNAL SQLSTATE ''45000''
       SET MESSAGE_TEXT = ''V4: tb_data_snd의 dtl_no/snd_server_cd/reg_user/mod_user 중 일부가 BASELINE DB에 없습니다 - 드리프트로 보입니다. DBA가 information_schema.columns로 실측 후 수동 조치하세요.''',
    'DO 0'
);
PREPARE v4_snd_guard_create_stmt FROM @v4_snd_guard_create_sql;
EXECUTE v4_snd_guard_create_stmt;
DEALLOCATE PREPARE v4_snd_guard_create_stmt;

SET @v4_snd_guard_call_sql = IF(@is_baseline_env > 0 AND @snd_matched < 4,
    'CALL `_v4_fail_on_baseline_drift_data_snd`()',
    'DO 0'
);
PREPARE v4_snd_guard_call_stmt FROM @v4_snd_guard_call_sql;
EXECUTE v4_snd_guard_call_stmt;
DEALLOCATE PREPARE v4_snd_guard_call_stmt;

-- ---------------------------------------------------------------------------
-- 2) tb_opr_status: opr_lane_no / opr_user_mode_desc / opr_security_mode_desc / mod_user / opr_start_date
-- ---------------------------------------------------------------------------
SET @opr_matched = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'tb_opr_status'
      AND column_name IN ('opr_lane_no', 'opr_user_mode_desc', 'opr_security_mode_desc', 'mod_user', 'opr_start_date')
);

SET @v4_opr_alter_sql = IF(@is_baseline_env = 0,
    'ALTER TABLE `tb_opr_status`
       ADD COLUMN `opr_lane_no` int(11) unsigned DEFAULT NULL COMMENT ''gate lane number'' AFTER `grp_id`,
       ADD COLUMN `opr_user_mode_desc` varchar(50) DEFAULT NULL AFTER `opr_user_mode`,
       ADD COLUMN `opr_security_mode_desc` varchar(50) DEFAULT NULL AFTER `opr_security_mode`,
       ADD COLUMN `opr_start_date` varchar(20) DEFAULT NULL COMMENT ''최초 운영 시작일'' AFTER `opr_door_before`,
       ADD COLUMN `mod_user` varchar(20) NOT NULL DEFAULT ''service'' AFTER `reg_date`',
    'DO 0'
);
PREPARE v4_opr_alter_stmt FROM @v4_opr_alter_sql;
EXECUTE v4_opr_alter_stmt;
DEALLOCATE PREPARE v4_opr_alter_stmt;

SET @v4_opr_guard_create_sql = IF(@is_baseline_env > 0 AND @opr_matched < 5,
    'CREATE OR REPLACE PROCEDURE `_v4_fail_on_baseline_drift_opr_status`()
       SIGNAL SQLSTATE ''45000''
       SET MESSAGE_TEXT = ''V4: tb_opr_status의 opr_lane_no/opr_user_mode_desc/opr_security_mode_desc/mod_user/opr_start_date 중 일부가 BASELINE DB에 없습니다 - 드리프트로 보입니다. DBA가 information_schema.columns로 실측 후 수동 조치하세요.''',
    'DO 0'
);
PREPARE v4_opr_guard_create_stmt FROM @v4_opr_guard_create_sql;
EXECUTE v4_opr_guard_create_stmt;
DEALLOCATE PREPARE v4_opr_guard_create_stmt;

SET @v4_opr_guard_call_sql = IF(@is_baseline_env > 0 AND @opr_matched < 5,
    'CALL `_v4_fail_on_baseline_drift_opr_status`()',
    'DO 0'
);
PREPARE v4_opr_guard_call_stmt FROM @v4_opr_guard_call_sql;
EXECUTE v4_opr_guard_call_stmt;
DEALLOCATE PREPARE v4_opr_guard_call_stmt;

-- ---------------------------------------------------------------------------
-- 3) tb_opr_status_outbox: dtl_no
--
-- [Codex 적대적 리뷰 지적, 2026-09-08] 이 컬럼은 레거시 유산이 아니라 이 프로젝트가 만든
-- 테이블(2026-08-12 도입)에 이번에 새로 추가하는 것이므로, 1)/2)와 달리 "BASELINE DB는 이미
-- 컬럼이 있을 것"이라는 전제 자체가 성립하지 않는다 — BASELINE 여부와 무관하게 모든 환경에서
-- 아직 컬럼이 없으면(예: 이번 세션에서 수동 ALTER한 공유 개발 DB 밖의 다른 스테이징/운영 DB)
-- 이 마이그레이션이 직접 추가해야 정상 배포된다. 최초 버전은 1)/2)와 같은
-- "BASELINE이면 검증만, 신규 DB만 ALTER" 패턴을 그대로 복사해, 컬럼이 없는 BASELINE 환경에서는
-- Flyway가 마이그레이션 실패로 기동을 막아버리는 배포 차단 결함이 있었다 — 컬럼 존재 여부만으로
-- 멱등하게 ALTER하도록 고친다(이미 컬럼이 있으면 no-op, 없으면 어떤 환경이든 추가).
-- ---------------------------------------------------------------------------
SET @outbox_matched = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'tb_opr_status_outbox'
      AND column_name = 'dtl_no'
);

SET @v4_outbox_alter_sql = IF(@outbox_matched = 0,
    'ALTER TABLE `tb_opr_status_outbox`
       ADD COLUMN `dtl_no` int(11) NOT NULL DEFAULT 1 AFTER `dtl_type`',
    'DO 0'
);
PREPARE v4_outbox_alter_stmt FROM @v4_outbox_alter_sql;
EXECUTE v4_outbox_alter_stmt;
DEALLOCATE PREPARE v4_outbox_alter_stmt;
