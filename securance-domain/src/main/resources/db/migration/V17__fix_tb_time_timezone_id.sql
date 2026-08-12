-- V17 — tb_time.timezone_id 타입 정합화 (Hibernate ddl-auto=validate 실패 수정, 2026-08-12)
--
-- GateTimeZone 엔티티는 다른 엔티티들의 IDENTITY PK 관례(GateDetail.dtlId, GateGroup.grpId 등)와
-- 마찬가지로 timezone_id를 Long으로 매핑하지만, 레거시 tb_time(V3에서 "already exists"로 확인된
-- 사전 존재 테이블)은 INT UNSIGNED AUTO_INCREMENT였다. 행이 9건뿐이라 폭 확장이 즉시 끝난다.
ALTER TABLE tb_time
    MODIFY COLUMN timezone_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Timezone 아이디';
