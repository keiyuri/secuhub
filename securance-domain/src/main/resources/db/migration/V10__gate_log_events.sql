-- ============================================================================
-- V10 — tb_gate_log_event 신규 (3차 스프린트 "0x61 로그 패킷" 구조화 파싱)
-- (원래 V4로 작성됐으나 기존 V4__add_data_snd_pending_index.sql과 버전 번호가 충돌해
--  Flyway 적용 순서를 그대로 유지한 채 V10로 재번호했다. 2026-08-11 B1 이슈 수정.)
--
-- 레거시 TB_DATA_RCV_LOG(SR_F_ViewLog가 조회하던 원본 테이블)에 대응하는 신규 테이블이지만,
-- 이름을 그대로 tb_data_rcv_log로 쓰면 개발 DB에 이미 존재하는 레거시 테이블(V1 베이스라인
-- 이전부터 실데이터 50만+ 행 보유, 완전히 다른 컬럼 구조)과 충돌한다(2026-08-12 확인, 기동 실패
-- 로그로 발견). tb_gate_log_event로 이름을 바꿔 분리한다 — 레거시 테이블은 그대로 유지된다.
-- (참고: V7__add_gate_log.sql이 만든 tb_gate_log는 같은 목적의 첫 시도였으나 TINYINT 매핑
-- 불일치 문제가 있고 실제로 비어 있다/미사용 — 이번 정리 범위에서는 별도로 다루지 않는다.)
-- SR_F_ViewLog 화면 자체는 이미 tb_data_rcv_anal 재사용으로 완료됐지만(docs/SR_Speed_Client_전환_계획.md
-- 4절 9번), 원본 0x61 로그 엔트리(36바이트 고정 구조, SpeedGate_Log_protocol_20260728_01.md)
-- 필드는 그 방식으로 재현할 수 없어 별도 테이블로 신규 적재한다(GatePacketPersister.enqueueLogInsert).
--
-- 숫자 컬럼을 전부 INT로 두는 이유: V8에서 겪은 TINYINT vs Hibernate 매핑 불일치(Kotlin Int ↔
-- 스키마 검증 실패)를 이번엔 처음부터 피하기 위함이다.
-- ============================================================================

CREATE TABLE tb_gate_log_event (
    log_id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    rcv_date       VARCHAR(20) NOT NULL COMMENT 'yyyyMMddHHmm — 조회 화면 필터 기준',
    sort_date      VARCHAR(20) NOT NULL COMMENT 'yyyyMMddHHmmss — 목록 정렬 기준',
    dtl_ip         VARCHAR(20) NOT NULL,
    dtl_lane_no    INT NOT NULL,
    dtl_type       INT NULL,
    dtl_id         BIGINT UNSIGNED NULL,
    loc_id         BIGINT UNSIGNED NULL,
    grp_id         BIGINT UNSIGNED NULL,
    event_type     INT NOT NULL COMMENT '0x01 ACCESS/0x08 PARKING/0x10 DATA OBJECT/0x18 SYSTEM/0x20 COMMUNICATION',
    object_code    INT NOT NULL,
    code           INT NOT NULL,
    err_code       INT NOT NULL,
    operation_mode INT NOT NULL,
    reader_type    INT NOT NULL COMMENT '문서상 Not Used — 원본 값만 보존',
    module_number  INT NOT NULL COMMENT 'Gate Lane Number, 0~14',
    reader_number  INT NOT NULL COMMENT '0~254',
    door_status    INT NOT NULL COMMENT '0x01 Open/0x02 Close',
    function_code  INT NOT NULL COMMENT '0~254',
    event_time     DATETIME NULL COMMENT '장비가 기록한 발생 시각(6바이트 BCD 디코딩, 범위 밖이면 NULL)',
    user_data1     VARCHAR(24) NOT NULL DEFAULT '' COMMENT 'User ID(8)+Revision(4) 또는 Card ID(8), HEX',
    user_data2     VARCHAR(16) NOT NULL DEFAULT '' COMMENT 'Old User ID(8), HEX',
    log_raw        LONGTEXT NULL COMMENT '엔트리 원본 36바이트 HEX(사후 재해석용)',
    PRIMARY KEY (log_id),
    KEY idx_gate_log_event_rcv_date (rcv_date),
    KEY idx_gate_log_event_dtl_ip_lane (dtl_ip, dtl_lane_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='게이트 로그 이벤트(0x61, 36바이트 구조) — 레거시 TB_DATA_RCV_LOG 대응(테이블명은 충돌 회피로 분리)';
