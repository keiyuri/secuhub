-- Opus 전체 리뷰 지적: SendControlJob이 1초 주기로 폴링하는
--   DataSendRepository.findBySndYnAndChkYnOrderBySndId(sndYn, chkYn)
-- 쿼리(WHERE snd_yn=? AND chk_yn=? ORDER BY snd_id)에 맞는 인덱스가 없다.
-- V1의 idx_data_snd_pending(snd_date, snd_yn, chk_yn, dtl_ip, dtl_lane_no)은 선두 컬럼이
-- snd_date라 이 쿼리에는 왼쪽 접두사 규칙상 전혀 활용되지 않아 매초 tb_data_snd 풀스캔이 발생한다.
-- V1 인덱스는 이미 배포/체크섬이 확정된 것으로 보고 직접 수정하지 않고(V2와 동일 원칙), 쿼리
-- 패턴에 맞는 인덱스를 별도로 추가한다. ORDER BY snd_id까지 커버해 정렬 비용도 없앤다.
ALTER TABLE tb_data_snd
    ADD INDEX idx_data_snd_poll (snd_yn, chk_yn, snd_id);
