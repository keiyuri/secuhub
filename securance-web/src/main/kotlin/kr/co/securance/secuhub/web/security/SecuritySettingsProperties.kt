package kr.co.securance.secuhub.web.security

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * `securance.security.*` 설정 — 로그인 기능을 운영 환경에 따라 켜고 끌 수 있게 한다
 * (2026-08-12 사용자 확인: "게이트 제어 실행 시" / "웹 접근 시" 두 축 모두 설정 가능해야 함).
 *
 * 재배포 없이 값만 바꿀 수 있도록(계획서 6절과 동일한 원칙) `application.yml`에서 읽는다 — 단,
 * Spring `authorizeHttpRequests`/인터셉터 등록은 애플리케이션 기동 시 1회 조립되므로, 값을
 * 바꾸려면 재기동이 필요하다(다른 `securance.*` on/off 플래그들과 동일한 제약).
 */
@ConfigurationProperties(prefix = "securance.security")
data class SecuritySettingsProperties(
    /**
     * false로 두면 [SecurityConfig]의 마지막 규칙(`anyRequest`)이 `permitAll`로 바뀌어 로그인 없이
     * 웹 화면에 접근할 수 있다. `/admin` 하위, `/control` 하위, 상태 변경 API 등 이미 명시된 역할 기반
     * 규칙은 이 설정과 무관하게 항상 그대로 적용된다 — 이 값은 오직 "그 외 나머지 화면"의 최소
     * 인증 요구(ROLE_VIEW) 여부만 토글한다.
     */
    val webLoginRequired: Boolean = true,

    /**
     * true면 [GateControlReauthInterceptor]가 상태를 바꾸는 게이트 제어 요청(리셋/모드 변경/모터
     * 설정 등)마다 현재 로그인 사용자의 비밀번호 재입력(`reauthPassword`)을 추가로 검증한다.
     * 세션이 이미 로그인돼 있어도(예: 방치된 브라우저 탈취) 실제 제어 명령 실행 순간에는 한 번 더
     * 본인 확인을 요구하는 2차 인증이다.
     */
    val gateControlReauthRequired: Boolean = false,
)
