-- ============================================================================
-- V38 — 레거시 저장 프로시저 `usp_net_check_data`가 `tb_net_state.applied_seq` 순서 보장을
-- 우회하던 데이터 유실 경로를 막는다.
--
-- 배경(2026-09-04 개발 DB(192.168.0.26:28031) 진단): `tb_net_state`에는 두 개의 쓰기 경로가
-- 공존한다.
--   1) 신규 Kotlin 서버 — [kr.co.securance.secuhub.server.connection.GateConnectionRegistryImpl]
--      이 [kr.co.securance.secuhub.domain.repository.NetStateRepository.upsertIfNewer]로 쓴다.
--      DB 전역 시퀀스(`tb_net_state_seq`, V32)에서 발급받은 `applied_seq`가 이미 반영된 값보다
--      크거나 같을 때만(`applied_seq <= VALUES(applied_seq)`) 갱신하는 조건부 UPSERT라, 지연된
--      옛 쓰기가 나중에 도착해도 최신 상태를 덮어쓰지 못한다(R-8, V31/V32 마이그레이션 KDoc 참고).
--   2) 레거시 저장 프로시저 `usp_net_check_data` — 이 DB에 여전히 남아 있고(개발 DB 실측:
--      2026-09-03 14:46:36경 다수 레인이 동시에 갱신된 이력 확인), `applied_seq`를 전혀 모르는
--      무조건 `INSERT ... ON DUPLICATE KEY UPDATE`다. 즉 이 프로시저가 신규 서버의 최신 쓰기
--      "이후"에 실행되면 — 예: 낡은 폴링 주기, 재시도, 또는 신규 서버와 나란히 떠 있는 레거시
--      클라이언트가 이 프로시저를 계속 호출하는 전환기 상황 — `applied_seq`를 갱신하지 않은 채
--      `dtl_state`/`check_time`을 조용히 과거 값으로 되돌릴 수 있다. 화면에는 게이트가 실제로는
--      온라인인데 오프라인으로(혹은 그 반대로) 잘못 표시되는 형태로 나타나는 데이터 유실이다.
--
-- 이 마이그레이션은 프로시저의 시그니처/호출 규약은 그대로 유지한 채, 내부 쓰기만 신규 경로와
-- 동일한 규칙으로 맞춘다: 매 레인 갱신마다 `tb_net_state_seq`에서 시퀀스를 하나 발급받아
-- `applied_seq`가 그 값 이하일 때만 반영한다. 두 쓰기 경로가 같은 전역 시퀀스를 공유하게 되므로,
-- 어느 쪽이 나중에 실행됐든 실제로 더 최신인 쪽이 항상 이긴다 — DB 재기동/재배포 없이 즉시
-- 적용된다.
-- ============================================================================

