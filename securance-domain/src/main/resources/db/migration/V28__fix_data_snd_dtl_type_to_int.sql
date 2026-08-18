-- ============================================================================
-- V28 — tb_data_snd.dtl_type을 레거시 TINYINT UNSIGNED에서 엔티티가 기대하는 INT로 정정
-- (2026-08-18, dev DB(레거시 GateControl 스키마 복사본) 대상 Flyway 전체 마이그레이션 중 재현)
--
-- [경위] V23/V27과 동일한 패턴 — 이 dev DB 계열에는 tb_data_snd.dtl_type이 Flyway 이전부터
-- TINYINT UNSIGNED로 이미 존재했고, 이 저장소의 마이그레이션 이력에는 이 컬럼을 새로 만들거나
-- 타입을 바꾸는 마이그레이션이 없었다(항상 존재를 전제). DataSend 엔티티는 `dtlType: Int`로
-- 매핑하므로, Hibernate `ddl-auto: validate`가 "found [tinyint unsigned], but expecting
-- [integer]"로 실패한다. 같은 테이블의 다른 dtl_*/loc_id/grp_id 컬럼들은 이미 INT/BIGINT
-- 계열이라 문제없다(재현 시 확인, information_schema 조회).
--
-- [조치] V23/V27과 동일하게 값 손실 없이 MODIFY COLUMN으로 타입만 넓힌다(TINYINT UNSIGNED →
-- INT는 상위 호환이라 기존 값 1~4가 그대로 보존됨). ALGORITHM 절은 생략한다(V23에서 확인된 대로
-- MariaDB 11.8이 이런 타입 변경에 ALGORITHM=INPLACE를 거부하므로 자동 선택에 맡긴다).
-- ============================================================================

ALTER TABLE tb_data_snd
    MODIFY COLUMN dtl_type INT NOT NULL DEFAULT 1;
