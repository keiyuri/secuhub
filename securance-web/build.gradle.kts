// securance-web: Spring MVC + Thymeleaf 대시보드(계획서 5절).
// adminlte-react 분석 결과(순정 AdminLTE4/Bootstrap5.3 마크업)를 그대로 Thymeleaf 프래그먼트로
// 이식해, 추후 React/Next.js 전환 시 시각적 변경 없이 컴포넌트만 스왑 가능하게 유지한다.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.spring.boot.get()}"))
    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.spring.boot.get()}"))

    implementation(project(":securance-common"))
    implementation(project(":securance-domain"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.thymeleaf.extras:thymeleaf-extras-springsecurity6")
    implementation(libs.thymeleaf.layout.dialect)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
}
