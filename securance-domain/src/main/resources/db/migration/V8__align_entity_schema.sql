-- ============================================================================
-- V8 — 엔티티 ↔ 실제 스키마 정합화 (2차 스프린트 "실 DB 연동 검증" 결과 반영)
-- (원래 V2로 작성됐으나 기존 V2__fix_dashboard_query_indexes.sql과 버전 번호가 충돌해
--  Flyway 적용 순서를 그대로 유지한 채 V8로 재번호했다. 2026-08-11 B1 이슈 수정.)
--
-- 로컬 MariaDB 11.8에 V1을 적용하고 Spring Boot 컨텍스트를 기동해 Hibernate
-- ddl-auto=validate로 검증한 결과 발견된 불일치를 수정한다.
--
-- 2026-08-12 수정: 실제 개발 DB(192.168.0.91)의 tb_data_rcv_anal(65만+ 행)에 대한
-- ADD COLUMN이 Flyway 소켓 타임아웃(당시 120초)을 넘겨 커넥션이 끊기며 스크립트가 중간에
-- 멈췄다(스키마 히스토리에는 기록되지 못한 채 DDL만 일부 반영된 상태). 재기동 시 스크립트를
-- 처음부터 다시 실행해도 안전하도록 ADD COLUMN 구문에 IF NOT EXISTS를 붙여 멱등하게 만든다
-- (MariaDB 10.0.2+ 지원). MODIFY COLUMN은 같은 정의를 재적용해도 오류 없이 no-op이라
-- 별도 가드가 필요 없다. 아울러 tb_data_snd는 이 DB에서 dtl_type/loc_id/grp_id/snd_data_tp가
-- V1부터 이미 존재했음이 확인되어(마이그레이션 작성 당시 검증한 로컬 DB와 차이), 해당
-- ADD COLUMN도 IF NOT EXISTS로 방어한다.
--
-- 1) TINYINT(UNSIGNED) 폭 확장 → INT(UNSIGNED)
--    레인 번호/게이트 타입/연결 방식 등은 V1에서 레거시 DDL 그대로 TINYINT로 포팅했지만,
--    모든 엔티티는 처음부터 Kotlin `Int`로 매핑돼 있었다(서비스 코드 전반이 Int 기준으로
--    작성됨). Hibernate validate는 TINYINT != INTEGER로 보고 스키마 검증에 실패한다.
--    엔티티/서비스 쪽 타입을 Byte/Short로 좁히는 대신, 저장 비용이 무시할 만한 이 컬럼들의
--    폭을 넓혀 기존 Kotlin 타입과 맞춘다(레인당 최대 255개 한계도 실질적으로 사라짐).
-- 2) tb_data_rcv_anal — GateStatusAnalyzer(2차 스프린트)가 채우는 desc_*/anal_* 컬럼 추가
-- 3) tb_data_snd — DataSend 엔티티가 요구하는 dtl_type/loc_id/grp_id/snd_data_tp 추가 및
--    NOT NULL DEFAULT '' 정합화(레거시 스키마 원본이 NOT NULL인데 V1에 DEFAULT를 빼먹었었다)
-- ============================================================================

-- ── 1) TINYINT → INT 폭 확장 ─────────────────────────────────────────────
ALTER TABLE tb_code MODIFY COLUMN disp_order INT UNSIGNED NOT NULL DEFAULT 0;

ALTER TABLE tb_gate_grp
    MODIFY COLUMN lane_cnt  INT UNSIGNED NOT NULL DEFAULT 1,
    MODIFY COLUMN dtl_type  INT NOT NULL,
    MODIFY COLUMN link_type INT NOT NULL DEFAULT 1;

ALTER TABLE tb_gate_dtl
    MODIFY COLUMN dtl_lane_no  INT UNSIGNED NOT NULL,
    MODIFY COLUMN dtl_type     INT NOT NULL,
    MODIFY COLUMN connect_type INT NOT NULL DEFAULT 1;

ALTER TABLE tb_net_state
    MODIFY COLUMN dtl_lane_no INT UNSIGNED NOT NULL,
    MODIFY COLUMN dtl_type    INT NULL;

ALTER TABLE tb_data_rcv
    MODIFY COLUMN dtl_lane_no INT UNSIGNED NOT NULL,
    MODIFY COLUMN dtl_type    INT NULL;

ALTER TABLE tb_data_rcv_fail
    MODIFY COLUMN dtl_lane_no INT UNSIGNED NULL;

ALTER TABLE tb_data_rcv_ack
    MODIFY COLUMN dtl_lane_no INT UNSIGNED NOT NULL;

ALTER TABLE tb_data_rcv_anal
    MODIFY COLUMN dtl_lane_no INT UNSIGNED NOT NULL,
    MODIFY COLUMN dtl_type    INT NULL,
    MODIFY COLUMN err_type    INT NULL;