-- Codex 적대적 리뷰 지적(critical): 이 저장소의 V1 스키마에는 `tb_net_state.snd_raw` 컬럼이
-- 없다 — V1은 Flyway BASELINE이라 실제로 실행되어 만들어진 적이 없고(다른 마이그레이션 파일의
-- `snd_raw`는 전혀 다른 테이블 `tb_data_snd`의 것), 실측한 개발 DB(192.168.0.26)에만 레거시
-- 프로시저가 채워온 이 컬럼이 실존한다(V8/V36과 같은 종류의 "baseline에는 있지만 마이그레이션
-- 파일엔 없는" 드리프트). 이 사실을 모른 채 바로 아래 프로시저가 `snd_raw`를 참조하면, V1부터
-- 새로 적용하는 환경(CI, 신규 로컬 DB 등)에서는 컬럼이 없어 CREATE PROCEDURE가 실패하고 —
-- 그마저도 DROP PROCEDURE가 이미 커밋된 뒤라 기존 프로시저까지 사라진 채로 남는다. V8/V36과
-- 동일한 방식(`ADD COLUMN IF NOT EXISTS`)으로 먼저 정합화해, 어느 환경에서 실행하든 컬럼이
-- 확실히 있는 상태에서 프로시저를 만든다.
ALTER TABLE tb_net_state
    ADD COLUMN IF NOT EXISTS snd_raw LONGTEXT NULL COMMENT '레거시 usp_net_check_data가 채우는 원시 수신 패킷 16진 문자열';

DROP PROCEDURE IF EXISTS usp_net_check_data;

-- Codex 리뷰 지적(P1): DEFINER를 개발 DB 계정(`dba`@`%`)으로 고정하면, 그 계정이 없거나
-- 마이그레이션 실행 계정에 임의 DEFINER를 지정할 SUPER/SET USER 권한이 없는 환경(운영/신규
-- 환경 등)에서 이 CREATE가 실패한다 — 그것도 바로 위 DROP PROCEDURE가 이미 커밋된 뒤라, 실패
-- 시 기존 프로시저까지 사라진 채로 남는다. DEFINER 절을 아예 생략해 "이 문장을 실행하는 계정"을
-- 그대로 쓰도록 한다(Flyway가 어떤 DB 계정으로 접속하든 동작).
DELIMITER $$
CREATE PROCEDURE usp_net_check_data(
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
    DECLARE vLocId   BIGINT UNSIGNED DEFAULT 0;
    DECLARE vGrpId   BIGINT UNSIGNED DEFAULT 0;
    DECLARE vCheckTime VARCHAR(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
    -- 신규 서버(GateConnectionRegistryImpl)와 동일한 전역 시퀀스에서 발급받는다 — 별도 카운터를
    -- 두면 두 경로 사이의 순서를 다시 비교할 수 없게 되어 이 마이그레이션의 목적 자체가 무너진다.
    DECLARE vSeq     BIGINT UNSIGNED;
    DECLARE done     INT DEFAULT 0;

    DECLARE cur CURSOR FOR
        SELECT a.dtl_no, a.dtl_lane_no, a.dtl_id, a.loc_id, a.grp_id
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
        FETCH cur INTO vDtlNo, vLaneNo, vDtlId, vLocId, vGrpId;
        IF done THEN LEAVE lane_loop; END IF;

        -- 레인마다 발급한다(호출 1건 = 여러 레인) — 같은 호출 안에서도 나중에 처리된 레인이 더
        -- 큰 시퀀스를 받아, 이후 이 레인만 갱신하는 다른 호출과 비교할 때도 순서가 어긋나지 않는다.
        SET vSeq = NEXT VALUE FOR tb_net_state_seq;

        INSERT INTO tb_net_state
            (dtl_ip, dtl_no, dtl_lane_no, dtl_state, dtl_id, loc_id, grp_id,
             snd_raw, check_time, server_ip, applied_seq, reg_date, mod_date)
        VALUES
            (vDtlIP, vDtlNo, vLaneNo, vState, vDtlId, vLocId, vGrpId,
             vRcvData, vCheckTime, vSvrIP, vSeq, NOW(), NOW())
        ON DUPLICATE KEY UPDATE
            -- NetStateRepository.upsertIfNewer와 동일한 조건(`applied_seq <= 새 seq`)일 때만
            -- 반영한다 — 이미 더 최신 seq가 적용된 행이면 이 UPSERT는 조용히 no-op이 된다.
            dtl_state   = IF(applied_seq <= vSeq, vState, dtl_state),
            snd_raw     = IF(applied_seq <= vSeq, vRcvData, snd_raw),
            server_ip   = IF(applied_seq <= vSeq, vSvrIP, server_ip),
            check_time  = IF(applied_seq <= vSeq, vCheckTime, check_time),
            mod_date    = IF(applied_seq <= vSeq, NOW(), mod_date),
            applied_seq = IF(applied_seq <= vSeq, vSeq, applied_seq);
    END LOOP;
    CLOSE cur;

    COMMIT;
END$$
DELIMITER ;
