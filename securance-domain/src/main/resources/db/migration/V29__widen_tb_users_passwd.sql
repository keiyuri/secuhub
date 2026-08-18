-- ============================================================================
-- V29 — tb_users.passwd를 레거시 VARCHAR(50)에서 BCrypt 해시를 담을 수 있는 VARCHAR(255)로 확장
-- (2026-08-18, dev DB(레거시 GateControl 스키마 복사본) 대상 재현)
--
-- [경위] V1__init_schema.sql은 `tb_users.passwd`를 처음부터 VARCHAR(255)로 정의하지만,
-- `baseline-on-migrate`로 V1이 베이스라인 처리되는 이 DB 계열(레거시 GateControl 원본이 이미
-- 갖고 있던 스키마)에는 V1의 CREATE TABLE이 실제로 실행된 적이 없다 — 레거시 원본 폭인
-- VARCHAR(50)이 그대로 남아 있다(재현: dev DB information_schema 조회).
--
-- [실제 위험] LegacyAwarePasswordEncoder(securance-web)는 tb_users.passwd에 레거시 평문
-- 비밀번호가 들어있는 계정도 로그인을 허용하고, 로그인 성공 시 즉시 BCrypt로 재해시해서
-- 저장한다(UserDetailsPasswordService 업그레이드 경로). BCrypt 인코딩 결과는 항상 60자
-- (`$2a$10$` 7자 + salt/hash 53자)인데 컬럼이 VARCHAR(50)이면 이 UPDATE에서 뒤 10자가
-- 잘려 해시가 손상된다 — 그 계정은 다음 로그인부터 영구히 실패한다(재현하기 전에 미리
-- 발견해 데이터 손상 없이 선제 조치).
--
-- [조치] 값 손실 없는 단순 확장이라 ALGORITHM/LOCK 신경 쓸 필요가 낮지만, V23에서 확인된
-- MariaDB 11.8의 ALGORITHM=INPLACE 제약을 고려해 여기서도 절을 생략해 자동 선택에 맡긴다.
-- ============================================================================

ALTER TABLE tb_users
    MODIFY COLUMN passwd VARCHAR(255) NOT NULL;
