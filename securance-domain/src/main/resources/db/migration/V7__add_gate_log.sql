-- ============================================================================
-- tb_gate_log 신설 — `GATE_LOG`(ObjectCode 0x61) 수신 로그 엔트리 적재 테이블
--
-- 레거시(SR_Speed_Server/SR_Speed_Client) 양쪽 모두 로그 처리가 미구현 상태였다(계획서 3.8절).
-- 이번에 사용자가 제공한 프로토콜 문서(SpeedGate_Log_protocol_20260728_01.md)를 기준으로
-- LogEventCodec(securance-protocol)을 새로 구현했고, 그 결과를 적재할 테이블이 필요해 신설한다.
-- 레거시의 TB_DATA_RCV_LOG(+ TB_LOG_EVENT 조인)와 개념은 유사하지만, TB_LOG_EVENT(코드->메시지
-- 사전)는 이식하지 않기로 이미 결정된 상태라(V1__init_schema.sql 주석, LogReportController 참고)
-- 이 테이블은 디코딩된 필드를 그대로 저장할 뿐 사람이 읽을 메시지로 변환하지 않는다.
-- ============================================================================

CREATE TABLE tb_gate_log (
    log_id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    dtl_ip          VARCHAR(20)      NOT NULL COMMENT '게이트 IP',
    dtl_lane_no     TINYINT UNSIGNED NOT NULL COMMENT 'Module Number(Gate Lane Number, 0~14)',
    event_type      TINYINT UNSIGNED NOT NULL COMMENT 'Event Type(Access=1,Parking=8,DataObject=0x10,System=0x18,Comm=0x20)',
    object_code     TINYINT UNSIGNED NOT NULL,
    code            TINYINT UNSIGNED NOT NULL,
    err_code        TINYINT UNSIGNED NOT NULL,
    operation_mode  TINYINT UNSIGNED NOT NULL,
    reader_type     TINYINT UNSIGNED NOT NULL COMMENT '문서상 Not Used, 원본 그대로 보관',
    reader_number   TINYINT UNSIGNED NOT NULL,
    door_status     TINYINT UNSIGNED NOT NULL COMMENT 'Active/Open=1, Inactive/Close=2',
    function_code   SMALLINT UNSIGNED NOT NULL COMMENT '문서 명시 범위 0~254, 실기기는 0xFF도 전송함',
    event_time      DATETIME NOT NULL COMMENT '로그 엔트리 BCD 시각(장치 시각, 수신 시각 아님)',
    user_data1      VARCHAR(24) NULL COMMENT 'User Data1(12byte) 16진 문자열',
    user_data2      VARCHAR(16) NULL COMMENT 'User Data2(8byte, Old User ID) 16진 문자열',
    reg_date        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'DB 적재 시각',
    PRIMARY KEY (log_id),
    -- GateDbWriteQueue는 타임아웃/재시도로 같은 작업이 두 번 실행될 수 있어(멱등성 요구, GateDbWriteQueue.kt
    -- 참고) 애플리케이션에서 find-or-create로 중복을 막지만, 그 판단 기준이 되는 자연키에도 유니크
    -- 제약을 걸어 이중 방어한다.
    UNIQUE KEY uk_gate_log_natural (dtl_ip, dtl_lane_no, event_time, event_type, code, err_code, function_code),
    KEY idx_gate_log_ip_time (dtl_ip, event_time),
    KEY idx_gate_log_event_time (event_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='GATE_LOG(0x61) 수신 로그 엔트리';
