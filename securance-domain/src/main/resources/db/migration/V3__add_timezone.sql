-- ── 타임존/스케줄 (계획서 Phase 5, #6 SetupSchedule + #7 Schedule 통합 / #15 Timezone) ──────
-- 레거시 TB_TIME 대응. 4개의 고정 슬롯(timezone1~4)에 시작/종료 시각과 적용 요일을 저장하고,
-- timezone_hex_data는 저장 시점에 계산한 26바이트 전송 페이로드(16진 문자열)를 그대로 보관한다
-- (재전송/동기화 시 재계산하지 않고 그대로 재사용 — 레거시 SelectTimeZoneData와 동일 접근).
--
-- Codex 리뷰 지적 + 사용자 확인: 이 마이그레이션은 레거시 VB 앱이 쓰던 것과 동일한 운영 DB
-- (securance_gate)에 적용된다. 그런데 그 DB에는 레거시 TB_TIME이 이미 있고, Windows용 MariaDB는
-- 테이블명 대소문자를 구분하지 않는 게 기본값(lower_case_table_names=1)이라 아래 CREATE TABLE이
-- 곧바로 "Table already exists"로 실패한다. 레거시 컬럼 구조를 확인할 방법이 없어(실사용처 불분명
-- — 과도한 억측 금지) 값을 그대로 옮기는 변환 로직은 넣지 않고, 유실 없이 백업 이름으로 보존만
-- 한 뒤 새 스키마로 새로 만든다. 실제 데이터 이관은 레거시 컬럼 매핑이 확정된 후 별도 후속
-- 마이그레이션에서 처리한다.
DELIMITER $$
CREATE PROCEDURE _v3_archive_legacy_tb_time()
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = DATABASE() AND table_name = 'tb_time'
    ) THEN
        RENAME TABLE tb_time TO tb_time_legacy_backup;
    END IF;
END$$
DELIMITER ;
CALL _v3_archive_legacy_tb_time();
DROP PROCEDURE _v3_archive_legacy_tb_time;

CREATE TABLE tb_time (
    timezone_id     BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    timezone_name   VARCHAR(100) NOT NULL COMMENT '타임존 명칭',
    timezone_desc   VARCHAR(200) NULL COMMENT '설명',
    timezone_fr1    VARCHAR(10) NULL COMMENT '타임존1 시작(HH:mm)',
    timezone_to1    VARCHAR(10) NULL COMMENT '타임존1 종료(HH:mm)',
    timezone_day1   VARCHAR(100) NULL COMMENT '타임존1 적용 요일(콤마구분)',
    timezone_fr2    VARCHAR(10) NULL,
    timezone_to2    VARCHAR(10) NULL,
    timezone_day2   VARCHAR(100) NULL,
    timezone_fr3    VARCHAR(10) NULL,
    timezone_to3    VARCHAR(10) NULL,
    timezone_day3   VARCHAR(100) NULL,
    timezone_fr4    VARCHAR(10) NULL,
    timezone_to4    VARCHAR(10) NULL,
    timezone_day4   VARCHAR(100) NULL,
    timezone_hex_data LONGTEXT NOT NULL COMMENT '저장 시점의 26바이트 전송 페이로드(16진 문자열)',
    use_yn          CHAR(1) NOT NULL DEFAULT 'Y',
    reg_user        VARCHAR(20) NULL,
    reg_date        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (timezone_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='타임존/스케줄(계획서 Phase 5)';
