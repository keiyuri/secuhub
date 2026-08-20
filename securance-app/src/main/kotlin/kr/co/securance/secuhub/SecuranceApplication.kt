package kr.co.securance.secuhub

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.builder.SpringApplicationBuilder

/**
 * securance-app 진입점(계획서 7절).
 *
 * 패키지를 모듈 공통 루트(`kr.co.securance.secuhub`)에 두어, 컴포넌트 스캔/JPA 엔티티·리포지토리
 * 스캔이 기본 설정만으로 common/protocol/domain/server/scheduler/web 전 모듈을 커버하게 한다.
 */
@SpringBootApplication
class SecuranceApplication

/**
 * `application-local.yml`이 클래스패스에 존재하고 프로필이 달리 지정되지 않았다면 `local` 프로필을
 * 자동으로 켠다(판단 로직은 [LocalProfileAutoDetector] 참고) — `./gradlew.bat :securance-app:bootRun`을
 * 프로필 인자 없이 바로 실행해도 로컬 개발 DB로 붙는다.
 */
fun main(args: Array<String>) {
    val hasLocalConfig = SecuranceApplication::class.java.classLoader.getResource("application-local.yml") != null
    val systemProperties = System.getProperties().entries.associate { (key, value) -> key.toString() to value.toString() }
    val shouldActivateLocal = LocalProfileAutoDetector.shouldActivateLocalProfile(
        args = args,
        env = System.getenv(),
        systemProperties = systemProperties,
        hasLocalConfigResource = hasLocalConfig,
    )

    val app = SpringApplicationBuilder(SecuranceApplication::class.java)
    if (shouldActivateLocal) {
        app.profiles("local")
    }
    app.run(*args)
}
