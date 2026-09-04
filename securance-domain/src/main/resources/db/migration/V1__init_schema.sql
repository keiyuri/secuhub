-- ============================================================================
-- secuhub(securance) V1 초기 스키마 (2026-09-03 1차 스쿼시 / 2026-09-04 2차 스쿼시)
--
-- [1차 스쿼시, 2026-09-03] 기존 V1~V32(32개 마이그레이션)를 하나로 병합했다. 병합 방법: 32개
-- 마이그레이션 파일을 실제 MariaDB 10.11 컨테이너에 처음부터 순서대로 전부 적용한 뒤
-- `mysqldump --no-data`로 최종 스키마를 그대로 떠서 이 파일의 CREATE TABLE 구문으로 삼았다.
-- 이 과정에서 마이그레이션 체인 자체의 버그 2건(V23의 컬럼 참조 순서 오류, V26의
-- ALGORITHM=INPLACE 강제)도 함께 드러나 해소됐다.
--
-- [2차 스쿼시, 2026-09-04] 이후 쌓인 V33~V39(7개, 데이터 시드 V34 제외 6개 DDL)를 다시 이
-- 파일로 병합했다 — 같은 방법(신선한 스크래치 DB에 V1 + V33/V35/V36/V37/V38/V39를 그대로
-- 순서대로 적용한 뒤 `mysqldump --no-data --routines`로 결과를 그대로 캡처)으로 검증했다.
-- V34(GATE_TYPE 코드 재시딩)는 별도 DDL이 없고 이 파일 하단의 시드 INSERT와 완전히
-- 동일한 내용이라 병합 후 자연히 흡수된다.
--
-- 계기: 로컬 개발 DB(`localhost:28031` = `192.168.0.26:28031`, 작업일지 0106/0107 참고)에서
-- V37이 `flyway_schema_history`상 `success`로 기록돼 있는데도 실제 컬럼 타입이 며칠 뒤 다시
-- 레거시 TINYINT로 돌아가 있는 드리프트가 재확인됐다(작업일지 0109 참고) — 이 DB는 여러
-- 워크트리 세션과 별도 저장소(GateControl/SR_Speed_Server, V33/V36/V38 코멘트 참고)가 동시에
-- 접속·수정하는 공유 개발 DB라, Flyway 이력만으로는 실제 스키마 상태를 신뢰할 수 없다는 것이
-- 반복 확인된 셈이다. V34/V36/V37/V38처럼 "실측 후 반영"하는 사후 보정 마이그레이션을 계속
-- 쌓는 대신, 그 시점까지의 최종 상태를 다시 한번 V1로 눌러 담아 이력을 단순화했다 — 이
-- 스쿼시 이후에는 BASELINE 이력을 가진 레거시 DB에 대한 자동 ALTER를 새로 추가하지 않는다는
-- 원칙을 [flyway-migration-recovery.md](../../../../../docs/flyway-migration-recovery.md)에
-- 명문화했다(레거시 DB는 수동/문서화된 조율 대상으로 남긴다).
--
-- **이미 한 번이라도 배포된 환경이 생긴 뒤에는 이런 병합을 다시 해서는 안 된다** — Flyway
-- 체크섬이 파일 내용을 검증하므로 기존 배포가 깨진다.
--
-- 레거시 대비 변경점(원래 V1 KDoc):
--   1) FOREIGN_KEY_CHECKS=0으로 운영되던 논리적 관계에 실제 FK를 추가했다.
--   2) tb_data_rcv_anal_evt/_plm/_state 샤드 3종은 통합하지 않고 단일 tb_data_rcv_anal +
--      anal_tp 구분자로 합쳤다(중복 저장 제거, 계획서 4.2절).
--   3) tb_log_event(=tb_log 중복), tb_data_rcv_state(=tb_data_rcv_anal_state로 대체됨)는 이식하지 않는다.
-- ============================================================================

SET FOREIGN_KEY_CHECKS = 0;

