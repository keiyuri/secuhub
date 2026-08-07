// securance-app: server+scheduler+web+domain을 조립해 기동하는 유일한 실행 모듈(계획서 1/7절).
// 다른 모듈과 동일하게 io.spring.dependency-management 대신 Gradle 표준 platform() BOM import를
// 사용한다(securance-domain build.gradle.kts 주석 참고 — 이 환경의 Kotlin 컴파일러 플러그인 충돌 회피).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.spring.boot.get()}"))
    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.spring.boot.get()}"))

    implementation(project(":securance-common"))
    implementation(project(":securance-protocol"))
    implementation(project(":securance-domain"))
    implementation(project(":securance-server"))
    implementation(project(":securance-scheduler"))
    implementation(project(":securance-web"))

    // RabbitMQ/Redis는 항상 클래스패스에 두고 @ConditionalOnProperty로 활성화 여부만 제어한다
    // (계획서 6절 — 재배포 없이 설정 변경만으로 on/off 전환).
    implementation("org.springframework.boot:spring-boot-starter-amqp")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // 컨텍스트 로드 스모크 테스트(Opus 리뷰 지적 — securance-app에 테스트가 전무했음)용 임베디드 DB.
    // 운영 DB(MariaDB)를 대신해 H2로 전체 빈 조립을 검증한다(Flyway는 MariaDB 전용 문법을 쓰므로
    // 비활성화하고 Hibernate ddl-auto=create-drop으로 엔티티에서 스키마를 생성한다).
    testRuntimeOnly("com.h2database:h2")
    testRuntimeOnly("org.jetbrains.kotlin:kotlin-reflect:${libs.versions.kotlin.get()}")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("securance-app.jar")
}
