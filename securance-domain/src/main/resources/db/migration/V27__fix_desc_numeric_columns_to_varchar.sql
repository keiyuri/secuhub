-- ============================================================================
-- V27 — tb_data_rcv_anal의 레거시 숫자형 desc_* 컬럼을 엔티티가 기대하는 VARCHAR로 정정
-- (2026-08-18, dev DB(레거시 GateControl 스키마 복사본) 대상 Flyway 전체 마이그레이션
-- 적용 중 재현)
--
-- [경위] V8(align entity schema)이 `ADD COLUMN IF NOT EXISTS`로 desc_* 컬럼들의 존재만
-- 보장했는데, 이 dev DB(및 이와 같은 계열의 레거시 스키마)에는 이 컬럼들이 Flyway 이전부터
-- 이미 존재했다(V8 로그의 "Duplicate column name" 경고로 확인) — 즉 `IF NOT EXISTS`가
-- no-op으로 넘어가 레거시 원본 타입(TINYINT UNSIGNED/INT)이 그대로 남았다. secuhub의
-- DataReceiveAnalysis 엔티티는 이 6개 컬럼을 VARCHAR로 매핑하므로, Hibernate
-- `ddl-auto: validate`가 기동 시점에 "wrong column type encountered ... found
-- [tinyint unsigned], but expecting [varchar]"로 실패한다.
--
-- [조치] V23과 동일하게 컬럼을 DROP/재생성하지 않고 MODIFY COLUMN으로 타입만 바꾼다 —
-- 기존 숫자 값은 MariaDB가 문자열로 변환해 보존한다(예: 5 → '5'), GateControl 쪽에서 이
-- 컬럼들에 여전히 숫자를 쓰더라도 VARCHAR가 숫자 문자열을 그대로 받아들이므로 호환된다.
-- ALGORITHM 절은 명시하지 않는다 — V23에서 확인된 대로 MariaDB 11.8은 이런 타입 변경에
-- ALGORITHM=INPLACE를 거부하고 COPY를 요구하므로, 생략해 MariaDB가 자동으로 고르게 한다.
-- ============================================================================

ALTER TABLE tb_data_rcv_anal
    MODIFY COLUMN desc_data_info_length VARCHAR(10) NOT NULL DEFAULT '',
    MODIFY COLUMN desc_gate_lane_count  VARCHAR(20) NOT NULL DEFAULT '',
    MODIFY COLUMN desc_gate_lane_number VARCHAR(20) NOT NULL DEFAULT '',
    MODIFY COLUMN desc_inout_time       VARCHAR(20) NOT NULL DEFAULT '',
    MODIFY COLUMN desc_user_count       VARCHAR(20) NOT NULL DEFAULT '',
    MODIFY COLUMN desc_total_count      VARCHAR(20) NOT NULL DEFAULT '';