CREATE SEQUENCE `tb_net_state_seq` start with 1 minvalue 1 maxvalue 9223372036854775806 increment by 1 cache 1000 nocycle ENGINE=InnoDB;
DO SETVAL(`tb_net_state_seq`, 1, 0);
CREATE TABLE `tb_code` (
  `code_grp` varchar(10) NOT NULL COMMENT '코드그룹 (예: GATE_TYPE)',
  `code_cd` varchar(20) NOT NULL COMMENT '코드값',
  `code_nm` varchar(100) NOT NULL COMMENT '코드명(표시용)',
  `code_val` varchar(50) DEFAULT NULL COMMENT '하드웨어/프로토콜 매핑 값',
  `code_desc` varchar(255) DEFAULT NULL COMMENT '설명',
  `disp_order` int(10) unsigned NOT NULL DEFAULT 0,
  `use_yn` bit(1) NOT NULL DEFAULT b'1',
  `reg_date` datetime NOT NULL DEFAULT current_timestamp(),
  `mod_date` datetime NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
  PRIMARY KEY (`code_grp`,`code_cd`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='코드 마스터';
CREATE TABLE `tb_data_rcv` (
  `rcv_id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
  `rcv_date` varchar(20) NOT NULL COMMENT 'yyyyMMddHHmm',
  `dtl_ip` varchar(20) NOT NULL,
  `dtl_lane_no` int(10) unsigned NOT NULL,
  `dtl_type` int(11) DEFAULT NULL,
  `dtl_no` int(10) unsigned DEFAULT NULL,
  `dtl_id` bigint(20) unsigned DEFAULT NULL,
  `loc_id` bigint(20) unsigned DEFAULT NULL,
  `grp_id` bigint(20) unsigned DEFAULT NULL,
  `rcv_header` varchar(100) DEFAULT NULL COMMENT '헤더(27바이트) 16진 문자열',
  `rcv_data` longtext DEFAULT NULL COMMENT 'DataInfo+Data 16진 문자열',
  `rcv_data_info` varchar(150) DEFAULT NULL,
  `rcv_data_lane` longtext DEFAULT NULL,
  `rcv_data_log` longtext DEFAULT NULL,
  `rcv_tail` varchar(20) DEFAULT NULL,
  `rcv_unixtime` double(22,3) DEFAULT NULL,
  `mod_date` datetime(3) NOT NULL DEFAULT current_timestamp(3) ON UPDATE current_timestamp(3),
  PRIMARY KEY (`rcv_id`),
  KEY `idx_data_rcv_date_ip_lane` (`rcv_date`,`dtl_ip`,`dtl_lane_no`),
  KEY `idx_data_rcv_mod_date` (`mod_date`),
  KEY `IDX_DATA_RCV_01` (`rcv_date`,`dtl_ip`,`dtl_lane_no`),
  KEY `IDX_DATA_RCV_02` (`mod_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='원시 수신 패킷';
CREATE TABLE `tb_data_rcv_ack` (
  `ack_id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
  `ack_date` varchar(20) NOT NULL,
  `dtl_ip` varchar(20) NOT NULL,
  `dtl_lane_no` int(10) unsigned NOT NULL,
  `dtl_id` bigint(20) unsigned DEFAULT NULL,
  `ack_raw` longtext DEFAULT NULL,
  `ack_header` varchar(100) DEFAULT NULL,
  `ack_data` longtext DEFAULT NULL,
  `ack_tail` varchar(20) DEFAULT NULL,
  `ack_unixtime` double(22,3) DEFAULT NULL,
  PRIMARY KEY (`ack_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='ACK 응답';
CREATE TABLE `tb_data_rcv_anal` (
  `anal_id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
  `anal_date` varchar(20) NOT NULL COMMENT 'yyyyMMddHHmm',
  `anal_tp` varchar(5) NOT NULL COMMENT 'NOR/EVT/PLM/STA/ERR',
  `anal_type` varchar(5) NOT NULL DEFAULT 'B' COMMENT '분석 데이터 형식 (레거시 트리거는 항상 B)',
  `rcv_date` varchar(20) NOT NULL DEFAULT '',
  `rcv_id` bigint(20) unsigned NOT NULL DEFAULT 0,
  `dtl_ip` varchar(20) NOT NULL,
  `dtl_lane_no` int(10) unsigned NOT NULL,
  `dtl_type` int(11) NOT NULL DEFAULT 1,
  `dtl_type_cd` varchar(10) DEFAULT NULL,
  `dtl_no` int(10) unsigned NOT NULL DEFAULT 1,
  `dtl_id` bigint(20) unsigned NOT NULL DEFAULT 0,
  `loc_id` bigint(20) unsigned DEFAULT NULL,
  `grp_id` bigint(20) unsigned DEFAULT NULL,
  `obj_cd` varchar(10) NOT NULL DEFAULT '' COMMENT '패킷 오브젝트 코드(4D/4E/4C 등)',
  `anal_data_object_code` varchar(10) NOT NULL DEFAULT '',
  `anal_data_motor_operation_count` varchar(20) NOT NULL DEFAULT '',
  `anal_data_master_in_total_count` varchar(20) NOT NULL DEFAULT '',
  `rcv_raw` longtext DEFAULT NULL COMMENT '원본 수신 패킷 16진 문자열',
  `anal_header` varchar(100) DEFAULT NULL,
  `anal_data` longtext DEFAULT NULL,
  `anal_tail` varchar(20) DEFAULT NULL,
  `desc_data_info_length` varchar(10) NOT NULL DEFAULT '',
  `desc_gate_name` varchar(80) NOT NULL DEFAULT '',
  `desc_gate_ip` varchar(20) NOT NULL DEFAULT '',
  `desc_gate_lane_count` varchar(20) NOT NULL DEFAULT '',
  `desc_gate_lane_number` varchar(20) NOT NULL DEFAULT '',
  `desc_gate_type` varchar(50) NOT NULL DEFAULT '',
  `desc_user_mode` varchar(30) NOT NULL DEFAULT '',
  `user_mode_cd` varchar(10) DEFAULT NULL,
  `desc_security_mode` varchar(20) NOT NULL DEFAULT '',
  `security_mode_cd` varchar(10) DEFAULT NULL,
  `desc_inout_time` varchar(20) NOT NULL DEFAULT '',
  `desc_user_count` varchar(20) NOT NULL DEFAULT '',
  `desc_total_count` varchar(20) NOT NULL DEFAULT '',
  `desc_operation01` varchar(20) DEFAULT NULL COMMENT 'S00: BACK RUSH 등 운영 센서 이상',
  `desc_operation02` varchar(20) DEFAULT NULL,
  `desc_operation03` varchar(20) DEFAULT NULL,
  `desc_operation04` varchar(20) DEFAULT NULL,
  `desc_operation05` varchar(20) DEFAULT NULL,
  `desc_operation06` varchar(20) DEFAULT NULL,
  `desc_operation07` varchar(20) DEFAULT NULL,
  `desc_operation08` varchar(20) DEFAULT NULL,
  `desc_motor_count` int(11) NOT NULL DEFAULT 0,
  `desc_master_in_total` int(11) NOT NULL DEFAULT 0,
  `desc_safety01` varchar(20) DEFAULT NULL COMMENT 'S04~S07: 안전 센서 이상',
  `desc_safety02` varchar(20) DEFAULT NULL,
  `desc_safety03` varchar(20) DEFAULT NULL,
  `desc_safety04` varchar(20) DEFAULT NULL,
  `desc_gate_status01` varchar(50) DEFAULT NULL COMMENT '1.BACK RUSH',
  `desc_gate_status02` varchar(50) DEFAULT NULL COMMENT '2.TAIL GATING',
  `desc_gate_status03` varchar(50) DEFAULT NULL COMMENT '3.WAITING',
  `desc_gate_status04` varchar(50) DEFAULT NULL COMMENT '4.ANTI-PASS',
  `desc_gate_status05` varchar(50) DEFAULT NULL COMMENT '5.OPEN/CLOSED',
  `desc_gate_status06` varchar(50) DEFAULT NULL COMMENT '6.REMOCON',
  `desc_gate_status07` varchar(50) DEFAULT NULL COMMENT '7.FIRE ALARM',
  `desc_gate_status08` varchar(50) DEFAULT NULL COMMENT '8.AC-POWER',
  `desc_gate_status09` varchar(50) DEFAULT NULL COMMENT '9.SUB BOARD COMM',
  `desc_gate_status10` varchar(50) DEFAULT NULL COMMENT '10.MAIN MOTOR',
  `desc_gate_status11` varchar(50) DEFAULT NULL COMMENT '11.SLAVE MOTOR',
  `desc_gate_status12` varchar(50) DEFAULT NULL COMMENT '12.EMERGENCY',
  `err_type` int(11) DEFAULT NULL,
  `resolve_yn` varchar(1) NOT NULL DEFAULT 'N',
  `resolve_user` varchar(50) DEFAULT NULL,
  `resolve_date` datetime DEFAULT NULL,
  `has_status_event` tinyint(1) GENERATED ALWAYS AS (case when coalesce(`desc_gate_status01`,'') <> '' or coalesce(`desc_gate_status02`,'') <> '' or coalesce(`desc_gate_status03`,'') <> '' or coalesce(`desc_gate_status04`,'') <> '' or coalesce(`desc_gate_status05`,'') <> '' or coalesce(`desc_gate_status06`,'') <> '' or coalesce(`desc_gate_status07`,'') <> '' or coalesce(`desc_gate_status08`,'') <> '' or coalesce(`desc_gate_status12`,'') <> '' then 1 else 0 end) VIRTUAL,
  `has_error_event` tinyint(1) GENERATED ALWAYS AS (case when coalesce(`desc_operation01`,'') <> '' or coalesce(`desc_operation02`,'') <> '' or coalesce(`desc_operation03`,'') <> '' or coalesce(`desc_operation04`,'') <> '' or coalesce(`desc_operation05`,'') <> '' or coalesce(`desc_operation06`,'') <> '' or coalesce(`desc_operation07`,'') <> '' or coalesce(`desc_operation08`,'') <> '' or coalesce(`desc_safety01`,'') <> '' or coalesce(`desc_safety02`,'') <> '' or coalesce(`desc_safety03`,'') <> '' or coalesce(`desc_safety04`,'') <> '' or coalesce(`desc_gate_status09`,'') <> '' or coalesce(`desc_gate_status10`,'') <> '' or coalesce(`desc_gate_status11`,'') <> '' then 1 else 0 end) VIRTUAL,
  `reg_date` datetime NOT NULL DEFAULT current_timestamp(),
  `mod_date` datetime(3) NOT NULL DEFAULT current_timestamp(3) ON UPDATE current_timestamp(3),
  PRIMARY KEY (`anal_id`),
  KEY `idx_anal_loc_grp_date` (`loc_id`,`grp_id`,`anal_date`),
  KEY `idx_anal_dtl_latest` (`dtl_id`,`anal_id`),
  KEY `idx_anal_err3_scan` (`err_type`,`has_error_event`,`anal_id`),
  KEY `idx_anal_tp_date` (`anal_tp`,`anal_date`),
  KEY `idx_anal_err3_tp_date` (`err_type`,`has_error_event`,`resolve_yn`,`anal_tp`,`anal_date`),
  KEY `IDX_ANAL_LANE_LATEST` (`dtl_ip`,`dtl_lane_no`,`anal_id`),
  KEY `IDX_DATA_ANAL_ERR` (`dtl_ip`,`dtl_lane_no`,`anal_date`,`err_type`,`resolve_yn`),
  KEY `IDX_DATA_ANAL_DATE` (`anal_date`,`dtl_ip`,`dtl_lane_no`),
  KEY `IDX_QUERY_OPTIMIZED` (`dtl_ip`,`dtl_lane_no`,`anal_date`,`anal_tp`,`err_type`),
  KEY `IDX_ERROR_RESOLVE` (`err_type`,`resolve_yn`,`anal_date`),
  KEY `IDX_TYPE_CODE` (`dtl_type_cd`),
  KEY `IDX_USER_MODE` (`user_mode_cd`),
  KEY `IDX_SECURITY_MODE` (`security_mode_cd`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='수신 데이터 분석(통합, 계획서 4.2절)';
CREATE TABLE `tb_data_rcv_fail` (
  `fail_id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
  `fail_date` varchar(20) NOT NULL,
  `dtl_ip` varchar(20) DEFAULT NULL,
  `dtl_lane_no` int(10) unsigned DEFAULT NULL,
  `rcv_raw` longtext DEFAULT NULL COMMENT '보드 타입 불일치 등으로 거부된 원시 패킷',
  PRIMARY KEY (`fail_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='수신 실패(Dead-letter)';
CREATE TABLE `tb_data_snd` (
  `snd_id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
  `snd_date` varchar(20) NOT NULL COMMENT 'yyyyMMddHHmmss',
  `snd_yn` char(1) NOT NULL DEFAULT 'N' COMMENT '게이트로 전송 완료 여부',
  `chk_yn` char(1) NOT NULL DEFAULT 'N' COMMENT '서버 전송 확인 여부',
  `next_attempt_at` datetime DEFAULT NULL,
  `dtl_ip` varchar(20) NOT NULL,
  `dtl_lane_no` int(10) unsigned NOT NULL,
  `dtl_type` int(11) NOT NULL DEFAULT 1,
  `dtl_id` bigint(20) unsigned NOT NULL DEFAULT 0,
  `loc_id` bigint(20) unsigned NOT NULL DEFAULT 0,
  `grp_id` bigint(20) unsigned NOT NULL DEFAULT 0,
  `snd_user` varchar(20) NOT NULL DEFAULT '',
  `snd_server` varchar(20) NOT NULL DEFAULT '',
  `snd_type_cd` varchar(20) NOT NULL DEFAULT '',
  `snd_data_tp` varchar(20) NOT NULL DEFAULT '',
  `snd_raw` longtext NOT NULL,
  `snd_header` varchar(100) NOT NULL DEFAULT '',
  `snd_data` longtext NOT NULL,
  `snd_tail` varchar(20) NOT NULL DEFAULT '',
  `version` bigint(20) NOT NULL DEFAULT 0,
  `reg_date` datetime NOT NULL DEFAULT current_timestamp(),
  `mod_date` datetime NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
  PRIMARY KEY (`snd_id`),
  KEY `idx_data_snd_pending` (`snd_date`,`snd_yn`,`chk_yn`,`dtl_ip`,`dtl_lane_no`),
  KEY `idx_data_snd_poll` (`snd_yn`,`chk_yn`,`snd_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='제어 명령 발송 큐';
CREATE TABLE `tb_gate_dtl` (
  `dtl_id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
  `loc_id` bigint(20) unsigned NOT NULL,
  `grp_id` bigint(20) unsigned NOT NULL,
  `dtl_ip` varchar(20) NOT NULL COMMENT '게이트 장비 IP',
  `dtl_lane_no` int(10) unsigned NOT NULL,
  `dtl_type` int(11) NOT NULL,
  `connect_type` int(11) NOT NULL DEFAULT 1,
  `dtl_no` int(10) unsigned DEFAULT NULL COMMENT '시리얼 연결 번호',
  `dtl_mac` varchar(200) DEFAULT NULL,
  `dtl_nm` varchar(200) DEFAULT NULL COMMENT '게이트 표시명',
  `use_yn` char(1) NOT NULL DEFAULT 'Y',
  `analysis_yn` char(1) NOT NULL DEFAULT 'Y' COMMENT '분석/제어 대상 여부',
  `dtl_x` int(11) DEFAULT NULL,
  `dtl_y` int(11) DEFAULT NULL,
  `dtl_icon` varchar(200) DEFAULT NULL,
  `reg_date` datetime NOT NULL DEFAULT current_timestamp(),
  `mod_date` datetime NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
  PRIMARY KEY (`dtl_id`),
  UNIQUE KEY `uq_gate_dtl_ip_lane` (`dtl_ip`,`dtl_lane_no`),
  KEY `fk_gate_dtl_grp` (`grp_id`),
  KEY `idx_gate_dtl_loc_grp` (`loc_id`,`grp_id`,`use_yn`),
  CONSTRAINT `fk_gate_dtl_grp` FOREIGN KEY (`grp_id`) REFERENCES `tb_gate_grp` (`grp_id`),
  CONSTRAINT `fk_gate_dtl_loc` FOREIGN KEY (`loc_id`) REFERENCES `tb_gate_loc` (`loc_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='게이트(레인) 상세';
CREATE TABLE `tb_gate_grp` (
  `grp_id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
  `loc_id` bigint(20) unsigned NOT NULL,
  `grp_nm` varchar(200) NOT NULL COMMENT '게이트 그룹명',
  `lane_cnt` int(10) unsigned NOT NULL DEFAULT 1,
  `dtl_type` int(11) DEFAULT NULL,
  `link_type` int(11) NOT NULL DEFAULT 1,
  `use_yn` char(1) NOT NULL DEFAULT 'Y',
  `grp_x` int(11) DEFAULT NULL,
  `grp_y` int(11) DEFAULT NULL,
  `grp_width` int(11) DEFAULT NULL,
  `grp_height` int(11) DEFAULT NULL,
  `grp_map_w` int(11) DEFAULT NULL,
  `grp_map_h` int(11) DEFAULT NULL,
  `grp_map` varchar(200) DEFAULT NULL,
  `rtsp_addr` varchar(200) DEFAULT NULL,
  `onvif_addr` varchar(200) DEFAULT NULL,
  `reg_date` datetime NOT NULL DEFAULT current_timestamp(),
  `mod_date` datetime NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
  PRIMARY KEY (`grp_id`),
  KEY `idx_gate_grp_loc` (`loc_id`,`use_yn`),
  CONSTRAINT `fk_gate_grp_loc` FOREIGN KEY (`loc_id`) REFERENCES `tb_gate_loc` (`loc_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='게이트 그룹';
CREATE TABLE `tb_gate_loc` (
  `loc_id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
  `loc_nm` varchar(200) NOT NULL COMMENT '설치 위치명',
  `use_yn` char(1) NOT NULL DEFAULT 'Y',
  `loc_x` int(11) DEFAULT NULL COMMENT '배치도 X 좌표',
  `loc_y` int(11) DEFAULT NULL COMMENT '배치도 Y 좌표',
  `loc_width` int(11) DEFAULT NULL,
  `loc_height` int(11) DEFAULT NULL,
  `loc_map_w` int(11) DEFAULT NULL,
  `loc_map_h` int(11) DEFAULT NULL,
  `loc_map` varchar(200) DEFAULT NULL COMMENT '배치도 이미지 파일명',
  `reg_date` datetime NOT NULL DEFAULT current_timestamp(),
  `mod_date` datetime NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
  PRIMARY KEY (`loc_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='게이트 설치 위치';
CREATE TABLE `tb_gate_log` (
  `log_id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
  `dtl_ip` varchar(20) NOT NULL COMMENT '게이트 IP',
  `dtl_lane_no` int(10) unsigned NOT NULL,
  `event_type` int(10) unsigned NOT NULL,
  `object_code` int(10) unsigned NOT NULL,
  `code` int(10) unsigned NOT NULL,
  `err_code` int(10) unsigned NOT NULL,
  `operation_mode` int(10) unsigned NOT NULL,
  `reader_type` int(10) unsigned NOT NULL,
  `reader_number` int(10) unsigned NOT NULL,
  `door_status` int(10) unsigned NOT NULL,
  `function_code` int(10) unsigned NOT NULL,
  `event_time` datetime NOT NULL COMMENT '로그 엔트리 BCD 시각(장치 시각, 수신 시각 아님)',
  `user_data1` varchar(24) DEFAULT NULL COMMENT 'User Data1(12byte) 16진 문자열',
  `user_data2` varchar(16) DEFAULT NULL COMMENT 'User Data2(8byte, Old User ID) 16진 문자열',
  `reg_date` datetime NOT NULL DEFAULT current_timestamp() COMMENT 'DB 적재 시각',
  PRIMARY KEY (`log_id`),
  UNIQUE KEY `uk_gate_log_natural` (`dtl_ip`,`dtl_lane_no`,`event_time`,`event_type`,`code`,`err_code`,`function_code`),
  KEY `idx_gate_log_ip_time` (`dtl_ip`,`event_time`),
  KEY `idx_gate_log_event_time` (`event_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='GATE_LOG(0x61) 수신 로그 엔트리';
CREATE TABLE `tb_net_state` (
  `dtl_ip` varchar(20) NOT NULL,
  `dtl_lane_no` int(10) unsigned NOT NULL,
  `loc_id` bigint(20) unsigned NOT NULL,
  `grp_id` bigint(20) unsigned NOT NULL,
  `dtl_id` bigint(20) unsigned DEFAULT NULL,
  `dtl_type` int(11) DEFAULT NULL,
  `dtl_no` int(10) unsigned DEFAULT NULL,
  `dtl_state` char(1) NOT NULL DEFAULT 'N' COMMENT '연결 상태 Y=온라인, N=오프라인',
  `dtl_ping` char(1) DEFAULT NULL COMMENT 'dtl_state=N일 때 ping 결과',
  `check_time` varchar(20) DEFAULT NULL,
  `applied_seq` bigint(20) NOT NULL DEFAULT 0 COMMENT '이 행에 마지막으로 반영된 GateConnectionRegistryImpl.netStateWriteSequence 값 — 더 오래된(작은) seq의 지연 쓰기가 이 행을 덮어쓰지 못하게 막는 조건부 UPSERT에 쓴다',
  `server_ip` varchar(20) DEFAULT NULL COMMENT '이 상태를 보고한 백엔드 인스턴스 IP',
  `server_cd` varchar(20) DEFAULT NULL,
  `reg_date` timestamp NOT NULL DEFAULT current_timestamp(),
  `mod_date` timestamp NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
  `snd_raw` longtext DEFAULT NULL COMMENT '레거시 usp_net_check_data가 채우는 원시 수신 패킷 16진 문자열',
  PRIMARY KEY (`dtl_ip`,`dtl_lane_no`,`loc_id`,`grp_id`),
  KEY `idx_net_state_loc_grp_state` (`loc_id`,`grp_id`,`dtl_state`),
  KEY `idx_net_state_grp` (`grp_id`),
  KEY `idx_net_state_state` (`dtl_state`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='게이트 연결 상태';
CREATE TABLE `tb_opr_status` (
  `opr_date` varchar(20) NOT NULL COMMENT 'yyyyMMddHHmm',
  `opr_seq` int(11) NOT NULL DEFAULT 1,
  `dtl_ip` varchar(20) NOT NULL,
  `dtl_lane_no` int(10) unsigned NOT NULL,
  `dtl_type` int(10) unsigned NOT NULL DEFAULT 1 COMMENT '게이트 종류(1:Speed,2:Flap,3:Turn,4:Fast)',
  `dtl_id` bigint(20) unsigned DEFAULT NULL,
  `dtl_no` int(10) unsigned NOT NULL DEFAULT 1 COMMENT 'Serial 연결 번호(TCP 경로는 미사용, 항상 0)',
  `loc_id` bigint(20) unsigned DEFAULT NULL,
  `grp_id` bigint(20) unsigned DEFAULT NULL,
  `opr_gate_type` varchar(20) DEFAULT NULL COMMENT 'GateStatusAnalyzer.describeGateType 결과',
  `opr_user_mode` varchar(20) DEFAULT NULL COMMENT '운영 모드 코드(문자열)',
  `opr_security_mode` varchar(20) DEFAULT NULL COMMENT '보안 등급 코드(문자열)',
  `opr_inout_time` int(11) NOT NULL DEFAULT 0 COMMENT '인증 후 미진출 시 닫힘 시간',
  `opr_user_count` int(11) DEFAULT NULL,
  `opr_total_count` bigint(20) NOT NULL,
  `opr_before_total` bigint(20) NOT NULL,
  `opr_in_count` int(11) DEFAULT NULL,
  `opr_in_total` bigint(20) NOT NULL,
  `opr_in_before` bigint(20) NOT NULL,
  `opr_out_count` int(11) DEFAULT NULL,
  `opr_out_total` bigint(20) NOT NULL,
  `opr_out_before` bigint(20) NOT NULL,
  `opr_door_count` int(11) DEFAULT NULL,
  `opr_door_total` bigint(20) NOT NULL,
  `opr_door_before` bigint(20) NOT NULL,
  `use_yn` char(1) NOT NULL DEFAULT 'Y',
  `reg_user` varchar(50) NOT NULL DEFAULT 'service',
  `reg_date` datetime NOT NULL DEFAULT current_timestamp(),
  `mod_date` datetime NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
  PRIMARY KEY (`opr_date`,`opr_seq`,`dtl_ip`,`dtl_lane_no`),
  KEY `idx_opr_status_date_loc_grp` (`opr_date`,`loc_id`,`grp_id`),
  KEY `idx_opr_status_loc_grp_date` (`loc_id`,`grp_id`,`opr_date`),
  KEY `idx_opr_status_dtl_ip_lane_date` (`dtl_ip`,`dtl_lane_no`,`opr_date`,`opr_seq`),
  KEY `IDX_OPR_DATE_LOC` (`opr_date`,`loc_id`,`grp_id`),
  KEY `IDX_OPR_DATE_DTL` (`opr_date`,`dtl_ip`,`dtl_lane_no`),
  KEY `IDX_OPR_STATUS_DAILY` (`use_yn`,`opr_date`,`loc_id`,`grp_id`,`dtl_ip`),
  KEY `IDX_OPR_DTL_DATE` (`dtl_id`,`dtl_ip`,`dtl_lane_no`,`use_yn`,`opr_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='분단위 운영 카운터';
CREATE TABLE `tb_opr_status_outbox` (
  `outbox_id` bigint(20) NOT NULL AUTO_INCREMENT,
  `dtl_ip` varchar(20) NOT NULL,
  `dtl_lane_no` int(11) NOT NULL,
  `dtl_id` bigint(20) NOT NULL,
  `dtl_type` int(11) NOT NULL,
  `loc_id` bigint(20) NOT NULL,
  `grp_id` bigint(20) NOT NULL,
  `opr_date` varchar(20) NOT NULL COMMENT '분(分) 버킷 키(dateKey)',
  `since_date` varchar(20) NOT NULL COMMENT 'PREV 조회 하한(sinceDateKey)',
  `curr_total` bigint(20) NOT NULL,
  `curr_door` bigint(20) NOT NULL,
  `curr_in` bigint(20) NOT NULL,
  `gate_type_raw` int(11) NOT NULL,
  `user_mode_raw` int(11) NOT NULL,
  `security_mode_raw` int(11) NOT NULL,
  `inout_time` int(11) NOT NULL,
  `reason` varchar(20) NOT NULL COMMENT 'DROPPED(큐 포화) 또는 FINAL_FAILURE(재시도 소진)',
  `retry_count` int(11) NOT NULL DEFAULT 0,
  `processed` bit(1) NOT NULL DEFAULT b'0',
  `reg_date` timestamp NOT NULL DEFAULT current_timestamp(),
  `processed_date` timestamp NULL DEFAULT NULL,
  PRIMARY KEY (`outbox_id`),
  KEY `ix_opr_status_outbox_pending` (`processed`,`outbox_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE `tb_time` (
  `timezone_id` bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'Timezone 아이디',
  `timezone_name` varchar(100) NOT NULL COMMENT '타임존 명칭',
  `timezone_desc` varchar(200) DEFAULT NULL COMMENT '설명',
  `timezone_fr1` varchar(10) DEFAULT NULL COMMENT '타임존1 시작(HH:mm)',
  `timezone_to1` varchar(10) DEFAULT NULL COMMENT '타임존1 종료(HH:mm)',
  `timezone_day1` varchar(100) DEFAULT NULL COMMENT '타임존1 적용 요일(콤마구분)',
  `timezone_fr2` varchar(10) DEFAULT NULL,
  `timezone_to2` varchar(10) DEFAULT NULL,
  `timezone_day2` varchar(100) DEFAULT NULL,
  `timezone_fr3` varchar(10) DEFAULT NULL,
  `timezone_to3` varchar(10) DEFAULT NULL,
  `timezone_day3` varchar(100) DEFAULT NULL,
  `timezone_fr4` varchar(10) DEFAULT NULL,
  `timezone_to4` varchar(10) DEFAULT NULL,
  `timezone_day4` varchar(100) DEFAULT NULL,
  `timezone_hex_data` tinytext NOT NULL COMMENT 'Timezone Data',
  `use_yn` char(1) NOT NULL DEFAULT 'Y',
  `reg_user` varchar(20) DEFAULT NULL,
  `reg_date` datetime(6) NOT NULL,
  PRIMARY KEY (`timezone_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='타임존/스케줄(계획서 Phase 5)';
CREATE TABLE `tb_users` (
  `user_id` varchar(20) NOT NULL,
  `passwd` varchar(255) NOT NULL,
  `user_nm` varchar(100) NOT NULL,
  `e_mail` varchar(100) DEFAULT NULL,
  `phone` varchar(50) DEFAULT NULL,
  `use_yn` char(1) NOT NULL DEFAULT 'Y',
  `auth_view` char(1) NOT NULL DEFAULT 'Y',
  `auth_ctrl` char(1) NOT NULL DEFAULT 'N',
  `auth_admin` char(1) NOT NULL DEFAULT 'N',
  `auth_loc` varchar(50) NOT NULL DEFAULT 'ALL',
  `auth_grp` varchar(50) NOT NULL DEFAULT 'ALL',
  `local_area` varchar(5) NOT NULL DEFAULT 'ko-KR',
  `reg_date` datetime NOT NULL DEFAULT current_timestamp(),
  `mod_date` datetime NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='시스템 사용자';

-- 레거시 저장 프로시저(usp_net_check_data): GateControl 등 레거시 호출자가 여전히 쓰는
-- 시그니처를 유지한 채, tb_net_state 쓰기를 신규 서버(GateConnectionRegistryImpl)와 동일한
-- tb_net_state_seq 기반 조건부 UPSERT로 통일한다(작업일지 0107, V38 참고) — 두 쓰기 경로가
-- 같은 전역 시퀀스를 공유해야 어느 쪽이 나중에 실행됐든 실제로 더 최신인 쪽이 항상 이긴다.
-- DEFINER 절은 의도적으로 생략한다 — 이 문장을 실행하는 계정을 그대로 쓰도록 해, 마이그레이션
-- 실행 계정에 임의 DEFINER를 지정할 SUPER/SET USER 권한이 없는 환경에서도 동작한다.
DROP PROCEDURE IF EXISTS `usp_net_check_data`;
DELIMITER $$
CREATE PROCEDURE `usp_net_check_data`(
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

        
        SET vSeq = NEXT VALUE FOR tb_net_state_seq;

        INSERT INTO tb_net_state
            (dtl_ip, dtl_no, dtl_lane_no, dtl_state, dtl_id, loc_id, grp_id,
             snd_raw, check_time, server_ip, applied_seq, reg_date, mod_date)
        VALUES
            (vDtlIP, vDtlNo, vLaneNo, vState, vDtlId, vLocId, vGrpId,
             vRcvData, vCheckTime, vSvrIP, vSeq, NOW(), NOW())
        ON DUPLICATE KEY UPDATE

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


-- ── 시드 데이터: 게이트 타입 코드 (계획서 4.3절 "게이트 타입 — 확장 가능한 코드값") ─
INSERT INTO tb_code (code_grp, code_cd, code_nm, code_val, code_desc, disp_order, use_yn) VALUES
    ('GATE_TYPE', '1', 'Speed Gate', 'SR-1400', 'Speed/Flap Gate 공유 프로토콜', 1, 1),
    ('GATE_TYPE', '2', 'Flap Gate', 'FLAP', 'Speed/Flap Gate 공유 프로토콜', 2, 1),
    ('GATE_TYPE', '3', 'Turn Gate', 'TURN', '별도 프로토콜 — 1차 스캐폴드 미구현(계획서 3.4절)', 3, 1),
    ('GATE_TYPE', '4', 'Fast Gate', 'FAST', '별도 프로토콜 — 1차 스캐폴드 미구현(계획서 3.4절)', 4, 1);

SET FOREIGN_KEY_CHECKS = 1;
