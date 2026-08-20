package kr.co.securance.secuhub.domain.repository

import kr.co.securance.secuhub.domain.entity.AppUser
import org.springframework.data.jpa.repository.JpaRepository

/** `tb_users` 리포지토리 — Spring Security 인증(계획서 5.4절)의 소스. */
interface AppUserRepository : JpaRepository<AppUser, String> {
    /**
     * 사용자 관리 화면의 기본 목록 — '사용=Y'인 사용자만 노출한다(2026-08-20 "관리 화면도 기본은
     * 사용=Y만 보이게 하고, 별도로 비활성 항목 보기 기능을 추가" 지시). 비활성 계정을
     * 재활성화해야 할 때는 [UserManagementService.findAll]의 showInactive=true 경로로 전체를 본다.
     */
    fun findByUseYnTrueOrderByUserId(): List<AppUser>
}
