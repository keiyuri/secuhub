-- ============================================================================
-- V22 — tb_opr_status에 dtl_ip/dtl_lane_no 조회 인덱스 추가 (2026-08-13 코드 리뷰)
--
-- OprStatusRepository.findLatestBefore는 [kr.co.securance.secuhub.server.db.OprStatusPersister]가
-- 게이트/레인마다 매 분(分) 버킷 저장 직전에 호출하는 hot path 쿼리다. 조건은
-- (dtl_ip, dtl_lane_no) 등호 + opr_date 범위이고 opr_date DESC, opr_seq DESC로 정렬한다.
--
-- 기존 인덱스는 PK(opr_date, opr_seq, dtl_ip, dtl_lane_no)와
-- idx_opr_status_date_loc_grp(opr_date, loc_id, grp_id)/idx_opr_status_loc_grp_date(loc_id, grp_id,
-- opr_date) 뿐이며, 어느 것도 dtl_ip/dtl_lane_no를 선두 컬럼으로 갖지 않아 이 쿼리는 매 실행마다
-- opr_date 범위 전체(또는 더 넓게)를 스캔해야 한다. (dtl_ip, dtl_lane_no, opr_date, opr_seq) 인덱스를
-- 추가해 등호 조건으로 좁힌 뒤 정렬까지 인덱스 순서로 커버되게 한다.
--
-- [Codex 어드버서리얼 리뷰 지적, docs/flyway-migration-recovery.md 재발 방지 원칙 적용]
-- MariaDB DDL은 암묵적으로 커밋된다 — ADD INDEX가 서버에서 실제로 완료된 직후 연결이 끊기면
-- Flyway는 V22를 실패로 기록하지만 인덱스 자체는 이미 테이블에 남는다. 이 상태에서 운영자가
-- flywayRepair 후 재실행하면 동일한 인덱스 이름으로 다시 ADD INDEX를 시도해
-- "Duplicate key name" 오류로 배포가 계속 막힌다(V18과 동일한 계열의 실패 모드). 인덱스는
-- V3처럼 트랜잭션 프로시저로 감쌀 수 없으므로(procedure 안 DDL도 각자 즉시 커밋되는 건 동일),
-- information_schema.statistics로 존재 여부를 먼저 확인해 없을 때만 실행하는 가드를 둔다 — 어느
-- 지점에서 재실행하든 최종 상태가 "인덱스 1개 존재"로 수렴한다.
-- ============================================================================
DELIMITER $$
CREATE PROCEDURE _v22_add_opr_status_dtl_lookup_index()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'tb_opr_status'
          AND index_name = 'idx_opr_status_dtl_ip_lane_date'
    ) THEN
        ALTER TABLE tb_opr_status
            ADD INDEX idx_opr_status_dtl_ip_lane_date (dtl_ip, dtl_lane_no, opr_date, opr_seq);
    END IF;
END$$
DELIMITER ;
CALL _v22_add_opr_status_dtl_lookup_index();
DROP PROCEDURE _v22_add_opr_status_dtl_lookup_index;
