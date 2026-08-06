package kr.co.securance.secuhub.web.common

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.io.File

/**
 * #5 SetupLocation 배치도 이미지 업로드([GateLocationService.uploadMap])가 저장한 파일을
 * `/loc-images/` 이하 와일드카드 경로로 서빙한다. 레거시(`SR_C_Global.locImgPath`, 로컬 파일 경로 직접 참조)와
 * 달리 웹은 별도 리소스 핸들러로 노출해야 브라우저가 `<img>` 태그로 불러올 수 있다.
 */
@Configuration
class WebConfig(
    @Value("\${securance.location.image-dir:./data/loc-images}") private val imageDir: String,
) : WebMvcConfigurer {
    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        val absolutePath = File(imageDir).absoluteFile
        absolutePath.mkdirs()
        registry.addResourceHandler("/loc-images/**")
            .addResourceLocations("file:${absolutePath.path}${File.separator}")
    }
}
