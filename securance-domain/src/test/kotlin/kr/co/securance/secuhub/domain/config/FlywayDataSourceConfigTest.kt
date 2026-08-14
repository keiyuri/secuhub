package kr.co.securance.secuhub.domain.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * 회귀 방지 테스트(2026-08-14 Codex 리뷰 지적) — SECURANCE_DB_URL에 이미 쿼리 파라미터가 있는
 * 운영 환경에서 물음표가 중복되어 잘못된 JDBC URL이 만들어지던 문제.
 */
class FlywayDataSourceConfigTest {

    @Test
    fun `쿼리 문자열이 없는 URL에는 물음표로 시작해 이어붙인다`() {
        val result = FlywayDataSourceConfig.withTimeoutParams("jdbc:mariadb://192.168.0.91:28030/securance_gate")

        assertEquals(
            "jdbc:mariadb://192.168.0.91:28030/securance_gate?socketTimeout=600000&connectTimeout=3000",
            result,
        )
    }

    @Test
    fun `이미 쿼리 문자열이 있는 URL에는 앰퍼샌드로 이어붙인다`() {
        // 예전에는 이 케이스에서도 물음표를 또 붙여 "...useSSL=true?socketTimeout=..."처럼
        // 잘못된 JDBC URL을 만들었다.
        val result = FlywayDataSourceConfig.withTimeoutParams(
            "jdbc:mariadb://db.example.com:3306/securance_gate?useSSL=true&serverTimezone=UTC",
        )

        assertEquals(
            "jdbc:mariadb://db.example.com:3306/securance_gate?useSSL=true&serverTimezone=UTC" +
                "&socketTimeout=600000&connectTimeout=3000",
            result,
        )
    }
}
