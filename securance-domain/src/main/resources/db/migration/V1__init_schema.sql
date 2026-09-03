-- ============================================================================
-- secuhub(securance) V1 초기 스키마 (2026-09-03 스쿼시 — 코드 리뷰 지적)
--
-- 기존 V1~V32(32개 마이그레이션)를 하나로 병합했다. 이 저장소는 아직 실제 운영 MariaDB에
-- 한 번도 적용된 적이 없다(README "검증한 것/검증하지 않은 것" 참고 — 사용자 확인 완료,
-- 2026-09-03) — 이미 적용된 환경이 있다면 이런 병합은 절대 해서는 안 된다(Flyway 체크섬이
-- 파일 내용을 검증하므로 기존 배포가 깨진다).
--
-- 병합 방법: 32개 마이그레이션 파일을 실제 MariaDB 10.11 컨테이너에 처음부터 순서대로 전부
-- 적용한 뒤 `mysqldump --no-data`로 최종 스키마를 그대로 떠서 이 파일의 CREATE TABLE 구문으로
-- 삼았다(레거시 컬럼 코멘트/타입까지 실 DB 검증을 거쳤으므로 수작업 병합보다 안전하다). 이
-- 과정에서 기존 마이그레이션 체인 자체의 버그 2건을 발견했다(둘 다 이 병합으로 해소됨 — "신선한"
-- 배포에서만 드러나고 기존 운영 DB에는 원래 문제없던 경로였다):
--   1) V23이 anal_data_* 14개 컬럼에 MODIFY COLUMN을 실행하는데, 그 컬럼들은 V26에서야
--      ADD COLUMN IF NOT EXISTS로 생성된다 — 신선한 DB에 V1부터 재생하면 V23에서
--      "Unknown column 'anal_data_stx'"로 실패한다(운영 DB는 Flyway 이전부터 레거시
--      저장 프로시저가 이미 그 컬럼들을 갖고 있어 문제가 드러나지 않았을 뿐).
--   2) V26이 ALGORITHM=INPLACE를 강제하는데, tb_data_rcv_anal에는 V1의 VIRTUAL 생성 컬럼
--      (has_status_event/has_error_event)이 있어 "다른 ALTER 동작과 결합된 가상 컬럼
--      추가/삭제는 INPLACE를 지원하지 않는다"로 실패한다.
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
  KEY `idx_data_rcv_mod_date` (`mod_date`)
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
  `anal_data_stx` varchar(10) NOT NULL DEFAULT '',
  `anal_data_packet_len` varchar(10) NOT NULL DEFAULT '',
  `anal_data_protocol_ver` varchar(10) NOT NULL DEFAULT '',
  `anal_data_frame_option` varchar(10) NOT NULL DEFAULT '',
  `anal_data_address` varchar(40) NOT NULL DEFAULT '',
  `anal_data_command` varchar(10) NOT NULL DEFAULT '',
  `anal_data_subcommand` varchar(10) NOT NULL DEFAULT '',
  `anal_data_object_code` varchar(10) NOT NULL DEFAULT '',
  `anal_data_info_length` varchar(10) NOT NULL DEFAULT '',
  `anal_data_count` varchar(10) NOT NULL DEFAULT '',
  `anal_data_length` varchar(10) NOT NULL DEFAULT '',
  `anal_data_gate_name` varchar(80) NOT NULL DEFAULT '',
  `anal_data_ip` varchar(20) NOT NULL DEFAULT '',
  `anal_data_mac` varchar(20) NOT NULL DEFAULT '',
  `anal_data_gate_lane_number` char(2) NOT NULL DEFAULT '',
  `anal_data_gate_lane_count` char(2) NOT NULL DEFAULT '',
  `anal_data_gate_type` char(2) NOT NULL DEFAULT '',
  `anal_data_user_mode` char(2) NOT NULL DEFAULT '',
  `anal_data_security_mode` char(2) NOT NULL DEFAULT '',
  `anal_data_inout_time` char(2) NOT NULL DEFAULT '',
  `anal_data_user_count` char(2) NOT NULL DEFAULT '',
  `anal_data_total_count` char(8) NOT NULL DEFAULT '',
  `anal_data_operation_sensor_status1` char(8) NOT NULL DEFAULT '',
  `anal_data_safety_sensor_status` char(8) NOT NULL DEFAULT '',
  `anal_data_operation_sensor_status2` char(8) NOT NULL DEFAULT '',
  `anal_data_optical_sensor_status` varchar(50) NOT NULL DEFAULT '',
  `anal_data_output_status` varchar(40) NOT NULL DEFAULT '',
  `anal_data_motor_operation_count` varchar(20) NOT NULL DEFAULT '',
  `anal_data_master_in_total_count` varchar(20) NOT NULL DEFAULT '',
  `anal_data_gate_operation_status` varchar(50) NOT NULL DEFAULT '',
  `anal_data_check_sum` char(4) NOT NULL DEFAULT '',
  `anal_data_packet_checksum` char(2) NOT NULL DEFAULT '',
  `anal_data_etx` char(2) NOT NULL DEFAULT '',
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
  `desc_security_mode` varchar(20) NOT NULL DEFAULT '',
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
  KEY `IDX_ANAL_LANE_LATEST` (`dtl_ip`,`dtl_lane_no`,`anal_id`)
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
  `dtl_type` int(11) NOT NULL,
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
  PRIMARY KEY (`dtl_ip`,`dtl_lane_no`,`loc_id`,`grp_id`),
  KEY `idx_net_state_loc_grp_state` (`loc_id`,`grp_id`,`dtl_state`)
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
  KEY `idx_opr_status_dtl_ip_lane_date` (`dtl_ip`,`dtl_lane_no`,`opr_date`,`opr_seq`)
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

-- ── 시드 데이터: 게이트 타입 코드 (계획서 4.3절 "게이트 타입 — 확장 가능한 코드값") ─
-- [P1] 스쿼시 검증용 mysqldump --no-data는 정의상 DDL만 캡처하므로, 원래 V1이 갖고 있던 이
-- INSERT를 놓쳤었다(2026-09-03 Codex 적대적 리뷰 재지적 — 32개 마이그레이션 전체를 확인한 결과
-- 시드/참조 데이터 INSERT는 원래 V1의 이 4행이 유일했고, V18/V21의 UPDATE는 전부 기존 운영
-- 데이터를 새 컬럼 제약에 맞추는 백필이라 빈 DB에는 애초에 적용 대상이 없다). 새로 추가한
-- 이 INSERT 블록만 별도로 재검증(빈 MariaDB에 V1 적용 후 4행 존재 확인)했다.
INSERT INTO tb_code (code_grp, code_cd, code_nm, code_val, code_desc, disp_order, use_yn) VALUES
    ('GATE_TYPE', '1', 'Speed Gate', 'SR-1400', 'Speed/Flap Gate 공유 프로토콜', 1, 1),
    ('GATE_TYPE', '2', 'Flap Gate', 'FLAP', 'Speed/Flap Gate 공유 프로토콜', 2, 1),
    ('GATE_TYPE', '3', 'Turn Gate', 'TURN', '별도 프로토콜 — 1차 스캐폴드 미구현(계획서 3.4절)', 3, 1),
    ('GATE_TYPE', '4', 'Fast Gate', 'FAST', '별도 프로토콜 — 1차 스캐폴드 미구현(계획서 3.4절)', 4, 1);

SET FOREIGN_KEY_CHECKS = 1;
