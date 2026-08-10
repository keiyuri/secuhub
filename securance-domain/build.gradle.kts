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

    // Spring Data JPA가 @Embeddable data class(복합키)를 리플렉션으로 다루려면 kotlin-reflect가
    // 런타임에 있어야 한다. 없으면 NoClassDefFoundError(kotlin/reflect/full/KClasses)로 컨텍스트
    // 기동이 실패한다(실 DB 연동 검증 중 발견) — api로 노출해 모든 소비 모듈 런타임에 전달한다.
    api(kotlin("reflect"))

    // consumer 모듈(securance-server 등)이 JpaRepository/엔티티 타입을 직접 참조하므로 api로 노출한다.
    api("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.flywaydb:flyway-core")
    implementation(libs.flyway.mysql)
    // Spring Boot 4는 autoconfigure를 기능별 모듈로 분리했다 — spring-boot-starter-data-jpa가
    // spring-boot-hibernate/spring-boot-data-jpa는 끌어오지만 FlywayAutoConfiguration이 들어있는
    // spring-boot-flyway는 별도 의존성이라 명시하지 않으면 Flyway가 조용히 스킵되고 Hibernate가
    // ddl-auto: validate를 빈 스키마에 대고 실행해 기동이 실패한다(실 DB 연동 검증 중 발견).
    implementation("org.springframework.boot:spring-boot-flyway")
    runtimeOnly(libs.mariadb.java.client)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
