-- V24 — tb_opr_status_outbox.processed 타입 정합화 (Hibernate ddl-auto=validate 실패 수정, 2026-08-14)
--
-- 작성 당시 이 워크트리 기준 다음 빈 번호가 V21이라 그렇게 만들었으나, 커밋 시점에는 mains가
-- 이미 V21~V23을 다른 목적으로 선점하고 있어(작업일지 0022 확인) V24로 변경했다.
--
-- 코드 리뷰 지적: OprStatusOutbox.processed(Kotlin Boolean, YnConverter를 쓰지 않고 직접 매핑)가
-- V12에서 `TINYINT(1)`로 생성됐다 — 이 프로젝트가 V13(tb_code.use_yn)에서 이미 겪고 문서화한 것과
-- 동일한 실수다: Hibernate 7.4의 MariaDB 방언은 Kotlin Boolean 컬럼에 정확히 `BIT` 타입을 요구하며,
-- TINYINT로 두면 `ddl-auto=validate` 스키마 검증이 실패한다
-- ("wrong column type encountered in column [processed] ... found [tinyint(Types#TINYINT)],
--   but expecting [bit (Types#BOOLEAN)]").
--
-- V12 도입 이후(2026-08-12) 이 테이블에 쌓인 값은 0/1만 존재하므로(다른 값을 넣는 코드 경로 없음)
-- BIT(1)로의 타입 축소가 안전하다(V13과 동일한 근거).
ALTER TABLE tb_opr_status_outbox
    MODIFY COLUMN processed BIT(1) NOT NULL DEFAULT b'0';
