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
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("securance-app.jar")
}
