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
    // GateConnectionState.codec의 타입(GateProtocolCodec)을 컴파일 타임에 인식하려면 필요하다
    // (securance-server가 GateConnectionState.codec의 타입으로 이 인터페이스를 노출한다).
    implementation(project(":securance-protocol"))

    implementation("org.springframework.boot:spring-boot-starter-quartz")
    implementation(libs.kotlinx.coroutines.core)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // ReqStatusJobTest에서 GateConnectionState를 직접 생성하려면 reactor.netty.Connection/
    // NettyOutbound 타입이 컴파일 클래스패스에 있어야 한다(securance-server는 이를 implementation
    // 의존성으로만 노출하므로 테스트 전용으로 별도 선언).
    testImplementation("org.springframework.boot:spring-boot-starter-webflux")
}
