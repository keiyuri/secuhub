-- ============================================================================
-- V21 — 엔티티 nullable=false 선언과 실제 DB NULL 허용 컬럼 정합화 (2026-08-13 코드 리뷰)
--
-- tb_data_rcv_anal.{rcv_date,rcv_id,dtl_type,dtl_no,dtl_id}와 tb_data_snd.dtl_id는
-- V1부터 NULL을 허용해왔지만, 대응하는 DataReceiveAnalysis/DataSend 엔티티는 처음부터
-- Kotlin non-null 타입(String/Int/Long)에 nullable=false로 매핑돼 있었다(애플리케이션은
-- 항상 기본값을 채워 INSERT한다). V8이 이미 이 두 테이블의 다른 컬럼들(dtl_type/loc_id/grp_id
-- 등)에 대해 같은 이유로 NOT NULL DEFAULT 정합화를 했으나 이 컬럼들은 누락되어 있었다.
--
-- 레거시에서 이관됐거나 별도 배치로 직접 INSERT된 행 중 실제 NULL 값이 남아있을 가능성을
-- 배제할 수 없으므로, NOT NULL 제약을 걸기 전에 먼저 기존 NULL 행을 엔티티 기본값으로
-- 백필한다(V8 관례와 동일하게 IF NOT EXISTS/UPDATE 선행으로 재실행 안전성을 확보).
-- ============================================================================

-- ── tb_data_rcv_anal ─────────────────────────────────────────────────────
UPDATE tb_data_rcv_anal SET rcv_date = '' WHERE rcv_date IS NULL;
UPDATE tb_data_rcv_anal SET rcv_id = 0 WHERE rcv_id IS NULL;
UPDATE tb_data_rcv_anal SET dtl_type = 1 WHERE dtl_type IS NULL;
UPDATE tb_data_rcv_anal SET dtl_no = 1 WHERE dtl_no IS NULL;
UPDATE tb_data_rcv_anal SET dtl_id = 0 WHERE dtl_id IS NULL;

ALTER TABLE tb_data_rcv_anal
    MODIFY COLUMN rcv_date VARCHAR(20) NOT NULL DEFAULT '',
    MODIFY COLUMN rcv_id   BIGINT UNSIGNED NOT NULL DEFAULT 0,
    MODIFY COLUMN dtl_type INT NOT NULL DEFAULT 1,
    MODIFY COLUMN dtl_no   INT UNSIGNED NOT NULL DEFAULT 1,
    MODIFY COLUMN dtl_id   BIGINT UNSIGNED NOT NULL DEFAULT 0;

-- ── tb_data_snd ──────────────────────────────────────────────────────────
UPDATE tb_data_snd SET dtl_id = 0 WHERE dtl_id IS NULL;

ALTER TABLE tb_data_snd
    MODIFY COLUMN dtl_id BIGINT UNSIGNED NOT NULL DEFAULT 0;
