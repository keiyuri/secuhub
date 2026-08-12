-- V19 — tb_time.timezone_hex_data 타입 정합화 (Hibernate ddl-auto=validate 실패 수정, 2026-08-12)
--
-- GateTimeZone.timezoneHexData는 @Lob String이라 Hibernate가 TEXT 계열(TINYTEXT)을 기대하지만,
-- 레거시 컬럼은 VARCHAR(500)였다. 현재 최대 길이가 77바이트뿐이라 TINYTEXT(최대 255바이트)로
-- 안전하게 축소 겸 타입 변경한다.
ALTER TABLE tb_time
    MODIFY COLUMN timezone_hex_data TINYTEXT NOT NULL COMMENT 'Timezone Data';
