package kr.co.securance.secuhub.domain.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Flyway 전용 커넥션에 socketTimeout/connectTimeout을 강제로 재정의한다 — `spring.datasource`가
 * 쓰는 Hikari 커넥션의 소켓 타임아웃(5초, securance-app/application.yml 참고)은 GateDbWriteQueue
 * 워커의 블로킹 쿼리를 강제로 풀어주기 위한 값이라 너무 짧다. ADD INDEX/ADD COLUMN 같은 대용량
 * DDL은 테이블이 커지면 5초를 쉽게 넘겨 마이그레이션 도중 소켓이 끊기고 앱 기동 자체가 실패한다
 * (2026-08-12 V2 마이그레이션 실패 로그로 확인). Flyway 전용 커넥션은 같은 DB를 보되 소켓
 * 타임아웃을 훨씬 넉넉하게(600초) 재정의한다 — 대용량 테이블 재구성(rebuild) 여유를 둔 값이다
 * (V8이 tb_data_rcv_anal 65만+ 행에 컬럼 19개를 ADD하다 205초만에 끊긴 사례가 있다).
 *
 * **이 클래스가 securance-domain에 있는 이유**: `spring-boot-flyway`를 `implementation`으로 선언한
 * 모듈이 securance-domain뿐이라(build.gradle.kts 참고), `implementation` 의존성은 소비 모듈
 * (securance-app)의 컴파일 클래스패스로 전이되지 않는다 — securance-app에 두면
 * `FlywayConfigurationCustomizer`를 컴파일 시점에 찾지 못한다.
 *
 * **이전에는** application*.yml에서 `spring.flyway.url: ${SECURANCE_DB_URL}?socketTimeout=...`처럼
 * 문자열을 YAML에서 정적으로 이어붙였다. `SECURANCE_DB_URL` 자체에 이미 `?useSSL=...`,
 * `serverTimezone=...` 같은 쿼리 파라미터가 포함된 운영 환경에서는 물음표가 중복되어(`...?useSSL=...
 * ?socketTimeout=...`) 잘못된 JDBC URL이 만들어지고, Flyway가 기동 시점에 연결 실패하거나 뒤쪽
 * 파라미터가 통째로 오해석된다(2026-08-14 Codex 리뷰 지적). `spring.datasource.url`을 코드에서
 * 그대로 읽어 쿼리 문자열 유무에 따라 구분자(`?` 또는 `&`)를 골라 안전하게 이어붙인다.
 */
@Configuration
class FlywayDataSourceConfig {

    @Bean
    fun flywayTimeoutCustomizer(
        @Value("\${spring.datasource.url}") datasourceUrl: String,
        @Value("\${spring.datasource.username}") username: String,
        @Value("\${spring.datasource.password}") password: String,
    ): FlywayConfigurationCustomizer =
        FlywayConfigurationCustomizer { config -> config.dataSource(withTimeoutParams(datasourceUrl), username, password) }

    companion object {
        /**
         * `datasourceUrl`에 이미 쿼리 문자열이 있는지에 따라 구분자(`?`/`&`)를 골라 소켓/커넥트
         * 타임아웃 파라미터를 이어붙인다. 단위 테스트(FlywayDataSourceConfigTest)에서 직접
         * 검증할 수 있도록 빈 람다 밖으로 뺐다.
         */
        fun withTimeoutParams(datasourceUrl: String): String {
            val separator = if (datasourceUrl.contains('?')) '&' else '?'
            return "$datasourceUrl${separator}socketTimeout=600000&connectTimeout=3000"
        }
    }
}
