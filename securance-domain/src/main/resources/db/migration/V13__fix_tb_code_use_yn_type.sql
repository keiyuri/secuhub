-- V13 — tb_code.use_yn 타입 정합화 (Hibernate ddl-auto=validate 실패 수정, 2026-08-12)
--
-- CodeMaster 엔티티는 tb_code.use_yn만 다른 테이블(YnConverter로 char(1) 'Y'/'N' ↔ Boolean
-- 변환)과 달리 Kotlin Boolean에 직접 매핑한다(CodeMaster.kt 주석 — DB가 tinyint(1)이라고 가정).
-- 그러나 실제 개발 DB의 컬럼은 `tinyint(3) unsigned`였다(레거시 스키마 원본, 다른 레거시
-- TINYINT 컬럼들과 동일한 폭 관례 — V8/V11에서 겪은 것과 같은 종류의 불일치). Hibernate
-- 7.4의 MariaDB 방언은 Boolean 매핑에 정확히 `bit` 타입을 기대하므로 스키마 검증이 실패한다:
--   "wrong column type encountered in column [use_yn] in table [tb_code];
--    found [tinyint unsigned (Types#TINYINT)], but expecting [bit (Types#BOOLEAN)]"
--
-- 현재 데이터는 57행 전부 1이라(0 값 없음) 타입 축소가 안전하다.
ALTER TABLE tb_code
    MODIFY COLUMN use_yn BIT(1) NOT NULL DEFAULT b'1';
