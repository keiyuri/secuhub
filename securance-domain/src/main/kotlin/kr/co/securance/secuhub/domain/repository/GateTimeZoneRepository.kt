package kr.co.securance.secuhub.domain.repository

import kr.co.securance.secuhub.domain.entity.GateTimeZone
import org.springframework.data.jpa.repository.JpaRepository

/** #7/#15(Phase 5) 타임존 CRUD·목록·전체동기화 대상 조회. */
interface GateTimeZoneRepository : JpaRepository<GateTimeZone, Long> {
    fun findByUseYnTrueOrderByTimezoneIdDesc(): List<GateTimeZone>
}
