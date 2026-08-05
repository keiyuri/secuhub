package kr.co.securance.secuhub.domain.repository

import kr.co.securance.secuhub.domain.entity.NetState
import kr.co.securance.secuhub.domain.entity.NetStateId
import org.springframework.data.jpa.repository.JpaRepository

/** `tb_net_state` 리포지토리 — `NetCheckJob`(계획서 3.6절)이 연결 생존 상태를 갱신할 때 사용. */
interface NetStateRepository : JpaRepository<NetState, NetStateId> {
    fun findByIdDtlIpAndIdDtlLaneNo(dtlIp: String, dtlLaneNo: Int): List<NetState>

    /** 대시보드 온라인/오프라인 게이트 수 위젯(계획서 5.2절)에 사용. */
    fun countByDtlState(dtlState: String): Long
}
