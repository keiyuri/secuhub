package kr.co.securance.secuhub.web.common

import tools.jackson.databind.ObjectMapper
import kr.co.securance.secuhub.web.security.GateControlReauthInterceptor
import kr.co.securance.secuhub.web.security.GateControlReauthService
import kr.co.securance.secuhub.web.security.SecuritySettingsProperties
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.io.File

/**
 * #5 SetupLocation 배치도 이미지 업로드([GateLocationService.uploadMap])가 저장한 파일을
 * `/loc-images/` 이하 와일드카드 경로로 서빙한다. 레거시(`SR_C_Global.locImgPath`, 로컬 파일 경로 직접 참조)와
 * 달리 웹은 별도 리소스 핸들러로 노출해야 브라우저가 `<img>` 태그로 불러올 수 있다.
 */
// SecuritySettingsProperties 자체는 SecurityConfig가 @EnableConfigurationProperties로 활성화한다
// (SecurityConfig KDoc 참고) — 이 클래스는 그 빈을 주입받아 쓰기만 한다.
@Configuration
class WebConfig(
    @Value("\${securance.location.image-dir:./data/loc-images}") private val imageDir: String,
    private val securitySettingsProperties: SecuritySettingsProperties,
    private val gateControlReauthService: GateControlReauthService,
    private val objectMapper: ObjectMapper,
) : WebMvcConfigurer {
    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        val absolutePath = File(imageDir).absoluteFile
        absolutePath.mkdirs()
        registry.addResourceHandler("/loc-images/**")
            .addResourceLocations("file:${absolutePath.path}${File.separator}")
    }

    // Phase 10 후속 — 게이트 제어 재인증(2026-08-12 사용자 확인). 컨트롤러마다 검증을 반복하지
    // 않도록 상태 변경 경로에만 인터셉터 하나로 일괄 적용한다(GateControlReauthInterceptor KDoc 참고).
    //
    // 코드 리뷰 지적(2026-08-14): ScheduleController(`/schedule/apply`, `/schedule/reset`,
    // `/schedule/timezones`, `/schedule/timezones/sync`)도 실제로 게이트에 제어 명령(모드 변경/
    // 리셋/타임존 배포)을 큐에 적재하는 상태 변경 엔드포인트라 다른 게이트 제어 경로와 동일하게
    // 재인증 대상이어야 한다 — 컨트롤러가 "스캐폴드 단계"라 제외했던 이전 주석은 더 이상 맞지
    // 않으므로 대상에 포함한다.
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(GateControlReauthInterceptor(securitySettingsProperties, gateControlReauthService, objectMapper))
            .addPathPatterns(
                "/api/gate-control/**",
                "/gates/details/*/mode",
                "/gates/details/*/motor",
                "/gates/details/*/fast-motor",
                "/gates/reset/execute",
                "/schedule/apply",
                "/schedule/reset",
                "/schedule/timezones",
                "/schedule/timezones/sync",
            )
    }
}
