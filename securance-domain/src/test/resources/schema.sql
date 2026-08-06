-- `DataReceiveAnalysisRepositoryTest`(@DataJpaTest) 전용 최소 스키마.
--
-- 운영 마이그레이션(V1__init_schema.sql)의 tb_data_rcv_anal은 MariaDB VIRTUAL 생성 컬럼 등
-- H2에 그대로 이식하기 어려운 문법을 쓴다. 이 테스트는 "리포지토리의 JPQL이 실제 JPA 프로바이더
-- 위에서 정상 실행되는가"만 검증하면 충분하므로, 엔티티가 매핑하는 컬럼만 최소한으로 재현한다
-- (운영 DDL과 100% 동일하지 않음에 주의 — 운영 스키마 회귀 검증 목적이 아니다).
CREATE TABLE tb_data_rcv_anal (
    anal_id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    anal_date           VARCHAR(20) NOT NULL,
    anal_tp             VARCHAR(5)  NOT NULL,
    dtl_ip              VARCHAR(20) NOT NULL,
    dtl_lane_no         TINYINT     NOT NULL,
    loc_id              BIGINT NULL,
    grp_id              BIGINT NULL,
    dtl_id              BIGINT NULL,
    desc_gate_status07  VARCHAR(50) NULL,
    desc_operation01    VARCHAR(20) NULL,
    desc_operation02    VARCHAR(20) NULL,
    desc_operation03    VARCHAR(20) NULL,
    desc_operation04    VARCHAR(20) NULL,
    desc_operation05    VARCHAR(20) NULL,
    desc_operation06    VARCHAR(20) NULL,
    desc_operation07    VARCHAR(20) NULL,
    desc_operation08    VARCHAR(20) NULL,
    desc_safety01       VARCHAR(20) NULL,
    desc_safety02       VARCHAR(20) NULL,
    desc_safety03       VARCHAR(20) NULL,
    desc_safety04       VARCHAR(20) NULL,
    desc_gate_status09  VARCHAR(50) NULL,
    desc_gate_status10  VARCHAR(50) NULL,
    desc_gate_status11  VARCHAR(50) NULL,
    err_type            TINYINT NULL,
    resolve_yn          VARCHAR(1) NOT NULL DEFAULT 'N',
    resolve_user        VARCHAR(50) NULL,
    resolve_date        TIMESTAMP NULL,
    has_status_event    BOOLEAN NOT NULL DEFAULT FALSE,
    has_error_event     BOOLEAN NOT NULL DEFAULT FALSE,
    reg_date            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- `DataSendRepositoryTest`(@DataJpaTest) 전용 최소 스키마. 운영 마이그레이션(V1 + V4 + V5)의
-- 컬럼 중 이 리포지토리의 JPQL이 실제로 참조하는 컬럼만 재현한다.
CREATE TABLE tb_data_snd (
    snd_id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    snd_date        VARCHAR(20) NOT NULL,
    snd_yn          VARCHAR(1)  NOT NULL DEFAULT 'N',
    chk_yn          VARCHAR(1)  NOT NULL DEFAULT 'N',
    next_attempt_at TIMESTAMP NULL,
    dtl_ip          VARCHAR(20) NOT NULL,
    dtl_lane_no     TINYINT     NOT NULL,
    snd_user        VARCHAR(20) NULL,
    snd_type_cd     VARCHAR(20) NULL,
    snd_raw         CLOB NULL
);
