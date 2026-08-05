package kr.co.securance.secuhub.domain.entity

import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import kr.co.securance.secuhub.domain.converter.YnConverter

/**
 * `tb_users` — 시스템 사용자. Spring Security 인증(5.4절)의 소스가 된다.
 * [passwordHash]는 BCrypt 등으로 해시된 값을 저장한다(레거시는 평문 저장 — 명시적 개선사항).
 */
@Entity
@Table(name = "tb_users")
class AppUser(
    @Id
    @Column(name = "user_id", length = 20)
    val userId: String,

    @Column(name = "passwd", nullable = false)
    var passwordHash: String,

    @Column(name = "user_nm", nullable = false, length = 100)
    var userName: String,

    @Convert(converter = YnConverter::class)
    @Column(name = "use_yn", nullable = false)
    var useYn: Boolean = true,

    @Convert(converter = YnConverter::class)
    @Column(name = "auth_view", nullable = false)
    var authView: Boolean = true,

    @Convert(converter = YnConverter::class)
    @Column(name = "auth_ctrl", nullable = false)
    var authControl: Boolean = false,

    @Convert(converter = YnConverter::class)
    @Column(name = "auth_admin", nullable = false)
    var authAdmin: Boolean = false,
)
