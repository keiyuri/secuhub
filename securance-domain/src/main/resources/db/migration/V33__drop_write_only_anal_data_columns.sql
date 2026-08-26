-- ============================================================================
-- V33 — tb_data_rcv_anal.anal_data_* 중 GateControl이 write-only로 판단해 DROP한
-- 30개 컬럼을 이 저장소의 스키마 이력에서도 제거 (2026-08-26)
--
-- [경위] 별도 저장소 GateControl(SR_Speed_Server)이 2026-08-26 dev DB
-- (192.168.0.26:28031/securance_gate)에서 `usp_process_analysis_v4` INSERT 문을 정리하며
-- write-only(어디서도 SELECT하지 않음)로 판단한 33개 `anal_data_*` 컬럼 중 30개를 실제로
-- DROP했다(GateControl 커밋 8c840c3/41696f2, dev DB 실측 검증 완료 — `SHOW COLUMNS FROM
-- tb_data_rcv_anal LIKE 'anal_data%'`로 재확인, 남은 3개만 존재). 이에 맞춰
-- DataReceiveAnalysis 엔티티와 GatePacketPersister도 이 30개 필드 매핑을 제거했다.
--
-- V23/V26/V30은 (Flyway 이력이 없던 운영 DB와의 격차를 메우기 위해) 이 컬럼들을
-- `ADD COLUMN IF NOT EXISTS`로 만들어 둔 이력이라 여기서 되돌리지 않는다(이미 적용된
-- 마이그레이션은 수정하지 않는다는 원칙) — 대신 후속 마이그레이션으로 명시적으로 DROP한다.
-- 이렇게 해야 `flyway_schema_history`가 없는 신규/복구 환경에서 V1부터 전체를 재실행해도
-- V26/V30이 이 30개 컬럼을 되살리지 않고, dev DB와 동일하게 3개(anal_data_object_code/
-- anal_data_motor_operation_count/anal_data_master_in_total_count)만 남는다.
--
-- [조치] `DROP COLUMN IF EXISTS`로 멱등하게 제거한다 — 이미 컬럼이 없는 dev DB(수동으로
-- DROP됨)에서는 no-op이고, 아직 V26/V30이 만든 컬럼을 갖고 있는 다른 환경(신규 배포 등)에서는
-- 실제로 제거한다.
--
-- [배포 순서 경고 — 적대적 리뷰(Codex) 지적, 2026-08-26] 이 DROP은 "GateControl이 이미 이
-- 컬럼들을 채우지 않는 저장 프로시저로 전환됐다"는 가정에 의존한다. dev DB
-- (192.168.0.26:28031)에서는 실측으로 확인됐지만, 만약 같은 스키마를 공유하는 다른 환경
-- (스테이징/운영 등)에 구버전 `usp_process_analysis`/`usp_process_analysis_v4`(이 컬럼들을 여전히
-- INSERT하는 버전)가 남아 있는 상태에서 이 마이그레이션이 먼저 실행되면, 그 구버전 프로시저가
-- 매 패킷마다 "Unknown column" 오류로 깨진다 — 이 변경이 애초에 해결하려는 장애와 대칭이다.
-- 이를 완전히 막을 수는 없지만(다른 저장소 배포 상태를 SQL만으로 확정할 수 없음), 최소한의
-- 방어로 이 스키마에 현재 등록된 저장 프로시저 정의에 삭제 대상 컬럼명이 남아 있으면 마이그레이션
-- 자체를 실패시킨다. 단, `information_schema.ROUTINES.ROUTINE_DEFINITION`은 마이그레이션 실행
-- 계정에 SHOW_ROUTINE/SUPER 권한(또는 프로시저 정의자 권한)이 없으면 NULL만 보여 이 가드를
-- 무력화할 수 있다 — 이 저장소가 GateControl의 배포 상태를 검증할 수 있는 유일하고 완전한
-- 수단은 아니며, 실제로는 두 저장소 운영자 간 배포 순서 합의(GateControl 전환 완료 확인 후 이
-- 마이그레이션 적용)가 반드시 선행돼야 한다.
-- ============================================================================

SET @v33_legacy_ref_count = (
    SELECT COUNT(*)
    FROM information_schema.ROUTINES
    WHERE ROUTINE_SCHEMA = DATABASE()
      AND ROUTINE_TYPE = 'PROCEDURE'
      AND ROUTINE_DEFINITION LIKE '%anal_data_stx%'
);

SET @v33_guard_sql = IF(
    @v33_legacy_ref_count > 0,
    'SELECT * FROM v33_guard_legacy_procedure_references_dropped_columns',
    'DO 0'
);

PREPARE v33_guard_stmt FROM @v33_guard_sql;
EXECUTE v33_guard_stmt;
DEALLOCATE PREPARE v33_guard_stmt;

ALTER TABLE tb_data_rcv_anal
    -- V26이 만들었던 14개 중 anal_data_object_code를 제외한 13개
    DROP COLUMN IF EXISTS anal_data_stx,
    DROP COLUMN IF EXISTS anal_data_packet_len,
    DROP COLUMN IF EXISTS anal_data_protocol_ver,
    DROP COLUMN IF EXISTS anal_data_frame_option,
    DROP COLUMN IF EXISTS anal_data_address,
    DROP COLUMN IF EXISTS anal_data_command,
    DROP COLUMN IF EXISTS anal_data_subcommand,
    DROP COLUMN IF EXISTS anal_data_info_length,
    DROP COLUMN IF EXISTS anal_data_count,
    DROP COLUMN IF EXISTS anal_data_length,
    DROP COLUMN IF EXISTS anal_data_gate_name,
    DROP COLUMN IF EXISTS anal_data_ip,
    DROP COLUMN IF EXISTS anal_data_mac,
    -- V30이 만들었던 19개 중 anal_data_motor_operation_count/anal_data_master_in_total_count를
    -- 제외한 17개
    DROP COLUMN IF EXISTS anal_data_gate_lane_number,
    DROP COLUMN IF EXISTS anal_data_gate_lane_count,
    DROP COLUMN IF EXISTS anal_data_gate_type,
    DROP COLUMN IF EXISTS anal_data_user_mode,
    DROP COLUMN IF EXISTS anal_data_security_mode,
    DROP COLUMN IF EXISTS anal_data_inout_time,
    DROP COLUMN IF EXISTS anal_data_user_count,
    DROP COLUMN IF EXISTS anal_data_total_count,
    DROP COLUMN IF EXISTS anal_data_operation_sensor_status1,
    DROP COLUMN IF EXISTS anal_data_safety_sensor_status,
    DROP COLUMN IF EXISTS anal_data_operation_sensor_status2,
    DROP COLUMN IF EXISTS anal_data_optical_sensor_status,
    DROP COLUMN IF EXISTS anal_data_output_status,
    DROP COLUMN IF EXISTS anal_data_gate_operation_status,
    DROP COLUMN IF EXISTS anal_data_check_sum,
    DROP COLUMN IF EXISTS anal_data_packet_checksum,
    DROP COLUMN IF EXISTS anal_data_etx;
