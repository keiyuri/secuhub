package kr.co.securance.secuhub.domain.migration

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `V1__init_schema.sql` 시드 데이터 회귀 테스트(2026-09-03 Codex 적대적 리뷰 지적 — [P1]).
 *
 * `V1`~`V32`(32개 마이그레이션)를 단일 `V1__init_schema.sql`로 스쿼시하는 과정에서, 검증에 쓴
 * `mysqldump --no-data`가 정의상 DDL(CREATE TABLE 등)만 캡처해 원래 V1이 갖고 있던 `tb_code`
 * GATE_TYPE 시드 4행(INSERT)이 통째로 누락됐었다 — 신규 설치에서 게이트 타입 조회/선택 기능이
 * 빈 마스터 데이터로 시작하는 배포 차단급 결함. 실제 MariaDB로 재검증해 복구했다.
 *
 * 이 테스트는 실제 DB 없이(도메인 모듈 테스트는 `spring.flyway.enabled=false` + H2로 돌아
 * SQL 마이그레이션 파일 자체를 거치지 않는다 — [DataReceiveRepositoryTest] 등 참고) 마이그레이션
 * 파일 텍스트에 이 INSERT가 실제로 존재하는지만 확인한다. 완전한 보장은 아니지만, 이 INSERT
 * 블록이 다시 통째로 삭제되는 것(예: 향후 재스쿼시 시 동일한 실수 반복)은 확실히 잡아낸다.
 */
class FlywaySeedDataTest {

    private val v1Sql: String by lazy {
        val resource = requireNotNull(
            javaClass.classLoader.getResourceAsStream("db/migration/V1__init_schema.sql"),
        ) { "V1__init_schema.sql을 클래스패스에서 찾을 수 없습니다." }
        resource.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    @Test
    fun `V1은 tb_code에 GATE_TYPE 1~4 시드 데이터를 INSERT한다`() {
        assertTrue(
            v1Sql.contains("INSERT INTO tb_code"),
            "V1__init_schema.sql에 tb_code 시드 데이터 INSERT가 없습니다 — 신규 설치의 게이트 타입 마스터 데이터가 비게 됩니다.",
        )
        listOf(
            "('GATE_TYPE', '1', 'Speed Gate'",
            "('GATE_TYPE', '2', 'Flap Gate'",
            "('GATE_TYPE', '3', 'Turn Gate'",
            "('GATE_TYPE', '4', 'Fast Gate'",
        ).forEach { expected ->
            assertTrue(v1Sql.contains(expected), "V1__init_schema.sql에 '$expected' 시드 행이 없습니다.")
        }
    }
}
