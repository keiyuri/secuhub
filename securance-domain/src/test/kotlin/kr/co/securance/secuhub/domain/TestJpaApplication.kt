package kr.co.securance.secuhub.domain

import org.springframework.boot.autoconfigure.SpringBootApplication

/**
 * `securance-domain`은 실행 가능한 Spring Boot 앱이 아니라 라이브러리 모듈이라 `@SpringBootApplication`
 * 클래스가 없다. `@DataJpaTest`는 테스트 클래스 패키지에서 위로 올라가며 `@SpringBootConfiguration`을
 * 찾으므로, 테스트 전용으로 이 최소 애플리케이션 클래스를 모듈 루트 패키지에 둔다 — 운영 코드에는
 * 영향을 주지 않는다(src/test에만 존재).
 */
@SpringBootApplication
class TestJpaApplication
