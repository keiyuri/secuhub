-- ============================================================================
-- tb_code.disp_order 컬럼 타입 수정
--
-- V1에서 TINYINT UNSIGNED로 생성했으나, JPA 엔티티(CodeMaster.displayOrder: Int)는
-- INTEGER를 기대해 Hibernate 스키마 검증(ddl-auto=validate)이 실패한다.
-- TINYINT UNSIGNED(0~255)는 표시 순서값 범위를 불필요하게 제한하므로 INT로 확장한다.
-- ============================================================================

ALTER TABLE tb_code
    MODIFY COLUMN disp_order INT NOT NULL DEFAULT 0 COMMENT '표시 순서';
