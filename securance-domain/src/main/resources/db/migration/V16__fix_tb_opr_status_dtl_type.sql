-- V16 — tb_opr_status.dtl_type 타입 정합화 (Hibernate ddl-auto=validate 실패 수정, 2026-08-12)
--
-- V11__opr_status_write_columns.sql은 레거시 저장 프로시저 DDL과 맞추려고 dtl_type을
-- TINYINT UNSIGNED로 뒀지만, OprStatus 엔티티는 다른 모든 dtl_type 필드(GateDetail/NetState/
-- DataReceive 등, V8에서 이미 INT로 통일)와 마찬가지로 Kotlin Int로 매핑돼 있다. 이 프로젝트의
-- 일관된 컨벤션(V8__align_entity_schema.sql)을 따라 여기서도 INT UNSIGNED로 폭을 넓힌다.
ALTER TABLE tb_opr_status
    MODIFY COLUMN dtl_type INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '게이트 종류(1:Speed,2:Flap,3:Turn,4:Fast)';