ALTER TABLE tb_data_snd
    MODIFY COLUMN dtl_lane_no INT UNSIGNED NOT NULL;

ALTER TABLE tb_opr_status
    MODIFY COLUMN dtl_lane_no INT UNSIGNED NOT NULL;

-- ── 2) tb_data_rcv_anal — GateStatusAnalyzer 출력 컬럼 추가 ────────────────
ALTER TABLE tb_data_rcv_anal
    ADD COLUMN IF NOT EXISTS anal_type              VARCHAR(5)  NOT NULL DEFAULT 'B' COMMENT '분석 데이터 형식 (레거시 트리거는 항상 B)' AFTER anal_tp,
    ADD COLUMN IF NOT EXISTS obj_cd                 VARCHAR(10) NOT NULL DEFAULT '' COMMENT '패킷 오브젝트 코드(4D/4E/4C 등)' AFTER grp_id,
    ADD COLUMN IF NOT EXISTS rcv_raw                LONGTEXT    NULL COMMENT '원본 수신 패킷 16진 문자열' AFTER obj_cd,
    ADD COLUMN IF NOT EXISTS anal_header            VARCHAR(100) NULL AFTER rcv_raw,
    ADD COLUMN IF NOT EXISTS anal_data              LONGTEXT    NULL AFTER anal_header,
    ADD COLUMN IF NOT EXISTS anal_tail              VARCHAR(20) NULL AFTER anal_data,
    ADD COLUMN IF NOT EXISTS desc_data_info_length  VARCHAR(10) NOT NULL DEFAULT '' AFTER anal_tail,
    ADD COLUMN IF NOT EXISTS desc_gate_name         VARCHAR(80) NOT NULL DEFAULT '' AFTER desc_data_info_length,
    ADD COLUMN IF NOT EXISTS desc_gate_ip           VARCHAR(20) NOT NULL DEFAULT '' AFTER desc_gate_name,
    ADD COLUMN IF NOT EXISTS desc_gate_lane_count   VARCHAR(20) NOT NULL DEFAULT '' AFTER desc_gate_ip,
    ADD COLUMN IF NOT EXISTS desc_gate_lane_number  VARCHAR(20) NOT NULL DEFAULT '' AFTER desc_gate_lane_count,
    ADD COLUMN IF NOT EXISTS desc_gate_type         VARCHAR(50) NOT NULL DEFAULT '' AFTER desc_gate_lane_number,
    ADD COLUMN IF NOT EXISTS desc_user_mode         VARCHAR(30) NOT NULL DEFAULT '' AFTER desc_gate_type,
    ADD COLUMN IF NOT EXISTS desc_security_mode     VARCHAR(20) NOT NULL DEFAULT '' AFTER desc_user_mode,
    ADD COLUMN IF NOT EXISTS desc_inout_time        VARCHAR(20) NOT NULL DEFAULT '' AFTER desc_security_mode,
    ADD COLUMN IF NOT EXISTS desc_user_count        VARCHAR(20) NOT NULL DEFAULT '' AFTER desc_inout_time,
    ADD COLUMN IF NOT EXISTS desc_total_count       VARCHAR(20) NOT NULL DEFAULT '' AFTER desc_user_count,
    ADD COLUMN IF NOT EXISTS desc_motor_count       INT         NOT NULL DEFAULT 0 AFTER desc_operation08,
    ADD COLUMN IF NOT EXISTS desc_master_in_total   INT         NOT NULL DEFAULT 0 AFTER desc_motor_count;

-- ── 3) tb_data_snd — DataSend 엔티티 정합화 ────────────────────────────────
ALTER TABLE tb_data_snd
    ADD COLUMN IF NOT EXISTS dtl_type    INT          NOT NULL DEFAULT 1 AFTER dtl_lane_no,
    ADD COLUMN IF NOT EXISTS loc_id      BIGINT UNSIGNED NOT NULL DEFAULT 0 AFTER dtl_id,
    ADD COLUMN IF NOT EXISTS grp_id      BIGINT UNSIGNED NOT NULL DEFAULT 0 AFTER loc_id,
    ADD COLUMN IF NOT EXISTS snd_data_tp VARCHAR(20)  NOT NULL DEFAULT '' AFTER snd_type_cd;

ALTER TABLE tb_data_snd
    MODIFY COLUMN snd_user    VARCHAR(20)  NOT NULL DEFAULT '',
    MODIFY COLUMN snd_server  VARCHAR(20)  NOT NULL DEFAULT '',
    MODIFY COLUMN snd_type_cd VARCHAR(20)  NOT NULL DEFAULT '',
    MODIFY COLUMN snd_raw     LONGTEXT     NOT NULL,
    MODIFY COLUMN snd_header  VARCHAR(100) NOT NULL DEFAULT '',
    MODIFY COLUMN snd_data    LONGTEXT     NOT NULL,
    MODIFY COLUMN snd_tail    VARCHAR(20)  NOT NULL DEFAULT '';
