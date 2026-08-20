-- 코드 리뷰 지적 R-8(2026-08-20): GateConnectionRegistryImpl이 net_state 갱신 순서 역전을 막기
-- 위해 인메모리 시퀀스 맵(lastAppliedNetStateSeq) + 인메모리 락(netStateWriteLocks)을 함께 썼다.
-- 두 맵 다 evict을 넣지 않은 이유(락 인스턴스 교체 레이스)를 그 클래스 KDoc이 스스로 정석 해법으로
-- 지목한 것이 바로 이 컬럼이다 — applied_seq를 DB에 두고 "내 시퀀스가 이미 적용된 시퀀스보다 새롭지
-- 않으면 쓰지 않는다"는 조건부 UPSERT 한 문장으로 강제하면, 인메모리 상태 없이도(다중 인스턴스에서도
-- 동일하게) 순서 역전을 막을 수 있다.
ALTER TABLE tb_net_state
    ADD COLUMN applied_seq BIGINT NOT NULL DEFAULT 0
        COMMENT '이 행에 마지막으로 반영된 GateConnectionRegistryImpl.netStateWriteSequence 값 — 더 오래된(작은) seq의 지연 쓰기가 이 행을 덮어쓰지 못하게 막는 조건부 UPSERT에 쓴다'
    AFTER check_time;
