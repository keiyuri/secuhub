-- V15 — tb_opr_status 누적 카운터 컬럼 타입 정합화 (Hibernate ddl-auto=validate 실패 수정, 2026-08-12)
--
-- OprStatus 엔티티는 total/in/out/door 각 3종 카운터 중 "증가분(count)"은 Int로,
-- "누적/전일기준(total/before)"은 Long으로 매핑한다(누적값이 int 범위를 넘어갈 수 있어 의도적으로
-- Long 사용). 그러나 V1 베이스라인의 실제 컬럼은 전부 int(11)이었다 — V8/V11/V13/V14와 같은
-- 종류의 타입 불일치.
ALTER TABLE tb_opr_status
    MODIFY COLUMN opr_total_count BIGINT NOT NULL,
    MODIFY COLUMN opr_before_total BIGINT NOT NULL,
    MODIFY COLUMN opr_in_total     BIGINT NOT NULL,
    MODIFY COLUMN opr_in_before    BIGINT NOT NULL,
    MODIFY COLUMN opr_out_total    BIGINT NOT NULL,
    MODIFY COLUMN opr_out_before   BIGINT NOT NULL,
    MODIFY COLUMN opr_door_total   BIGINT NOT NULL,
    MODIFY COLUMN opr_door_before  BIGINT NOT NULL;
