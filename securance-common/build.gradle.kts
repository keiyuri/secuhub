// securance-common: Spring/Netty 등에 의존하지 않는 순수 Kotlin 모듈.
// 어떤 런타임(서버/스케줄러/웹/향후 CLI 도구)에서도 재사용 가능한 최소 공용 유틸/예외만 둔다.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    // 의도적으로 비어 있음 — 외부 의존성을 최소화해 어디서든 재사용 가능하게 유지한다.
}
