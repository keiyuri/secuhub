-- V18 — tb_time.reg_date 타입 정합화 (Hibernate ddl-auto=validate 실패 수정, 2026-08-12)
--
-- GateTimeZone 엔티티는 regDate를 LocalDateTime으로 매핑하지만, 레거시 tb_time.reg_date는
-- 'yyyyMMddHHmmss' 형식의 VARCHAR(20)였다(V1 베이스라인, 기본값도 date_format()으로 같은
-- 형식을 생성). 기존 9건 전부 그 형식과 일치함을 확인했다 — STR_TO_DATE로 안전하게 변환한다.
-- 컬럼 삭제 후 재생성하면 순간적으로 NULL 상태가 되므로, 임시 컬럼에 변환값을 채운 뒤 old
-- 컬럼을 교체하는 방식으로 데이터 유실 없이 진행한다.
ALTER TABLE tb_time
    ADD COLUMN reg_date_new DATETIME(6) NULL AFTER reg_date;

UPDATE tb_time
SET reg_date_new = STR_TO_DATE(reg_date, '%Y%m%d%H%i%s');

ALTER TABLE tb_time
    DROP COLUMN reg_date,
    CHANGE COLUMN reg_date_new reg_date DATETIME(6) NOT NULL;
