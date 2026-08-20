package kr.co.securance.secuhub

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LocalProfileAutoDetectorTest {

    @Test
    fun `local 설정 파일이 없으면 프로필을 자동 활성화하지 않는다`() {
        val result = LocalProfileAutoDetector.shouldActivateLocalProfile(
            args = emptyArray(),
            env = emptyMap(),
            systemProperties = emptyMap(),
            hasLocalConfigResource = false,
        )

        assertThat(result).isFalse()
    }

    @Test
    fun `local 설정 파일이 있고 프로필이 지정되지 않았으면 자동 활성화한다`() {
        val result = LocalProfileAutoDetector.shouldActivateLocalProfile(
            args = emptyArray(),
            env = emptyMap(),
            systemProperties = emptyMap(),
            hasLocalConfigResource = true,
        )

        assertThat(result).isTrue()
    }

    @Test
    fun `커맨드라인 인자로 프로필이 지정되면 자동 활성화하지 않는다`() {
        val result = LocalProfileAutoDetector.shouldActivateLocalProfile(
            args = arrayOf("--spring.profiles.active=prod"),
            env = emptyMap(),
            systemProperties = emptyMap(),
            hasLocalConfigResource = true,
        )

        assertThat(result).isFalse()
    }

    @Test
    fun `SPRING_PROFILES_ACTIVE 환경변수로 프로필이 지정되면 자동 활성화하지 않는다`() {
        val result = LocalProfileAutoDetector.shouldActivateLocalProfile(
            args = emptyArray(),
            env = mapOf("SPRING_PROFILES_ACTIVE" to "prod"),
            systemProperties = emptyMap(),
            hasLocalConfigResource = true,
        )

        assertThat(result).isFalse()
    }

    @Test
    fun `spring_profiles_active 시스템 프로퍼티로 프로필이 지정되면 자동 활성화하지 않는다`() {
        val result = LocalProfileAutoDetector.shouldActivateLocalProfile(
            args = emptyArray(),
            env = emptyMap(),
            systemProperties = mapOf("spring.profiles.active" to "prod"),
            hasLocalConfigResource = true,
        )

        assertThat(result).isFalse()
    }

    @Test
    fun `빈 문자열 환경변수는 명시적 지정으로 취급하지 않는다`() {
        val result = LocalProfileAutoDetector.shouldActivateLocalProfile(
            args = emptyArray(),
            env = mapOf("SPRING_PROFILES_ACTIVE" to ""),
            systemProperties = emptyMap(),
            hasLocalConfigResource = true,
        )

        assertThat(result).isTrue()
    }
}
