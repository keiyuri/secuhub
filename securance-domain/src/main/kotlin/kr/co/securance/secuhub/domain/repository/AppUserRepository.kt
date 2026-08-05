package kr.co.securance.secuhub.domain.repository

import kr.co.securance.secuhub.domain.entity.AppUser
import org.springframework.data.jpa.repository.JpaRepository

/** `tb_users` 리포지토리 — Spring Security 인증(계획서 5.4절)의 소스. */
interface AppUserRepository : JpaRepository<AppUser, String>
