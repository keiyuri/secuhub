// securance-protocol: SpeedGate 바이너리 프로토콜 코덱.
// Netty/Spring에 의존하지 않는 순수 Kotlin — securance-server(TCP)뿐 아니라
// 배치 도구/CLI 등 어떤 런타임에서도 재사용·단위테스트 가능하게 유지한다(계획서 3.4절).
plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":securance-common"))
}
