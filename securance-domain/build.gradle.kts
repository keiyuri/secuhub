// securance-domain: JPA 엔티티/리포지토리 + Flyway 마이그레이션.
// 실행 가능한 Spring Boot 앱이 아니라 라이브러리 모듈이므로 org.springframework.boot 플러그인은
// 적용하지 않는다. 버전 정렬은 io.spring.dependency-management 플러그인 대신 Gradle 표준
// platform()(BOM) import로 처리한다 — 이 개발 환경에서 io.spring.dependency-management 플러그인을
// Kotlin 컴파일러 플러그인(kotlin-jpa/kotlin-spring)과 함께 쓰면 Kotlin 컴파일러가 스크립팅 확장을
// 초기화하다 내부 클래스를 찾지 못해 죽는 환경 특이적 버그가 있어(별도 재현 확인 완료), 이를 피한다.
plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.jpa)
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.spring.boot.get()}"))
    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.spring.boot.get()}"))

    api(project(":securance-common"))

    // consumer 모듈(securance-server 등)이 JpaRepository/엔티티 타입을 직접 참조하므로 api로 노출한다.
    api("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.flywaydb:flyway-core")
    implementation(libs.flyway.mysql)
    runtimeOnly(libs.mariadb.java.client)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Spring Boot 4.x부터 @DataJpaTest 등 JPA 테스트 슬라이스가 spring-boot-test-autoconfigure에서
    // 분리되어 이 전용 스타터로 옮겨졌다(spring-boot-starter-test만으로는 @DataJpaTest를 찾을 수 없음).
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    // @DataJpaTest로 JPQL(특히 @Modifying 벌크 업데이트) 쿼리를 실제 Hibernate/JPA 위에서
    // 검증하기 위한 임베디드 DB. 운영 DB(MariaDB)와 문법이 100% 동일하지는 않지만, 표준 JPQL
    // 쿼리가 실제로 파싱/실행되는지(오탈자·문법 오류)를 컴파일 타임에 잡히지 않는 리스크로부터
    // 검증하는 목적으로는 충분하다.
    testRuntimeOnly("com.h2database:h2")
    // Spring Data JPA가 Kotlin 리포지토리 인터페이스를 프록시할 때 kotlin-reflect(KClasses)를
    // 런타임에 필요로 한다 — 운영 앱(securance-app)은 이를 이미 갖고 있지만 이 모듈은 라이브러리라
    // 없었고, @DataJpaTest로 실제 스프링 컨텍스트를 띄우면서 처음 드러났다.
    testRuntimeOnly("org.jetbrains.kotlin:kotlin-reflect:${libs.versions.kotlin.get()}")
}
