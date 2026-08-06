package kr.co.securance.secuhub.web.security

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder

/**
 * `tb_users.passwd`에는 두 종류의 값이 섞여 있을 수 있다: 신규/이관된 BCrypt 해시와,
 * 레거시 시스템에서 그대로 넘어온 평문 비밀번호(주석 참고: [kr.co.securance.secuhub.domain.entity.AppUser]).
 *
 * 순수 [BCryptPasswordEncoder]만 쓰면 레거시 평문 비밀번호를 가진 계정은 해시 포맷 파싱에서
 * 항상 실패해 **영구히 로그인이 불가능**해진다(로그인 실패 원인도 겉으로는 구분되지 않는다).
 * 이 인코더는 저장된 값이 BCrypt 해시 형태인지 판별해 그에 맞는 방식으로 비교하고,
 * 레거시 평문으로 로그인에 성공한 경우 [upgradeEncoding]이 true를 반환해
 * [SecurityUserDetailsService]가 즉시 BCrypt로 재해시해서 저장하도록 만든다(스프링 시큐리티의
 * `UserDetailsPasswordService` 자동 업그레이드 메커니즘 — 별도 배치/마이그레이션 스크립트 불필요).
 */
class LegacyAwarePasswordEncoder(
    private val delegate: PasswordEncoder = BCryptPasswordEncoder(),
) : PasswordEncoder {

    // PasswordEncoder는 Java 인터페이스라 파라미터에 null 허용 표기가 없어(플랫폼 타입),
    // Kotlin이 non-null로 오버라이드하면 시그니처가 일치하지 않는다 — null 허용으로 선언해 맞춘다.
    override fun encode(rawPassword: CharSequence?): String = requireNotNull(delegate.encode(rawPassword))

    override fun matches(rawPassword: CharSequence?, encodedPassword: String?): Boolean {
        if (encodedPassword == null) return false
        return if (isBCryptHash(encodedPassword)) {
            delegate.matches(rawPassword, encodedPassword)
        } else {
            // 레거시 평문 저장분 — 상수 시간 비교는 아니지만, 이는 일회성 이관 경로일 뿐이며
            // 성공 시 즉시 BCrypt로 승격되므로(아래 upgradeEncoding) 같은 계정은 다음 로그인부터
            // 이 분기를 다시 타지 않는다.
            rawPassword?.toString() == encodedPassword
        }
    }

    /** 레거시 평문(BCrypt 형태가 아님)이면 다음 성공 로그인 시 재해시가 필요하다고 알린다. */
    override fun upgradeEncoding(encodedPassword: String?): Boolean =
        encodedPassword != null && !isBCryptHash(encodedPassword)

    private fun isBCryptHash(value: String): Boolean = BCRYPT_PATTERN.matches(value)

    private companion object {
        // $2a$ / $2b$ / $2y$ + cost(2자리) + '$' + 53자리 salt+hash
        val BCRYPT_PATTERN = Regex("^\\$2[aby]\\$\\d{2}\\$.{53}$")
    }
}
