# Flyway 마이그레이션 복구 절차 — V18 (`tb_time.reg_date` 타입 정합화)

## 배경

`V18__fix_tb_time_reg_date_type.sql`은 `ADD COLUMN` → `UPDATE` → `DROP COLUMN + CHANGE COLUMN`을
가드 없이 순차 실행한다. MariaDB DDL은 트랜잭션 롤백이 보장되지 않으므로, 연결 끊김 등으로
`ADD COLUMN reg_date_new` 이후 `UPDATE`나 마지막 `ALTER TABLE` 단계에서 중단되면 Flyway는 V18을
`schema_history`에 **실패(success=0)** 로 기록하고, 이후 모든 마이그레이션(V19 이상)과 애플리케이션
기동을 막는다.

이미 배포된 환경에서 V18 파일 내용을 고쳐 재실행 안전성을 넣는 것은 금지된다 — Flyway는
`schema_history`에 저장된 체크섬과 파일의 현재 체크섬을 비교하므로, 이미 성공 적용된 환경에서는
파일을 한 글자만 바꿔도 시작 시 checksum mismatch로 막힌다(2026-08-13 코드 리뷰 지적). 그렇다고
V18 뒤에 오는 새 버전(V19+)으로 "복구 마이그레이션"을 추가해도 소용없다 — Flyway는 **실패한
마이그레이션을 해결하기 전에는 그 뒤의 어떤 버전도 실행하지 않으므로**, 복구 로직 자체가
도달 불가능하다(2026-08-13 adversarial review 지적, 최초 시도했던 V23 삭제).

## 증상

애플리케이션 기동 시 다음과 유사한 오류로 실패한다:

```
FlywayException: Validate failed:
Migration checksum mismatch for migration version 18
-- 또는 --
Detected failed migration to version 18 (fix tb time reg date type)
```

`tb_time` 테이블에 `reg_date`(VARCHAR)와 `reg_date_new`(DATETIME) 컬럼이 함께 남아있다.

## 복구 절차 (운영자가 수동 실행)

1. **현재 상태 확인**

   ```sql
   SELECT column_name, data_type FROM information_schema.columns
   WHERE table_schema = DATABASE() AND table_name = 'tb_time';
   ```

   `reg_date`(varchar)와 `reg_date_new`(datetime)가 함께 있으면 부분 실패 상태다.

2. **중단된 지점부터 수동으로 완료**

   ```sql
   UPDATE tb_time
   SET reg_date_new = STR_TO_DATE(reg_date, '%Y%m%d%H%i%s')
   WHERE reg_date_new IS NULL;

   ALTER TABLE tb_time
       DROP COLUMN reg_date,
       CHANGE COLUMN reg_date_new reg_date DATETIME(6) NOT NULL;
   ```

   (`ADD COLUMN` 단계까지만 실패했다면 `reg_date_new`가 이미 있으므로 위 두 단계만 실행하면
   V18이 원래 의도한 최종 상태와 동일해진다.)

3. **Flyway 이력 정리**

   ```bash
   ./gradlew flywayRepair
   ```

   (또는 `flyway repair` CLI) — `schema_history`에서 V18의 실패 행을 정리하고 파일 체크섬을
   현재 상태로 다시 기록한다. 1~2단계로 스키마가 이미 V18의 최종 상태와 일치하므로, repair 후
   재기동하면 Flyway가 V18을 "이미 적용됨"으로 인식하고 V19부터 정상 진행한다.

## 재발 방지

새로 작성하는 다단계 DDL 마이그레이션은 반드시 **배포 전** 단계에서 V3(`V3__add_timezone.sql`)
방식의 idempotent 가드(`information_schema` 조건 확인 후 `DELIMITER`/프로시저로 감싸기)를
적용한 상태로 최초 커밋해야 한다 — 한 번 배포된 뒤에는 파일을 고칠 수도, 뒤에 복구
마이그레이션을 붙일 수도 없기 때문이다.
