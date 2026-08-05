-- ============================================================================
-- secuhub(securance) V1 초기 스키마
--
-- 레거시 securance_gate(MariaDB 10.11) 54개 SQL 파일을 기준으로 포팅했다(계획서 4.1절).
-- 1차 스캐폴드 범위: 계획서 4.3절의 "대표 엔티티"에 대응하는 테이블만 포함한다.
-- 나머지 테이블(tb_time, tb_calendar, tb_log, tb_data_rcv_motor/ctrl, tb_data_init 등)은
-- 동일 패턴으로 후속 마이그레이션(V2 이후)에서 추가한다.
--
-- 레거시 대비 변경점:
--   1) FOREIGN_KEY_CHECKS=0으로 운영되던 논리적 관계에 실제 FK를 추가했다.
--   2) tb_data_rcv_anal_evt/_plm/_state 샤드 3종은 통합하지 않고 단일 tb_data_rcv_anal +
--      anal_tp 구분자로 합쳤다(중복 저장 제거, 계획서 4.2절).
--   3) tb_log_event(=tb_log 중복), tb_data_rcv_state(=tb_data_rcv_anal_state로 대체됨)는 이식하지 않는다.
-- ============================================================================

-- ── 코드 마스터 ──────────────────────────────────────────────────────────
-- 게이트 타입(1=Speed,2=Flap,3=Turn,4=Fast) 등 확장 가능한 코드값을 관리한다(계획서 4.3절).
-- 신규 게이트 타입 추가는 이 테이블에 행을 추가하는 것만으로 가능하며 애플리케이션 재배포가 필요 없다.
CREATE TABLE tb_code (
    code_grp     VARCHAR(10)  NOT NULL COMMENT '코드그룹 (예: GATE_TYPE)',
    code_cd      VARCHAR(20)  NOT NULL COMMENT '코드값',
    code_nm      VARCHAR(100) NOT NULL COMMENT '코드명(표시용)',
    code_val     VARCHAR(50)  NULL COMMENT '하드웨어/프로토콜 매핑 값',
    code_desc    VARCHAR(255) NULL COMMENT '설명',
    disp_order   TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '표시 순서',
    use_yn       TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '사용 여부 (tb_code만 tinyint(1), 나머지 테이블은 char(1) Y/N)',
    reg_date     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    mod_date     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (code_grp, code_cd)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='코드 마스터';

-- ── 게이트 위치/그룹/상세 (설치 위치 계층) ──────────────────────────────
CREATE TABLE tb_gate_loc (
    loc_id      BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    loc_nm      VARCHAR(200) NOT NULL COMMENT '설치 위치명',
    use_yn      CHAR(1) NOT NULL DEFAULT 'Y',
    loc_x       INT NULL COMMENT '배치도 X 좌표',
    loc_y       INT NULL COMMENT '배치도 Y 좌표',
    loc_width   INT NULL,
    loc_height  INT NULL,
    loc_map_w   INT NULL,
    loc_map_h   INT NULL,
    loc_map     VARCHAR(200) NULL COMMENT '배치도 이미지 파일명',
    reg_date    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    mod_date    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (loc_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='게이트 설치 위치';

CREATE TABLE tb_gate_grp (
    grp_id      BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    loc_id      BIGINT UNSIGNED NOT NULL,
    grp_nm      VARCHAR(200) NOT NULL COMMENT '게이트 그룹명',
    lane_cnt    TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '레인 수 (물리적 게이트 유닛 수는 lane_cnt+1, 계획서 3.2절)',
    dtl_type    TINYINT NOT NULL COMMENT '게이트 타입 원시값 (tb_code GATE_TYPE 참고, 계획서 4.3절)',
    link_type   TINYINT NOT NULL DEFAULT 1 COMMENT '연결 방식: 1=1:1, 2=1:N (계획서 3.2절)',
    use_yn      CHAR(1) NOT NULL DEFAULT 'Y',
    grp_x       INT NULL,
    grp_y       INT NULL,
    grp_width   INT NULL,
    grp_height  INT NULL,
    grp_map_w   INT NULL,
    grp_map_h   INT NULL,
    grp_map     VARCHAR(200) NULL,
    rtsp_addr   VARCHAR(200) NULL,
    onvif_addr  VARCHAR(200) NULL,
    reg_date    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    mod_date    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (grp_id),
    CONSTRAINT fk_gate_grp_loc FOREIGN KEY (loc_id) REFERENCES tb_gate_loc (loc_id),
    KEY idx_gate_grp_loc (loc_id, use_yn)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='게이트 그룹';

CREATE TABLE tb_gate_dtl (
    dtl_id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    loc_id         BIGINT UNSIGNED NOT NULL,
    grp_id         BIGINT UNSIGNED NOT NULL,
    dtl_ip         VARCHAR(20) NOT NULL COMMENT '게이트 장비 IP',
    dtl_lane_no    TINYINT UNSIGNED NOT NULL COMMENT '레인 번호',
    dtl_type       TINYINT NOT NULL COMMENT '게이트 타입 원시값 (tb_code GATE_TYPE)',
    connect_type   TINYINT NOT NULL DEFAULT 1 COMMENT '1=Net/Wifi, 4=BLE',
    dtl_no         INT UNSIGNED NULL COMMENT '시리얼 연결 번호',
    dtl_mac        VARCHAR(200) NULL,
    dtl_nm         VARCHAR(200) NULL COMMENT '게이트 표시명',
    use_yn         CHAR(1) NOT NULL DEFAULT 'Y',
    analysis_yn    CHAR(1) NOT NULL DEFAULT 'Y' COMMENT '분석/제어 대상 여부',
    dtl_x          INT NULL,
    dtl_y          INT NULL,
    dtl_icon       VARCHAR(200) NULL,
    reg_date       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    mod_date       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (dtl_id),
    CONSTRAINT fk_gate_dtl_loc FOREIGN KEY (loc_id) REFERENCES tb_gate_loc (loc_id),
    CONSTRAINT fk_gate_dtl_grp FOREIGN KEY (grp_id) REFERENCES tb_gate_grp (grp_id),
    UNIQUE KEY uq_gate_dtl_ip_lane (dtl_ip, dtl_lane_no),
    KEY idx_gate_dtl_loc_grp (loc_id, grp_id, use_yn)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='게이트(레인) 상세';

-- ── 사용자 ───────────────────────────────────────────────────────────────
CREATE TABLE tb_users (
    user_id     VARCHAR(20) NOT NULL,
    passwd      VARCHAR(255) NOT NULL COMMENT 'BCrypt 등으로 해시된 값 (레거시는 평문 저장 — 명시적 개선사항)',
    user_nm     VARCHAR(100) NOT NULL,
    e_mail      VARCHAR(100) NULL,
    phone       VARCHAR(50) NULL,
    use_yn      CHAR(1) NOT NULL DEFAULT 'Y',
    auth_view   CHAR(1) NOT NULL DEFAULT 'Y',
    auth_ctrl   CHAR(1) NOT NULL DEFAULT 'N',
    auth_admin  CHAR(1) NOT NULL DEFAULT 'N',
    auth_loc    VARCHAR(50) NOT NULL DEFAULT 'ALL',
    auth_grp    VARCHAR(50) NOT NULL DEFAULT 'ALL',
    local_area  VARCHAR(5) NOT NULL DEFAULT 'ko-KR',
    reg_date    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    mod_date    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='시스템 사용자';

-- ── 네트워크 연결 상태 ───────────────────────────────────────────────────
CREATE TABLE tb_net_state (
    dtl_ip       VARCHAR(20) NOT NULL,
    dtl_lane_no  TINYINT UNSIGNED NOT NULL,
    loc_id       BIGINT UNSIGNED NOT NULL,
    grp_id       BIGINT UNSIGNED NOT NULL,
    dtl_id       BIGINT UNSIGNED NULL,
    dtl_type     TINYINT NULL,
    dtl_no       INT UNSIGNED NULL,
    dtl_state    CHAR(1) NOT NULL DEFAULT 'N' COMMENT '연결 상태 Y=온라인, N=오프라인',
    dtl_ping     CHAR(1) NULL COMMENT 'dtl_state=N일 때 ping 결과',
    check_time   VARCHAR(20) NULL,
    server_ip    VARCHAR(20) NULL COMMENT '이 상태를 보고한 백엔드 인스턴스 IP',
    server_cd    VARCHAR(20) NULL,
    reg_date     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    mod_date     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (dtl_ip, dtl_lane_no, loc_id, grp_id),
    KEY idx_net_state_loc_grp_state (loc_id, grp_id, dtl_state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='게이트 연결 상태';

-- ── 원시 수신 데이터 ─────────────────────────────────────────────────────
CREATE TABLE tb_data_rcv (
    rcv_id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    rcv_date        VARCHAR(20) NOT NULL COMMENT 'yyyyMMddHHmm',
    dtl_ip          VARCHAR(20) NOT NULL,
    dtl_lane_no     TINYINT UNSIGNED NOT NULL,
    dtl_type        TINYINT NULL,
    dtl_no          INT UNSIGNED NULL,
    dtl_id          BIGINT UNSIGNED NULL,
    loc_id          BIGINT UNSIGNED NULL,
    grp_id          BIGINT UNSIGNED NULL,
    rcv_header      VARCHAR(100) NULL COMMENT '헤더(27바이트) 16진 문자열',
    rcv_data        LONGTEXT NULL COMMENT 'DataInfo+Data 16진 문자열',
    rcv_data_info   VARCHAR(150) NULL,
    rcv_data_lane   LONGTEXT NULL,
    rcv_data_log    LONGTEXT NULL,
    rcv_tail        VARCHAR(20) NULL,
    rcv_unixtime    DOUBLE(22,3) NULL,
    mod_date        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (rcv_id),
    KEY idx_data_rcv_date_ip_lane (rcv_date, dtl_ip, dtl_lane_no),
    KEY idx_data_rcv_mod_date (mod_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='원시 수신 패킷';

CREATE TABLE tb_data_rcv_fail (
    fail_id     BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    fail_date   VARCHAR(20) NOT NULL,
    dtl_ip      VARCHAR(20) NULL,
    dtl_lane_no TINYINT UNSIGNED NULL,
    rcv_raw     LONGTEXT NULL COMMENT '보드 타입 불일치 등으로 거부된 원시 패킷',
    PRIMARY KEY (fail_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='수신 실패(Dead-letter)';

CREATE TABLE tb_data_rcv_ack (
    ack_id       BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    ack_date     VARCHAR(20) NOT NULL,
    dtl_ip       VARCHAR(20) NOT NULL,
    dtl_lane_no  TINYINT UNSIGNED NOT NULL,
    dtl_id       BIGINT UNSIGNED NULL,
    ack_raw      LONGTEXT NULL,
    ack_header   VARCHAR(100) NULL,
    ack_data     LONGTEXT NULL,
    ack_tail     VARCHAR(20) NULL,
    ack_unixtime DOUBLE(22,3) NULL,
    PRIMARY KEY (ack_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='ACK 응답';

-- ── 분석(파싱) 데이터 — 통합 테이블 (계획서 4.2절: evt/plm/state 샤드 통합) ─
CREATE TABLE tb_data_rcv_anal (
    anal_id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    anal_date           VARCHAR(20) NOT NULL COMMENT 'yyyyMMddHHmm',
    anal_tp             VARCHAR(5) NOT NULL COMMENT 'NOR/EVT/PLM/STA/ERR',
    rcv_date            VARCHAR(20) NULL,
    rcv_id              BIGINT UNSIGNED NULL,
    dtl_ip              VARCHAR(20) NOT NULL,
    dtl_lane_no         TINYINT UNSIGNED NOT NULL,
    dtl_type            TINYINT NULL,
    dtl_no              INT UNSIGNED NULL,
    dtl_id              BIGINT UNSIGNED NULL,
    loc_id              BIGINT UNSIGNED NULL,
    grp_id              BIGINT UNSIGNED NULL,
    -- 상태 요약에 필요한 디코딩 결과(desc_*) — 프로토콜 문서의 GATE OPERATION STATUS 1~12에 대응.
    desc_operation01    VARCHAR(20) NULL COMMENT 'S00: BACK RUSH 등 운영 센서 이상',
    desc_operation02    VARCHAR(20) NULL,
    desc_operation03    VARCHAR(20) NULL,
    desc_operation04    VARCHAR(20) NULL,
    desc_operation05    VARCHAR(20) NULL,
    desc_operation06    VARCHAR(20) NULL,
    desc_operation07    VARCHAR(20) NULL,
    desc_operation08    VARCHAR(20) NULL,
    desc_safety01       VARCHAR(20) NULL COMMENT 'S04~S07: 안전 센서 이상',
    desc_safety02       VARCHAR(20) NULL,
    desc_safety03       VARCHAR(20) NULL,
    desc_safety04       VARCHAR(20) NULL,
    desc_gate_status01  VARCHAR(50) NULL COMMENT '1.BACK RUSH',
    desc_gate_status02  VARCHAR(50) NULL COMMENT '2.TAIL GATING',
    desc_gate_status03  VARCHAR(50) NULL COMMENT '3.WAITING',
    desc_gate_status04  VARCHAR(50) NULL COMMENT '4.ANTI-PASS',
    desc_gate_status05  VARCHAR(50) NULL COMMENT '5.OPEN/CLOSED',
    desc_gate_status06  VARCHAR(50) NULL COMMENT '6.REMOCON',
    desc_gate_status07  VARCHAR(50) NULL COMMENT '7.FIRE ALARM',
    desc_gate_status08  VARCHAR(50) NULL COMMENT '8.AC-POWER',
    desc_gate_status09  VARCHAR(50) NULL COMMENT '9.SUB BOARD COMM',
    desc_gate_status10  VARCHAR(50) NULL COMMENT '10.MAIN MOTOR',
    desc_gate_status11  VARCHAR(50) NULL COMMENT '11.SLAVE MOTOR',
    desc_gate_status12  VARCHAR(50) NULL COMMENT '12.EMERGENCY',
    err_type            TINYINT NULL COMMENT '3=오류',
    resolve_yn          VARCHAR(1) NOT NULL DEFAULT 'N',
    resolve_user        VARCHAR(50) NULL,
    resolve_date        DATETIME NULL,
    -- MariaDB VIRTUAL 생성 컬럼 — JPA에서는 insertable=false updatable=false로 읽기 전용 매핑(계획서 4.1절).
    has_status_event TINYINT(1) AS (
        CASE WHEN
            COALESCE(desc_gate_status01,'') <> '' OR COALESCE(desc_gate_status02,'') <> '' OR
            COALESCE(desc_gate_status03,'') <> '' OR COALESCE(desc_gate_status04,'') <> '' OR
            COALESCE(desc_gate_status05,'') <> '' OR COALESCE(desc_gate_status06,'') <> '' OR
            COALESCE(desc_gate_status07,'') <> '' OR COALESCE(desc_gate_status08,'') <> '' OR
            COALESCE(desc_gate_status12,'') <> ''
        THEN 1 ELSE 0 END
    ) VIRTUAL,
    has_error_event TINYINT(1) AS (
        CASE WHEN
            COALESCE(desc_operation01,'') <> '' OR COALESCE(desc_operation02,'') <> '' OR
            COALESCE(desc_operation03,'') <> '' OR COALESCE(desc_operation04,'') <> '' OR
            COALESCE(desc_operation05,'') <> '' OR COALESCE(desc_operation06,'') <> '' OR
            COALESCE(desc_operation07,'') <> '' OR COALESCE(desc_operation08,'') <> '' OR
            COALESCE(desc_safety01,'') <> '' OR COALESCE(desc_safety02,'') <> '' OR
            COALESCE(desc_safety03,'') <> '' OR COALESCE(desc_safety04,'') <> '' OR
            COALESCE(desc_gate_status09,'') <> '' OR COALESCE(desc_gate_status10,'') <> '' OR
            COALESCE(desc_gate_status11,'') <> ''
        THEN 1 ELSE 0 END
    ) VIRTUAL,
    reg_date            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    mod_date            DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (anal_id),
    KEY idx_anal_loc_grp_date (loc_id, grp_id, anal_date),
    KEY idx_anal_dtl_latest (dtl_id, anal_id),
    KEY idx_anal_err3_scan (err_type, has_error_event, anal_id),
    KEY idx_anal_tp_date (anal_tp, anal_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='수신 데이터 분석(통합, 계획서 4.2절)';

-- ── 제어 명령 발송 큐 (5.5절 QUEUED 경로 / SendControlJob) ──────────────
CREATE TABLE tb_data_snd (
    snd_id        BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    snd_date      VARCHAR(20) NOT NULL COMMENT 'yyyyMMddHHmmss',
    snd_yn        CHAR(1) NOT NULL DEFAULT 'N' COMMENT '게이트로 전송 완료 여부',
    chk_yn        CHAR(1) NOT NULL DEFAULT 'N' COMMENT '서버 전송 확인 여부',
    dtl_ip        VARCHAR(20) NOT NULL,
    dtl_lane_no   TINYINT UNSIGNED NOT NULL,
    dtl_id        BIGINT UNSIGNED NULL,
    snd_user      VARCHAR(20) NULL COMMENT '요청한 사용자(tb_users.user_id)',
    snd_server    VARCHAR(20) NULL,
    snd_type_cd   VARCHAR(20) NULL,
    snd_raw       LONGTEXT NULL COMMENT '전송할 패킷 16진 문자열',
    snd_header    VARCHAR(100) NULL,
    snd_data      LONGTEXT NULL,
    snd_tail      VARCHAR(20) NULL,
    reg_date      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    mod_date      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (snd_id),
    KEY idx_data_snd_pending (snd_date, snd_yn, chk_yn, dtl_ip, dtl_lane_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='제어 명령 발송 큐';

-- ── 운영 카운터(대시보드 통행량 위젯 소스) ───────────────────────────────
CREATE TABLE tb_opr_status (
    opr_date        VARCHAR(20) NOT NULL COMMENT 'yyyyMMddHHmm',
    opr_seq         INT NOT NULL DEFAULT 1,
    dtl_ip          VARCHAR(20) NOT NULL,
    dtl_lane_no     TINYINT UNSIGNED NOT NULL,
    dtl_id          BIGINT UNSIGNED NULL,
    loc_id          BIGINT UNSIGNED NULL,
    grp_id          BIGINT UNSIGNED NULL,
    opr_user_count  INT NULL,
    opr_total_count BIGINT NULL,
    opr_before_total BIGINT NULL,
    opr_in_count    INT NULL,
    opr_in_total    BIGINT NULL,
    opr_in_before   BIGINT NULL,
    opr_out_count   INT NULL,
    opr_out_total   BIGINT NULL,
    opr_out_before  BIGINT NULL,
    opr_door_count  INT NULL,
    opr_door_total  BIGINT NULL,
    opr_door_before BIGINT NULL,
    use_yn          CHAR(1) NOT NULL DEFAULT 'Y',
    reg_user        VARCHAR(50) NOT NULL DEFAULT 'service',
    reg_date        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    mod_date        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (opr_date, opr_seq, dtl_ip, dtl_lane_no),
    KEY idx_opr_status_date_loc_grp (opr_date, loc_id, grp_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='분단위 운영 카운터';

-- ── 시드 데이터: 게이트 타입 코드 (계획서 4.3절 "게이트 타입 — 확장 가능한 코드값") ─
INSERT INTO tb_code (code_grp, code_cd, code_nm, code_val, code_desc, disp_order, use_yn) VALUES
    ('GATE_TYPE', '1', 'Speed Gate', 'SR-1400', 'Speed/Flap Gate 공유 프로토콜', 1, 1),
    ('GATE_TYPE', '2', 'Flap Gate', 'FLAP', 'Speed/Flap Gate 공유 프로토콜', 2, 1),
    ('GATE_TYPE', '3', 'Turn Gate', 'TURN', '별도 프로토콜 — 1차 스캐폴드 미구현(계획서 3.4절)', 3, 1),
    ('GATE_TYPE', '4', 'Fast Gate', 'FAST', '별도 프로토콜 — 1차 스캐폴드 미구현(계획서 3.4절)', 4, 1);
