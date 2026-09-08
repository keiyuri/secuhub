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

    // 컬럼 타입 재점검(2026-09-09, 개발 DB 실측): 실제 DB는 `user_nm varchar(20)`인데
    // 엔티티는 length=100으로 선언돼 있었다 — 21자 이상 이름을 등록하면 화면 검증은 통과하고
    // DB INSERT에서만 `Data too long` 예외가 나는 문제가 있어 실제 컬럼 길이로 정정한다.
    @Column(name = "user_nm", nullable = false, length = 20)
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
