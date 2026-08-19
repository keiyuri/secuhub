package kr.co.securance.secuhub.web.gate

import kr.co.securance.secuhub.domain.repository.GateDetailRepository
import kr.co.securance.secuhub.domain.repository.GateGroupRepository
import kr.co.securance.secuhub.domain.repository.GateLocationRepository
import kr.co.securance.secuhub.domain.repository.NetStateRepository
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/** 트리 리프(레인) — 우클릭 제어 메뉴가 필요로 하는 값(dtlId/dtlIp/dtlLaneNo)까지 그대로 노출한다. */
data class GateTreeDetailNode(
    val dtlId: Long,
    val dtlIp: String,
    val dtlLaneNo: Int,
    val dtlName: String?,
    val dtlType: Int,
    val online: Boolean,
)

data class GateTreeGroupNode(
    val grpId: Long,
    val grpName: String,
    val gateTypeCode: Int,
    val details: List<GateTreeDetailNode>,
)

data class GateTreeLocationNode(
    val locId: Long,
    val locName: String,
    val groups: List<GateTreeGroupNode>,
)

/**
 * Phase 10 — #18 대시보드 게이트 트리뷰(레거시 없이 신규 설계, 2026-08-12 사용자 확인).
 *
 * 레거시 UI 원본을 이 워크스페이스에서 확보하지 못해 계획서 3절 요구사항(LOC/GRP/DTL 3계층 트리 +
 * 연결상태 아이콘 + 우클릭 제어 메뉴)만으로 새로 설계했다. 우클릭 메뉴 자체(리셋/모드변경/모터설정)는
 * 이미 완성된 [GateControlApiController]/[GateControlController] 엔드포인트를 그대로 재사용한다 —
 * 이 서비스는 트리 구조 + 온라인 상태 조회만 담당한다.
 */
@Service
class GateTreeService(
    private val locationRepository: GateLocationRepository,
    private val groupRepository: GateGroupRepository,
    private val detailRepository: GateDetailRepository,
    private val netStateRepository: NetStateRepository,
) {
    fun buildTree(): List<GateTreeLocationNode> {
        // 트리 정렬 순서 — 위치 ID → 그룹 ID → 게이트(레인) ID(2026-08-19 사용자 요청, SR_Speed_Client
        // 트리뷰의 InsertNodeSorted와 동일하게 이름이 아닌 ID 기준으로 정렬한다).
        //
        // 사용여부(use_yn)/분석여부(analysis_yn) 필터 — 2026-08-19 사용자 요청("사용여부와
        // 분석여부가 Y인 것만 표시"). SR_Speed_Client GetTreeListAsync 쿼리(SR_C_MariaDB.cs
        // 1273~1360행)와 동일한 계층별 기준을 따른다: LOC/GRP는 use_yn='Y'만(두 테이블 모두
        // analysis_yn 컬럼이 없다), DTL은 use_yn='Y' AND analysis_yn='Y' — 후자는
        // findAllForTree()의 쿼리 조건으로 이미 반영돼 있다.
        val locations = locationRepository.findAll(Sort.by("locId")).filter { it.useYn }
        // GateGroupRepository.findAll()/GateDetailRepository.findAllForTree()는 JOIN FETCH로
        // location/group을 함께 읽어온다(둘 다 LAZY + open-in-view:false, 계획서 4.2절 대응).
        val groupsByLoc = groupRepository.findAll().filter { it.useYn }.groupBy { it.location.locId }
        val detailsByGrp = detailRepository.findAllForTree().groupBy { it.group.grpId }
        // (dtlIp, dtlLaneNo) 복합키로 온라인 여부를 조회한다 — GateResetGridService와 동일한 이유로
        // dtlLaneNo만으로는 IP가 다른 두 장비의 상태가 서로 덮어써질 수 있다.
        val onlineByKey = netStateRepository.findAll().associate { (it.id.dtlIp to it.id.dtlLaneNo) to it.isOnline }

        return locations.map { loc ->
            val groups = groupsByLoc[loc.locId].orEmpty()
                .sortedBy { it.grpId }
                .map { grp ->
                    val details = detailsByGrp[grp.grpId].orEmpty()
                        .sortedBy { it.dtlId }
                        .map { d ->
                            GateTreeDetailNode(
                                dtlId = requireNotNull(d.dtlId),
                                dtlIp = d.dtlIp,
                                dtlLaneNo = d.dtlLaneNo,
                                dtlName = d.dtlName,
                                dtlType = d.dtlType,
                                online = onlineByKey[d.dtlIp to d.dtlLaneNo] ?: false,
                            )
                        }
                    GateTreeGroupNode(
                        grpId = requireNotNull(grp.grpId),
                        grpName = grp.grpName,
                        gateTypeCode = grp.gateTypeCode,
                        details = details,
                    )
                }
            GateTreeLocationNode(
                locId = requireNotNull(loc.locId),
                locName = loc.locName,
                groups = groups,
            )
        }
    }
}

/**
 * `dashboard.html`의 트리뷰 위젯이 폴링하는 조회 전용 엔드포인트. `/api/gate-control` 하위 경로와
 * 달리 상태 변경이 없어 [kr.co.securance.secuhub.web.security.SecurityConfig]의 기본 규칙
 * (`anyRequest → hasRole("VIEW")`)만으로 충분하다 — 별도 URL 규칙 추가 불필요.
 */
@RestController
class GateTreeApiController(private val gateTreeService: GateTreeService) {
    @GetMapping("/api/gate-tree")
    fun tree(): List<GateTreeLocationNode> = gateTreeService.buildTree()
}
