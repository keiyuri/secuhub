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
    // Phase 4(#12 GateModeChange, #13 GateSetupMotor) — 제어 명령 패킷 빌더(GateControlCommandBuilder)
    // 재사용을 위해 securance-protocol에 의존한다.
    implementation(project(":securance-protocol"))
    // 게이트 제어/리셋 화면이 GateControlService(제어 명령 발행)를 호출하기 위해 필요하다(계획서 5.5절).
    implementation(project(":securance-server"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-security")
    // Phase 3 실시간 대시보드(계획서 4절 결정: WebSocket + 폴링 하이브리드) — 순정 WebSocket API만
    // 쓰고 STOMP/SockJS는 도입하지 않는다(별도 클라이언트 JS 벤더링 없이 브라우저 내장 WebSocket으로
    // 충분하기 때문 — vendor/ 디렉터리에 외부 JS 라이브러리를 추가로 받아오지 않아도 됨).
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    // spring-boot-starter-web이 Jackson 3(tools.jackson)를 런타임에 끌고 오지만, DashboardPushService가
    // ObjectMapper(Jackson 3 타입 — Spring Boot 4.1의 기본 JacksonAutoConfiguration이 등록하는 빈이
    // 바로 이 타입이다)를 직접 컴파일 타임에 참조하므로 명시적으로 선언한다.
    implementation("tools.jackson.core:jackson-databind")
    // #4/#11 화면의 폼 검증(jakarta.validation.constraints.*) — 이전까지는 화면에 폼이 없어 불필요했다.
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // #8/#9 조회 화면의 엑셀 내보내기 공용 컴포넌트(ExcelExportService, 계획서 5절) — 레거시
    // `SR_C_Excel.DtToExcel` 대응.
    implementation(libs.poi.ooxml)
    implementation("org.thymeleaf.extras:thymeleaf-extras-springsecurity6")
    implementation(libs.thymeleaf.layout.dialect)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-webmvc-test")
    testImplementation("org.springframework.security:spring-security-test")
}
