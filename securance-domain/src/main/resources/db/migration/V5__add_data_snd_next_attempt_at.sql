-- Codex 적대적 리뷰 지적: SendControlJob의 배치 조회(findBySndYnAndChkYnOrderBySndId,
-- ORDER BY snd_id, LIMIT batchSize)는 항상 snd_id가 가장 작은 pending 행부터 채운다. 오프라인
-- 게이트처럼 계속 전송에 실패하는 행이 큐 앞쪽에 있으면 매 폴링마다 그 행(들)만 다시 선택되어,
-- 뒤에 적재된(더 큰 snd_id) 정상 게이트용 명령이 무기한 처리되지 못하는 헤드 오브 라인 차단이
-- 발생한다. 실패 시 "다음 재시도 가능 시각"을 DB에 남기고 조회 조건에서 아직 그 시각이 안 된
-- 행을 제외해, 실패한 행을 건너뛰고 뒤의 정상 행을 처리할 수 있게 한다.
ALTER TABLE tb_data_snd
    ADD COLUMN next_attempt_at DATETIME NULL AFTER chk_yn;
