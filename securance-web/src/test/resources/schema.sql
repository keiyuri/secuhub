-- EventSearchFilterTest/LogSearchFilterTest/ControlHistoryFilterTest/GateLogSearchFilterTest
-- (@DataJpaTest) 전용 최소 스키마.
--
-- securance-domain/src/test/resources/schema.sql의 동명 테이블 정의와 동일한 이유로
-- (MariaDB VIRTUAL 생성 컬럼 등 H2로 그대로 이식하기 어려운 문법을 피해 엔티티가 매핑하는 컬럼만
-- 최소 재현) 이 모듈에도 같은 테이블을 둔다 — 두 모듈이 별도로 컴파일되는 test source set이라
-- 공유할 수 없다(운영 스키마 회귀 검증 목적이 아님에 주의).
-- ※ 2026-08-18: DataReceiveAnalysis에 dtl_type_cd + anal_data_* 19개 필드를 추가하면서
-- securance-domain 쪽 schema.sql만 갱신하고 이 파일은 빠뜨려 EventSearchFilterTest/
-- LogSearchFilterTest가 SQLGrammarException(Column not found)으로 깨졌다 — 엔티티 컬럼을
-- 추가/변경할 때는 이 파일도 함께 갱신해야 한다.
-- ※ 2026-08-25: tb_data_snd/tb_gate_log를 추가하며 같은 사고가 실제로 재현됐다(정의를 빠뜨린 채
-- 테스트부터 작성해 SQLGrammarException로 실패하는 것을 확인한 뒤 이 파일에 반영) — 위 경고가
-- 여전히 유효함을 스스로 증명한 셈이다.
CREATE TABLE tb_data_rcv_anal (
    anal_id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    anal_date               VARCHAR(20) NOT NULL,
    anal_tp                 VARCHAR(5)  NOT NULL,
    anal_type               VARCHAR(5)  NOT NULL DEFAULT 'B',
    dtl_ip                  VARCHAR(20) NOT NULL,
    dtl_lane_no             TINYINT     NOT NULL,
    dtl_type                TINYINT     NOT NULL DEFAULT 1,
    dtl_type_cd             VARCHAR(10) NULL,
    dtl_no                  INT         NOT NULL DEFAULT 1,
    dtl_id                  BIGINT      NOT NULL DEFAULT 0,
    loc_id                  BIGINT NULL,
    grp_id                  BIGINT NULL,
    rcv_date                VARCHAR(20) NOT NULL,
    rcv_id                  BIGINT      NOT NULL DEFAULT 0,
    rcv_raw                 CLOB NULL,
    anal_header             VARCHAR(100) NULL,
    anal_data               CLOB NULL,
    anal_tail               VARCHAR(20) NULL,
    obj_cd                  VARCHAR(10) NOT NULL DEFAULT '',
    anal_data_stx           VARCHAR(10) NOT NULL DEFAULT '',
    anal_data_packet_len    VARCHAR(10) NOT NULL DEFAULT '',
    anal_data_protocol_ver  VARCHAR(10) NOT NULL DEFAULT '',
    anal_data_frame_option  VARCHAR(10) NOT NULL DEFAULT '',
    anal_data_address       VARCHAR(40) NOT NULL DEFAULT '',
    anal_data_command       VARCHAR(10) NOT NULL DEFAULT '',
    anal_data_subcommand    VARCHAR(10) NOT NULL DEFAULT '',
    anal_data_object_code   VARCHAR(10) NOT NULL DEFAULT '',
    anal_data_info_length   VARCHAR(10) NOT NULL DEFAULT '',
    anal_data_count         VARCHAR(10) NOT NULL DEFAULT '',
    anal_data_length        VARCHAR(10) NOT NULL DEFAULT '',
    anal_data_gate_name     VARCHAR(80) NOT NULL DEFAULT '',
    anal_data_ip            VARCHAR(20) NOT NULL DEFAULT '',
    anal_data_mac           VARCHAR(20) NOT NULL DEFAULT '',
    anal_data_gate_lane_number        VARCHAR(2)  NOT NULL DEFAULT '',
    anal_data_gate_lane_count         VARCHAR(2)  NOT NULL DEFAULT '',
    anal_data_gate_type               VARCHAR(2)  NOT NULL DEFAULT '',
    anal_data_user_mode               VARCHAR(2)  NOT NULL DEFAULT '',
    anal_data_security_mode           VARCHAR(2)  NOT NULL DEFAULT '',
    anal_data_inout_time              VARCHAR(2)  NOT NULL DEFAULT '',
    anal_data_user_count              VARCHAR(2)  NOT NULL DEFAULT '',
    anal_data_total_count             VARCHAR(8)  NOT NULL DEFAULT '',
    anal_data_operation_sensor_status1 VARCHAR(8) NOT NULL DEFAULT '',
    anal_data_safety_sensor_status     VARCHAR(8) NOT NULL DEFAULT '',
    anal_data_operation_sensor_status2 VARCHAR(8) NOT NULL DEFAULT '',
    anal_data_optical_sensor_status    VARCHAR(50) NOT NULL DEFAULT '',
    anal_data_output_status            VARCHAR(40) NOT NULL DEFAULT '',
    anal_data_motor_operation_count    VARCHAR(20) NOT NULL DEFAULT '',
    anal_data_master_in_total_count    VARCHAR(20) NOT NULL DEFAULT '',
    anal_data_gate_operation_status    VARCHAR(50) NOT NULL DEFAULT '',
    anal_data_check_sum                VARCHAR(4)  NOT NULL DEFAULT '',
    anal_data_packet_checksum          VARCHAR(2)  NOT NULL DEFAULT '',
    anal_data_etx                      VARCHAR(2)  NOT NULL DEFAULT '',
    desc_data_info_length   TINYINT     NOT NULL DEFAULT 0,
    desc_gate_name          VARCHAR(80) NOT NULL DEFAULT '',
    desc_gate_ip            VARCHAR(20) NOT NULL DEFAULT '',
    desc_gate_lane_count    TINYINT     NOT NULL DEFAULT 0,
    desc_gate_lane_number   TINYINT     NOT NULL DEFAULT 0,
    desc_gate_type          VARCHAR(50) NOT NULL DEFAULT '',
    desc_user_mode          VARCHAR(30) NOT NULL DEFAULT '',
    user_mode_cd            VARCHAR(10) NULL,
    desc_security_mode      VARCHAR(20) NOT NULL DEFAULT '',
    security_mode_cd        VARCHAR(10) NULL,
    desc_inout_time         INT         NOT NULL DEFAULT 0,
    desc_user_count         BIGINT      NOT NULL DEFAULT 0,
    desc_total_count        BIGINT      NOT NULL DEFAULT 0,
    desc_operation01        VARCHAR(20) NOT NULL DEFAULT '',
    desc_operation02        VARCHAR(20) NOT NULL DEFAULT '',
    desc_operation03        VARCHAR(20) NOT NULL DEFAULT '',
    desc_operation04        VARCHAR(20) NOT NULL DEFAULT '',
    desc_safety01           VARCHAR(20) NOT NULL DEFAULT '',
    desc_safety02           VARCHAR(20) NOT NULL DEFAULT '',
    desc_safety03           VARCHAR(20) NOT NULL DEFAULT '',
    desc_safety04           VARCHAR(20) NOT NULL DEFAULT '',
    desc_operation05        VARCHAR(20) NOT NULL DEFAULT '',
    desc_operation06        VARCHAR(20) NOT NULL DEFAULT '',
    desc_operation07        VARCHAR(20) NOT NULL DEFAULT '',
    desc_operation08        VARCHAR(20) NOT NULL DEFAULT '',
    desc_motor_count        INT         NOT NULL DEFAULT 0,
    desc_master_in_total    BIGINT      NOT NULL DEFAULT 0,
    desc_gate_status01      VARCHAR(50) NOT NULL DEFAULT '',
    desc_gate_status02      VARCHAR(50) NOT NULL DEFAULT '',
    desc_gate_status03      VARCHAR(50) NOT NULL DEFAULT '',
    desc_gate_status04      VARCHAR(50) NOT NULL DEFAULT '',
    desc_gate_status05      VARCHAR(50) NOT NULL DEFAULT '',
    desc_gate_status06      VARCHAR(50) NOT NULL DEFAULT '',
    desc_gate_status07      VARCHAR(50) NOT NULL DEFAULT '',
    desc_gate_status08      VARCHAR(50) NOT NULL DEFAULT '',
    desc_gate_status09      VARCHAR(50) NOT NULL DEFAULT '',
    desc_gate_status10      VARCHAR(50) NOT NULL DEFAULT '',
    desc_gate_status11      VARCHAR(50) NOT NULL DEFAULT '',
    desc_gate_status12      VARCHAR(50) NOT NULL DEFAULT '',
    err_type                TINYINT     NOT NULL DEFAULT 0,
    resolve_yn              VARCHAR(1)  NOT NULL DEFAULT 'N',
    resolve_user            VARCHAR(50) NULL,
    resolve_date            TIMESTAMP NULL,
    has_status_event        BOOLEAN NOT NULL DEFAULT FALSE,
    has_error_event         BOOLEAN NOT NULL DEFAULT FALSE,
    reg_date                TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ControlHistoryFilterTest(@DataJpaTest) 전용 최소 스키마 — securance-domain/src/test/resources/
-- schema.sql의 tb_data_snd 정의와 동일(위 안내와 같은 이유로 모듈 간 공유 불가).
CREATE TABLE tb_data_snd (
    snd_id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    snd_date        VARCHAR(20) NOT NULL,
    snd_yn          VARCHAR(1)  NOT NULL DEFAULT 'N',
    chk_yn          VARCHAR(1)  NOT NULL DEFAULT 'N',
    next_attempt_at TIMESTAMP NULL,
    dtl_ip          VARCHAR(20) NOT NULL,
    dtl_lane_no     TINYINT     NOT NULL,
    dtl_type        TINYINT     NOT NULL DEFAULT 1,
    dtl_id          BIGINT      NOT NULL DEFAULT 0,
    loc_id          BIGINT      NOT NULL DEFAULT 0,
    grp_id          BIGINT      NOT NULL DEFAULT 0,
    snd_user        VARCHAR(20) NOT NULL DEFAULT '',
    snd_server      VARCHAR(20) NOT NULL DEFAULT '',
    snd_type_cd     VARCHAR(20) NOT NULL DEFAULT '',
    snd_data_tp     VARCHAR(20) NOT NULL DEFAULT '',
    snd_raw         CLOB        NOT NULL,
    snd_header      VARCHAR(100) NOT NULL DEFAULT '',
    snd_data        CLOB        NOT NULL,
    snd_tail        VARCHAR(20) NOT NULL DEFAULT '',
    version         BIGINT      NOT NULL DEFAULT 0
);

-- GateLogSearchFilterTest(@DataJpaTest) 전용 최소 스키마 — securance-domain/src/test/resources/
-- schema.sql의 tb_gate_log 정의와 동일(위 안내와 같은 이유로 모듈 간 공유 불가).
CREATE TABLE tb_gate_log (
    log_id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    dtl_ip          VARCHAR(20) NOT NULL,
    dtl_lane_no     TINYINT     NOT NULL,
    event_type      TINYINT     NOT NULL,
    object_code     TINYINT     NOT NULL,
    code            TINYINT     NOT NULL,
    err_code        TINYINT     NOT NULL,
    operation_mode  TINYINT     NOT NULL,
    reader_type     TINYINT     NOT NULL,
    reader_number   TINYINT     NOT NULL,
    door_status     TINYINT     NOT NULL,
    function_code   SMALLINT    NOT NULL,
    event_time      TIMESTAMP   NOT NULL,
    user_data1      VARCHAR(24) NULL,
    user_data2      VARCHAR(16) NULL,
    reg_date        TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_gate_log_natural UNIQUE (dtl_ip, dtl_lane_no, event_time, event_type, code, err_code, function_code)
);

-- GateLocationServiceUploadMapTest(@DataJpaTest) 전용 최소 스키마 — securance-domain/src/test/resources/
-- schema.sql의 tb_gate_loc 정의와 동일(위 안내와 같은 이유로 모듈 간 공유 불가).
CREATE TABLE tb_gate_loc (
    loc_id      BIGINT AUTO_INCREMENT PRIMARY KEY,
    loc_nm      VARCHAR(200) NOT NULL,
    use_yn      CHAR(1) NOT NULL DEFAULT 'Y',
    loc_x       INT NULL,
    loc_y       INT NULL,
    loc_map     VARCHAR(200) NULL,
    loc_map_w   INT NULL,
    loc_map_h   INT NULL,
    reg_date    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    mod_date    TIMESTAMP NULL
);
