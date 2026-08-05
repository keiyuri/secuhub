// securance-scheduler: Quartz 잡 전용 모듈(계획서 3.6절).
// securance-server가 노출하는 GateConnectionRegistry 인터페이스에만 의존하고,
// GateConnectionActor/Netty 내부 구현은 직접 참조하지 않는다 — 두 모듈 간 경계를 명확히 유지한다.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.spring.boot.get()}"))
    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.spring.boot.get()}"))

    implementation(project(":securance-common"))
    implementation(project(":securance-server"))
    implementation(project(":securance-domain"))

    implementation("org.springframework.boot:spring-boot-starter-quartz")
    implementation(libs.kotlinx.coroutines.core)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
