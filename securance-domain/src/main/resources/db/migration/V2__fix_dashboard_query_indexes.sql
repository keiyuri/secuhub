-- 코드 리뷰에서 발견된 인덱스 미스매치 수정.
--
-- V1의 인덱스는 이미 배포/체크섬이 확정된 것으로 보고 직접 수정하지 않고, 여기서 보강한다.

-- 1) tb_data_rcv_anal: 대시보드 미해결 오류 조회
--    (WHERE err_type=3 AND has_error_event=true AND resolve_yn='N' AND anal_tp IN (...) AND anal_date >= :sinceDate)
--    기존 idx_anal_err3_scan(err_type, has_error_event, anal_id)에는 resolve_yn/anal_tp/anal_date가 없어
--    등호 필터까지만 인덱스를 타고 나머지는 스캔+필터링해야 한다. 쿼리 패턴에 맞는 인덱스를 추가한다.
--    resolve_yn도 등호 필터라 anal_tp보다 앞에 두어 선택도를 최대한 인덱스에서 활용한다
--    (적대적 리뷰 지적: DataReceiveAnalysisRepository의 두 쿼리에 resolve_yn='N' 조건이 누락돼
--    해결 처리된 오류가 대시보드에서 계속 미해결로 표시되던 버그를 함께 수정하며 반영).
ALTER TABLE tb_data_rcv_anal
    ADD INDEX idx_anal_err3_tp_date (err_type, has_error_event, resolve_yn, anal_tp, anal_date);

-- 2) tb_opr_status: OprStatusRepository.findByLocIdAndGrpIdAndIdOprDateBetween
--    (WHERE loc_id=? AND grp_id=? AND opr_date BETWEEN ? AND ?)
--    기존 idx_opr_status_date_loc_grp(opr_date, loc_id, grp_id)는 범위 조건 컬럼이 선두라
--    loc_id/grp_id 등호 필터를 인덱스만으로 좁히지 못한다. 등호 컬럼을 선두로 둔 인덱스를 추가한다.
ALTER TABLE tb_opr_status
    ADD INDEX idx_opr_status_loc_grp_date (loc_id, grp_id, opr_date);
