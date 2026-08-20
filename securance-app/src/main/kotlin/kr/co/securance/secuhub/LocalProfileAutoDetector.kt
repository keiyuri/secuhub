package kr.co.securance.secuhub

/**
 * `application-local.yml`(.gitignore 대상, 커밋되지 않고 개발자 로컬에만 존재) 존재 여부로 `local`
 * 프로필 자동 활성화 여부를 판단한다(2026-08-20 — 로컬 실행 시 `--spring.profiles.active=local`을
 * 깜빡 빠뜨리면 기본 프로필(SECURANCE_DB_URL 등 폴백값 있는 application.yml)로 떨어져 개발서버로
 * 잘못 붙거나, `prod` 프로필이 실수로 딸려오면 `SECURANCE_DB_URL` 플레이스홀더 해석 실패로 기동
 * 자체가 죽는 사고가 있었다).
 *
 * 프로필이 이미 명시적으로 지정된 경우(커맨드라인 `--spring.profiles.active=...`, `SPRING_PROFILES_ACTIVE`
 * 환경변수, `spring.profiles.active` 시스템 프로퍼티 — 예: WinSW 서비스의 `prod` 고정 실행)는 절대
 * 덮어쓰지 않는다. 순수 함수로 분리해 `SecuranceApplicationKt.main`의 실제 클래스패스/환경 접근 없이
 * 단위 테스트할 수 있게 한다.
 */
object LocalProfileAutoDetector {

    fun shouldActivateLocalProfile(
        args: Array<String>,
        env: Map<String, String>,
        systemProperties: Map<String, String>,
        hasLocalConfigResource: Boolean,
    ): Boolean {
        if (!hasLocalConfigResource) return false

        val profileExplicitlySet =
            args.any { it.startsWith("--spring.profiles.active=") } ||
                !env["SPRING_PROFILES_ACTIVE"].isNullOrBlank() ||
                !systemProperties["spring.profiles.active"].isNullOrBlank()

        return !profileExplicitlySet
    }
}
