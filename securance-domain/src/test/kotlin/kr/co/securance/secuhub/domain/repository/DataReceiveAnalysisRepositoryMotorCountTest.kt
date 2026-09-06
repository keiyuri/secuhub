package kr.co.securance.secuhub.domain.repository

import kr.co.securance.secuhub.domain.entity.DataReceiveAnalysis
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager
import org.springframework.test.context.TestPropertySource

/**
 * `desc_motor_count`/`desc_inout_time` unsigned 32비트 컬럼(최대 4,294,967,295)의 오버플로 회귀
 * 테스트(작업일지 0113 참고).
 *
 * 2026-09-07: Hibernate `ddl-auto=validate`가 `desc_motor_count`(실제 컬럼 `int(10) unsigned`)를
 * Kotlin `Int`(SQL INTEGER, signed) 기본 매핑과 폭이 안 맞는다고 막았을 때, 최초 수정안은
 * `@JdbcTypeCode(SqlTypes.INTEGER)`로 검증 타입 자체를 좁혔다 — 이는 검증은 통과시키지만 JDBC
 * 바인딩/추출까지 `getInt`/`setInt`(signed 32비트, 최대 약 21억)로 좁혀버려 실제 unsigned 상한
 * 근방 값에서 조용히 오버플로/음수화되는 새 결함이었다(`/codex:review` P1 지적). 최종 수정은
 * `@Column(columnDefinition = "INT UNSIGNED")`로 검증용 비교 문자열만 맞추고, 실제 바인딩은
 * 엔티티 필드 타입(`Long`, `getLong`/`setLong`)이 그대로 결정하게 했다.
 *
 * 이 테스트는 그 결정이 실제로 지켜지는지 — `Int.MAX_VALUE`(2,147,483,647)를 넘는 값도 잘림/
 * 부호 반전 없이 저장·조회되는지 — 리포지토리 왕복으로 직접 검증한다. 이 테스트가 쓰는 H2는
 * `INT UNSIGNED` 문법을 파싱하지 못해(schema.sql 주석 참고) 컬럼을 BIGINT로 넓혀뒀다 — 검증
 * 대상은 어디까지나 "엔티티/JDBC 바인딩이 21억을 넘는 값을 자르지 않는가"이지 컬럼 타입 자체가
 * 아니므로, 컬럼 폭을 넓히는 것으로 충분하다.
 */
@DataJpaTest
@TestPropertySource(
    properties = [
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
    ],
)
class DataReceiveAnalysisRepositoryMotorCountTest {

    @Autowired
    private lateinit var entityManager: TestEntityManager

    @Autowired
    private lateinit var repository: DataReceiveAnalysisRepository

    private fun sample(descMotorCount: Long) = DataReceiveAnalysis(
        analDate = "202609070000",
        analTp = "STA",
        dtlIp = "192.168.0.10",
        dtlLaneNo = 1,
        rcvDate = "202609070000",
        descMotorCount = descMotorCount,
    )

    @Test
    fun `Int 최댓값을 넘는 모터 카운트도 잘림 없이 저장·조회된다`() {
        // int(10) unsigned 상한(4,294,967,295)에 가까운, signed Int 범위(약 21억)를 넘는 값 —
        // getInt/setInt로 바인딩됐다면 여기서 잘리거나 음수로 반전됐을 값이다.
        val overflowValue = 4_294_967_295L
        val saved = repository.saveAndFlush(sample(overflowValue))
        entityManager.clear()

        val found = repository.findById(saved.analId!!).orElseThrow()

        assertEquals(overflowValue, found.descMotorCount)
    }
}
