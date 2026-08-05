package kr.co.securance.secuhub.domain.repository

import kr.co.securance.secuhub.domain.entity.OprStatus
import kr.co.securance.secuhub.domain.entity.OprStatusId
import org.springframework.data.jpa.repository.JpaRepository

/** `tb_opr_status` 리포지토리 — 대시보드 통행량 위젯(`uvw_user_cnt` 대응, 계획서 5.2절)의 원본 데이터. */
interface OprStatusRepository : JpaRepository<OprStatus, OprStatusId> {
    fun findByLocIdAndGrpIdAndIdOprDateBetween(locId: Long, grpId: Long, fromDate: String, toDate: String): List<OprStatus>
}
