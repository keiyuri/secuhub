-- ============================================================================
-- V34 — `tb_net_state` 조회 경로 2건이 인덱스를 못 타 풀스캔하던 문제 해소.
--
-- 배경(2026-09-04 개발 DB(192.168.0.26:28031) EXPLAIN 실측): 기존 인덱스는 PK
-- `(dtl_ip, dtl_lane_no, loc_id, grp_id)`와 `IDX_NET_STATE_LOC (loc_id, grp_id, dtl_state)`
-- 뿐이다. 다음 두 조회는 둘 중 어느 인덱스도 선두 컬럼으로 활용하지 못해 `type=ALL`(전체 스캔)로
-- 실행된다(개발 DB는 아직 115행이라 체감되지 않지만, 위치/그룹이 늘어날수록 그대로 스캔 비용이
-- 커진다):
--   - [kr.co.securance.secuhub.domain.repository.NetStateRepository.findByIdGrpId] —
--     `GateResetGridService.rowsFor`(#3 GateReset 검색 그리드)가 그룹 화면을 열 때마다 호출한다.
--     `grp_id`는 `tb_gate_grp` PK라 전역적으로 유일하므로 `loc_id` 없이 `grp_id` 단독으로도
--     의미가 있다(`WHERE grp_id = :grpId`) — 그런데 `grp_id`가 선두가 아닌 인덱스만 있어 그 값을
--     못 쓴다.
--   - [kr.co.securance.secuhub.domain.repository.NetStateRepository.countByDtlState] —
--     대시보드(`DashboardService`)가 페이지를 열 때마다 온라인/오프라인 게이트 수 위젯에 쓴다.
--     `dtl_state` 단독 조건은 어느 기존 인덱스에서도 선두 컬럼이 아니다.
--
-- `dtl_ip`가 이미 PK 선두라 `findByIdDtlIpAndIdDtlLaneNo`는 그대로 PK를 타므로 대상이 아니다.
-- ============================================================================

ALTER TABLE tb_net_state
    ADD INDEX IF NOT EXISTS idx_net_state_grp (grp_id),
    ADD INDEX IF NOT EXISTS idx_net_state_state (dtl_state);
