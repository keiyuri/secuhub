package kr.co.securance.secuhub

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

/**
 * `securance-web`은 실행 가능한 Spring Boot 앱이 아니라 라이브러리 모듈이라 `@SpringBootApplication`
 * 클래스가 없다. `@DataJpaTest`는 테스트 클래스 패키지에서 위로 올라가며 `@SpringBootConfiguration`을
 * 찾으므로, 테스트 전용으로 이 최소 애플리케이션 클래스를 `kr.co.securance.secuhub`(공통 루트) 패키지에
 * 둔다 — 운영 코드에는 영향을 주지 않는다(src/test에만 존재). `securance-domain`의 `TestJpaApplication`과
 * 동일한 이유이되, 엔티티/리포지토리(`kr.co.securance.secuhub.domain`)가 이 모듈 밖에 있어
 * `@EntityScan`/`@EnableJpaRepositories`로 명시해야 한다.
 */
@SpringBootApplication
@EntityScan(basePackageClasses = [kr.co.securance.secuhub.domain.entity.DataReceiveAnalysis::class])
@EnableJpaRepositories(basePackageClasses = [kr.co.securance.secuhub.domain.repository.DataReceiveAnalysisRepository::class])
class TestJpaApplication
