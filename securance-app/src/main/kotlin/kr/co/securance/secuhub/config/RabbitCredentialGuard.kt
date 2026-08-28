package kr.co.securance.secuhub.config

import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * RabbitMQ 기본 자격증명(guest/guest) 운영 기동 가드(2026-08-13 코드 리뷰 지적).
 *
 * `application.yml`은 `SECURANCE_RABBITMQ_USER`/`PASSWORD`에 guest/guest를 기본값으로 둔다(로컬
 * 개발 편의 목적). DB 비밀번호와 달리 이 fallback을 프로필 전체에서 없앨 수는 없다 — RabbitMQ
 * 스타터가 항상 클래스패스에 있어(`securance-app/build.gradle.kts` 주석 참고) 스프링이
 * `securance.messaging.rabbitmq.enabled` 값과 무관하게 `spring.rabbitmq.*`를 기동 시점에
 * 바인딩하므로, fallback을 지우면 RabbitMQ를 아예 안 쓰는 배포(기본값)까지 기동이 막힌다.
 *
 * 대신 실제로 연동을 켰는데(`enabled=true`) 자격증명이 기본값 그대로면, 조용히 guest로 연결을
 * 시도하는 대신 기동 자체를 막아 운영자가 환경변수 설정을 빠뜨렸음을 즉시 알게 한다 —
 * `SecuranceApplication` 컴포넌트 스캔 루트 하위라 별도 등록 없이 인식된다.
 *
 * `@Profile("prod")`가 아니라 `@Profile("!local")`을 쓴다(코드 리뷰 지적, 2026-08-28) — "prod"
 * 문자열에만 걸면 배포 스크립트가 `--spring.profiles.active` 자체를 빠뜨린 경우(과거 S-1 DB URL
 * 사고와 동일한 실수 패턴, [LocalProfileAutoDetector] KDoc 참고) 가드가 전혀 실행되지 않아 guest/guest로
 * 운영 브로커에 조용히 연결을 시도할 수 있었다. 이 프로젝트는 "local"과 "prod" 두 프로필만 쓰므로,
 * "local이 아니면(=개발자가 명시적으로 로컬임을 선언하지 않는 한) 항상 검사"로 바꿔 프로필 누락
 * 시에도 기본 안전 상태(가드 활성)를 보장한다.
 */
@Component
@Profile("!local")
class RabbitCredentialGuard(
    @Value("\${securance.messaging.rabbitmq.enabled}") private val enabled: Boolean,
    @Value("\${spring.rabbitmq.username}") private val username: String,
    @Value("\${spring.rabbitmq.password}") private val password: String,
) {
    @PostConstruct
    fun verify() {
        // 2026-08-13 코드 리뷰(Codex) 지적: 원래 두 값이 "모두" guest일 때만 차단했는데, 사용자명·
        // 비밀번호 환경변수 중 하나만 빠뜨려도(예: PASSWORD만 설정하고 USER는 누락) 나머지 하나는
        // 여전히 기본값 guest로 남는다 — 이 경우도 운영 기본 자격증명을 그대로 쓰는 것과 동일한
        // 위험이므로 &&가 아니라 ||로 검사해 "둘 중 하나라도 guest"면 기동을 막는다.
        check(!(enabled && (username == "guest" || password == "guest"))) {
            "SECURANCE_RABBITMQ_USER/SECURANCE_RABBITMQ_PASSWORD 환경변수가 설정되지 않았습니다 " +
                "(securance.messaging.rabbitmq.enabled=true인데 자격증명 중 하나 이상이 기본값 guest입니다). " +
                "운영 환경에서 RabbitMQ 연동을 켤 때는 두 환경변수를 반드시 지정하세요."
        }
    }
}
