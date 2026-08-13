-- ============================================================================
-- V23 — tb_data_rcv_anal 레거시 anal_data_* 컬럼에 DEFAULT '' 부여 (2026-08-13, 정정판)
--
-- [경위] 최초 버전의 V23은 이 33개 컬럼을 전부 DROP하는 것이었으나, 별도 저장소인
-- GateControl(SR_Speed_Server/SR_Speed_Client, 게이트를 직접 제어하는 레거시 C#/.NET
-- 프로그램)을 확인한 결과 이 판단이 틀렸음이 드러나 철회했다(docs/작업일지.md 0021 참고).
-- 실 DB에 남아있는 저장 프로시저 usp_process_analysis가 지금도 게이트 패킷을 받을
-- 때마다 anal_data_* 33개 컬럼 전부에 실시간으로 INSERT하고 있고(SR_Speed_Server가
-- usp_rcv_data_raw → usp_process_analysis 경로로 매 패킷마다 CALL), anal_data_object_code는
-- GateControl 대시보드가 obj_cd로 지금도 조회하는 컬럼이다. DROP은 다음 게이트 패킷 수신
-- 즉시 그 프로시저를 "Unknown column" 오류로 깨뜨려 GateControl 전체의 게이트 수신 처리를
-- 중단시켰을 것이다.
--
-- [실제 원인] secuhub(Kotlin) 쪽 문제는 컬럼이 죽었다는 것이 아니라, DataReceiveAnalysis
-- 엔티티가 이 컬럼들을 매핑하지 않아 JPA가 생성하는 INSERT 문에서 항상 빠지는데, 그중 14개
-- (anal_data_stx/packet_len/protocol_ver/frame_option/address/command/subcommand/
-- object_code/info_length/count/length/gate_name/ip/mac)는 DEFAULT가 없어 MariaDB가
-- "Field 'anal_data_stx' doesn't have a default value"로 매 INSERT를 거부한다는 것이다
-- (2026-08-13 15:09 재현). 나머지 19개는 이미 DEFAULT ''가 있어 문제없다.
--
-- [조치] 컬럼을 건드리지 않고(GateControl 쪽 살아있는 사용과 충돌하지 않도록) 14개 컬럼에
-- 나머지 19개와 동일하게 DEFAULT ''만 추가한다. 값 자체는 usp_process_analysis가 채우는
-- 원본 Hex 파싱값이므로 secuhub가 직접 넣지 않는 이상 빈 문자열로 남으며, 이는 GateControl
-- 쪽 로직에 영향을 주지 않는다(GateControl은 자신이 직접 값을 채워 INSERT함).
-- 85만+ 행 테이블이라 ALGORITHM=INPLACE, LOCK=NONE으로 테이블 재구성 락을 피한다.
-- ============================================================================

ALTER TABLE tb_data_rcv_anal
    MODIFY COLUMN anal_data_stx          VARCHAR(10) NOT NULL DEFAULT '',
    MODIFY COLUMN anal_data_packet_len   VARCHAR(10) NOT NULL DEFAULT '',
    MODIFY COLUMN anal_data_protocol_ver VARCHAR(10) NOT NULL DEFAULT '',
    MODIFY COLUMN anal_data_frame_option VARCHAR(10) NOT NULL DEFAULT '',
    MODIFY COLUMN anal_data_address      VARCHAR(40) NOT NULL DEFAULT '',
    MODIFY COLUMN anal_data_command      VARCHAR(10) NOT NULL DEFAULT '',
    MODIFY COLUMN anal_data_subcommand   VARCHAR(10) NOT NULL DEFAULT '',
    MODIFY COLUMN anal_data_object_code  VARCHAR(10) NOT NULL DEFAULT '',
    MODIFY COLUMN anal_data_info_length  VARCHAR(10) NOT NULL DEFAULT '',
    MODIFY COLUMN anal_data_count        VARCHAR(10) NOT NULL DEFAULT '',
    MODIFY COLUMN anal_data_length       VARCHAR(10) NOT NULL DEFAULT '',
    MODIFY COLUMN anal_data_gate_name    VARCHAR(80) NOT NULL DEFAULT '',
    MODIFY COLUMN anal_data_ip           VARCHAR(20) NOT NULL DEFAULT '',
    MODIFY COLUMN anal_data_mac          VARCHAR(20) NOT NULL DEFAULT '',
    ALGORITHM = INPLACE,
    LOCK = NONE;
