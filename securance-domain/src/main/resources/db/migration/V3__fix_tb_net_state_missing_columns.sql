-- ============================================================================
-- tb_net_state 컬럼 누락 수정 (2026-09-07, 사용자 요청 — dtl_type/dtl_id/snd_raw/server_cd/
-- mod_date 데이터 누락 및 check_time 포맷 점검)
--
-- 레거시 저장 프로시저 usp_net_check_data(V1 정의)는 dtl_type/server_cd를 INSERT/UPDATE 절
-- 어디에도 담지 않아, 이 프로시저가 쓰는 행은 두 컬럼이 영구히 NULL로 남는다. 신규 서버 경로
-- (NetStateRepository.upsertIfNewer)도 같은 결함이 있었으나 그쪽은 V1 스키마가 아니라
-- Kotlin 코드이므로 이번 세션에서 직접 수정했다(NetStateRepository.kt 참고) — 이 마이그레이션은
-- 레거시 프로시저 쪽을 맞춘다.
--
-- 변경 내용:
--   1. dtl_type: 커서가 이미 조회하는 tb_gate_dtl.dtl_type을 그대로 INSERT/UPDATE에 싣는다.
--   2. server_cd: 이 프로시저는 SR_Speed_Server(레거시, 신규 GatewayMode.SERVER에 대응하는
--      인바운드 연결 방식)가 호출하므로 'SERVER'로 고정한다(사용자 확인, 2026-09-07 — 이
--      프로시저가 레거시 CLIENT 모드 서버에서도 호출된다는 근거가 없고, CLIENT 모드 자체가
--      나중에 Kotlin 서버에 와서야 추가된 개념이다).
--   3. check_time 포맷은 이미 초 단위(yyyyMMddHHmmss, DATE_FORMAT(NOW(), '%Y%m%d%H%i%s'))라
--      변경하지 않는다 — Kotlin 신규 경로 쪽을 이 포맷에 맞췄다(사용자 확인, 2026-09-07).
--
-- snd_raw는 V1부터 이미 이 프로시저가 채우고 있어 손대지 않는다. mod_date는 컬럼 자체가
-- `ON UPDATE current_timestamp()`이고 이 프로시저도 이미 NOW()로 명시 갱신하므로 문제가
-- 없었다(재검토 결과 실제 버그 없음).
--
-- DEFINER 절은 V1과 동일하게 의도적으로 생략한다 — 이 문장을 실행하는 계정을 그대로 쓰도록 해,
-- 마이그레이션 실행 계정에 임의 DEFINER를 지정할 SUPER/SET USER 권한이 없는 환경에서도 동작한다.
-- ============================================================================

DROP PROCEDURE IF EXISTS `usp_net_check_data`;
DELIMITER $$
CREATE PROCEDURE `usp_net_check_data`(
    IN vSvrIP VARCHAR(20),
    IN vDtlIP VARCHAR(20),
    IN vRcvData MEDIUMTEXT,
    IN vState VARCHAR(1)
)
    SQL SECURITY INVOKER
BEGIN
    DECLARE vDtlNo   INT UNSIGNED DEFAULT 1;
    DECLARE vLaneNo  TINYINT UNSIGNED DEFAULT 1;
    DECLARE vDtlId   BIGINT UNSIGNED DEFAULT 0;
    DECLARE vDtlType INT DEFAULT 1;
    DECLARE vLocId   BIGINT UNSIGNED DEFAULT 0;
    DECLARE vGrpId   BIGINT UNSIGNED DEFAULT 0;
    DECLARE vCheckTime VARCHAR(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
    DECLARE vServerCd VARCHAR(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT 'SERVER';

    DECLARE vSeq     BIGINT UNSIGNED;
    DECLARE done     INT DEFAULT 0;

    DECLARE cur CURSOR FOR
        SELECT a.dtl_no, a.dtl_lane_no, a.dtl_id, a.dtl_type, a.loc_id, a.grp_id
          FROM tb_gate_dtl a
         INNER JOIN tb_gate_loc b ON a.loc_id = b.loc_id AND b.use_yn = 'Y'
         INNER JOIN tb_gate_grp c ON a.loc_id = c.loc_id AND a.grp_id = c.grp_id AND c.use_yn = 'Y'
         WHERE a.use_yn = 'Y'
           AND a.dtl_ip = vDtlIP;

    DECLARE CONTINUE HANDLER FOR NOT FOUND SET done = 1;

    SET vCheckTime = DATE_FORMAT(NOW(), '%Y%m%d%H%i%s');

    START TRANSACTION;

    OPEN cur;
    lane_loop: LOOP
        FETCH cur INTO vDtlNo, vLaneNo, vDtlId, vDtlType, vLocId, vGrpId;
        IF done THEN LEAVE lane_loop; END IF;

        SET vSeq = NEXT VALUE FOR tb_net_state_seq;

        INSERT INTO tb_net_state
            (dtl_ip, dtl_no, dtl_lane_no, dtl_state, dtl_id, dtl_type, loc_id, grp_id,
             snd_raw, check_time, server_ip, server_cd, applied_seq, reg_date, mod_date)
        VALUES
            (vDtlIP, vDtlNo, vLaneNo, vState, vDtlId, vDtlType, vLocId, vGrpId,
             vRcvData, vCheckTime, vSvrIP, vServerCd, vSeq, NOW(), NOW())
        ON DUPLICATE KEY UPDATE

            dtl_state   = IF(applied_seq <= vSeq, vState, dtl_state),
            dtl_type    = IF(applied_seq <= vSeq, vDtlType, dtl_type),
            snd_raw     = IF(applied_seq <= vSeq, vRcvData, snd_raw),
            server_ip   = IF(applied_seq <= vSeq, vSvrIP, server_ip),
            server_cd   = IF(applied_seq <= vSeq, vServerCd, server_cd),
            check_time  = IF(applied_seq <= vSeq, vCheckTime, check_time),
            mod_date    = IF(applied_seq <= vSeq, NOW(), mod_date),
            applied_seq = IF(applied_seq <= vSeq, vSeq, applied_seq);
    END LOOP;
    CLOSE cur;

    COMMIT;
END$$
DELIMITER ;
