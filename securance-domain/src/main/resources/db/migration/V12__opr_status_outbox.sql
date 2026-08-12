-- V12 — 큐 드롭 durable 재작성(2026-08-12, `docs/작업일지.md` 참고).
--
-- `GateDbWriteQueue`가 통행량 집계(`OprStatusPersister`) 작업을 드롭하거나(큐 포화) 재시도를
-- 모두 소진해도 끝내 실패하면, 예전에는 로그+카운터만 남기고 그 분(分) 버킷 데이터를 그대로
-- 포기했다(0003 항목 codex 적대적 리뷰 지적, 문서화로만 대응했던 항목). 이 테이블은 그 fallback
-- 경로가 UPSERT에 필요한 원시값을 즉시(동기) 적재해두는 durable outbox다 —
-- `OprStatusOutboxReplayJob`(securance-scheduler)이 주기적으로 훑어 원래 실패했던 UPSERT를
-- 재시도한다.
CREATE TABLE tb_opr_status_outbox (
    outbox_id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    dtl_ip            VARCHAR(20) NOT NULL,
    dtl_lane_no       INT         NOT NULL,
    dtl_id            BIGINT      NOT NULL,
    dtl_type          INT         NOT NULL,
    loc_id            BIGINT      NOT NULL,
    grp_id            BIGINT      NOT NULL,
    opr_date          VARCHAR(20) NOT NULL COMMENT '분(分) 버킷 키(dateKey)',
    since_date        VARCHAR(20) NOT NULL COMMENT 'PREV 조회 하한(sinceDateKey)',
    curr_total        BIGINT      NOT NULL,
    curr_door         BIGINT      NOT NULL,
    curr_in           BIGINT      NOT NULL,
    gate_type_raw     INT         NOT NULL,
    user_mode_raw     INT         NOT NULL,
    security_mode_raw INT         NOT NULL,
    inout_time        INT         NOT NULL,
    reason            VARCHAR(20) NOT NULL COMMENT 'DROPPED(큐 포화) 또는 FINAL_FAILURE(재시도 소진)',
    retry_count       INT         NOT NULL DEFAULT 0,
    processed         TINYINT(1)  NOT NULL DEFAULT 0,
    reg_date          TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_date    TIMESTAMP   NULL,
    INDEX ix_opr_status_outbox_pending (processed, outbox_id)
);
