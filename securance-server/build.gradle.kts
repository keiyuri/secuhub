// securance-server: 게이트 TCP 연동 전용 모듈(Quartz 의존 없음, 계획서 3절).
// Reactor Netty(WebFlux)로 SERVER/CLIENT 모드 TCP 연결을 관리하고, 커넥션당 코루틴 액터로
// 직렬 처리한다. Quartz 잡은 별도 모듈(securance-scheduler)에서 이 모듈이 노출하는
// GateConnectionRegistry 인터페이스만 보고 동작한다.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.spring.boot.get()}"))
    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.spring.boot.get()}"))

    implementation(project(":securance-common"))
    implementation(project(":securance-protocol"))
    implementation(project(":securance-domain"))

    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.reactor)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(libs.kotlinx.coroutines.test)
}
