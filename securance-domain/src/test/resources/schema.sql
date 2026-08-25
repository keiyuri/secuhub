-- `DataReceiveAnalysisRepositoryTest`(@DataJpaTest) 전용 최소 스키마.
--
-- 운영 마이그레이션(V1__init_schema.sql)의 tb_data_rcv_anal은 MariaDB VIRTUAL 생성 컬럼 등
-- H2에 그대로 이식하기 어려운 문법을 쓴다. 이 테스트는 "리포지토리의 JPQL이 실제 JPA 프로바이더
-- 위에서 정상 실행되는가"만 검증하면 충분하므로, 엔티티가 매핑하는 컬럼만 최소한으로 재현한다
-- (운영 DDL과 100% 동일하지 않음에 주의 — 운영 스키마 회귀 검증 목적이 아니다).
-- 2026-08-12(D5) `DataReceiveAnalysisRepositoryDeleteBatchTest` 추가로 엔티티가 매핑하는 컬럼
-- 전체를 반영하도록 확장했다 — Hibernate는 매핑된 컬럼을 전부 INSERT 문에 포함하므로, 엔티티에
-- 있는데 스키마에 없는 컬럼이 하나라도 있으면 "Column not found"로 실패한다.
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
    desc_data_info_length   VARCHAR(10) NOT NULL DEFAULT '',
    desc_gate_name          VARCHAR(80) NOT NULL DEFAULT '',
    desc_gate_ip            VARCHAR(20) NOT NULL DEFAULT '',
    desc_gate_lane_count    VARCHAR(20) NOT NULL DEFAULT '',
    desc_gate_lane_number   VARCHAR(20) NOT NULL DEFAULT '',
    desc_gate_type          VARCHAR(50) NOT NULL DEFAULT '',
    desc_user_mode          VARCHAR(30) NOT NULL DEFAULT '',
    desc_security_mode      VARCHAR(20) NOT NULL DEFAULT '',
    desc_inout_time         VARCHAR(20) NOT NULL DEFAULT '',
    desc_user_count         VARCHAR(20) NOT NULL DEFAULT '',
    desc_total_count        VARCHAR(20) NOT NULL DEFAULT '',
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
    desc_master_in_total    INT         NOT NULL DEFAULT 0,
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

-- `DataReceiveRepositoryTest`(@DataJpaTest) 전용 최소 스키마. D5 데이터 보관 정책(2026-08-12)의
-- `deleteBatchOlderThan` 네이티브 `LIMIT` 삭제 쿼리가 실제 JPA 프로바이더 위에서 동작하는지
-- 검증하는 데 필요한 컬럼만 재현한다.
CREATE TABLE tb_data_rcv (
    rcv_id      BIGINT AUTO_INCREMENT PRIMARY KEY,
    rcv_date    VARCHAR(20) NOT NULL,
    dtl_ip      VARCHAR(20) NOT NULL,
    dtl_lane_no TINYINT     NOT NULL,
    dtl_type    TINYINT NULL,
    dtl_id      BIGINT NULL,
    loc_id      BIGINT NULL,
    grp_id      BIGINT NULL,
    rcv_header  VARCHAR(100) NULL,
    rcv_data    CLOB NULL,
    rcv_data_info VARCHAR(150) NULL,
    rcv_data_lane CLOB NULL,
    rcv_tail    VARCHAR(20) NULL
);

-- `DataSendRepositoryTest`(@DataJpaTest) 전용 최소 스키마. [DataSend] 엔티티가 NOT NULL로 매핑한
-- 컬럼은 전부 채워야 Hibernate INSERT/UPDATE가 실제로 성공하는지 검증할 수 있어 전 컬럼을 재현한다
-- (claimForSend의 상관 서브쿼리 UPDATE를 실제 JPA 프로바이더 위에서 검증하기 위함 — 2026-08-13
-- Opus 전체 리뷰 지적).
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

-- `GateLogRepositoryTest`(@DataJpaTest) 전용 최소 스키마. 운영 마이그레이션(V7__add_gate_log.sql)의
-- 컬럼을 그대로 재현한다(자연키 UNIQUE 제약 포함 — existsBy... 중복 판단 쿼리가 실제로 그 컬럼
-- 조합을 대상으로 동작하는지 검증하려면 필요하다).
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

-- `GateDetailRepositoryFindAllForTreeTest`/`GateGroupRepositoryTest`(@DataJpaTest) 공용 최소 스키마.
-- [GateLocation]/[GateGroup]/[GateDetail] 엔티티가 매핑하는 컬럼만 재현한다(2026-08-25, 두 테스트가
-- 같은 테이블을 각자 필요한 범위만큼 나눠 검증한다 — findAllForTree()의 위치ID→그룹ID→게이트ID
-- 정렬+사용_분석 필터, GateGroupRepository 각 메서드의 위치ID→그룹ID 정렬+사용여부 필터).
-- use_yn류 컬럼은 [YnConverter] KDoc대로 CHAR(1)이 정확한 타입이다.
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

CREATE TABLE tb_gate_grp (
    grp_id      BIGINT AUTO_INCREMENT PRIMARY KEY,
    loc_id      BIGINT NOT NULL,
    grp_nm      VARCHAR(200) NOT NULL,
    lane_cnt    INT NOT NULL DEFAULT 1,
    dtl_type    INT NOT NULL,
    link_type   INT NOT NULL DEFAULT 1,
    use_yn      CHAR(1) NOT NULL DEFAULT 'Y',
    grp_x       INT NULL,
    grp_y       INT NULL,
    CONSTRAINT fk_gate_grp_loc FOREIGN KEY (loc_id) REFERENCES tb_gate_loc (loc_id)
);

CREATE TABLE tb_gate_dtl (
    dtl_id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    loc_id          BIGINT NOT NULL,
    grp_id          BIGINT NOT NULL,
    dtl_ip          VARCHAR(20) NOT NULL,
    dtl_lane_no     INT NOT NULL,
    dtl_type        INT NOT NULL,
    connect_type    INT NOT NULL DEFAULT 1,
    dtl_nm          VARCHAR(200) NULL,
    use_yn          VARCHAR(1) NOT NULL DEFAULT 'Y',
    analysis_yn     VARCHAR(1) NOT NULL DEFAULT 'Y',
    CONSTRAINT uq_gate_dtl_ip_lane UNIQUE (dtl_ip, dtl_lane_no)
);
