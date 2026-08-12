package kr.co.securance.secuhub

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest

/**
 * 애플리케이션 컨텍스트 로드 스모크 테스트(Opus 리뷰 지적 — `securance-app`은 실제 조립 모듈인데도
 * 스프링 컨텍스트가 뜨는지 확인하는 테스트가 하나도 없었다).
 *
 * common → protocol → domain → server → scheduler → web 6개 모듈의 빈이 한 컨텍스트에 모두 올라올 때
 * 발생하는 조립 오류(빈 충돌, `@ConfigurationProperties` 바인딩 실패, 순환 참조 등)는 지금까지 실제
 * 기동 시에만 드러났다. 이 테스트는 그 실패를 CI에서 잡기 위한 최소 안전망이다.
 *
 * 운영 DB(MariaDB)와 Flyway 마이그레이션은 이 테스트의 목적(빈 조립 검증)에 필수가 아니고, V1
 * 마이그레이션이 MariaDB 전용 문법(VIRTUAL 컬럼 등)을 써서 H2로 그대로 재현하기도 어렵다. 대신
 * Flyway를 끄고 Hibernate `ddl-auto=create-drop`으로 엔티티 매핑에서 직접 스키마를 생성한다 —
 * "운영 스키마와 100% 동일한 DB 위에서 동작하는가"가 아니라 "빈 그래프가 정상 조립되는가"만 검증한다.
 */
@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:securance-app-context-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        // 실제 게이트 포트(28010)를 점유하지 않도록 임의 포트를 쓴다(GateTcpServer.boundPort 참고).
        "securance.server.port=0",
    ],
)
class SecuranceApplicationTests {

    @Test
    fun `애플리케이션 컨텍스트가 정상적으로 로드된다`() {
        // 컨텍스트 로드 자체가 검증 대상이므로 본문은 비워둔다 — 로드 실패 시 이 테스트가 예외로 실패한다.
    }
}
