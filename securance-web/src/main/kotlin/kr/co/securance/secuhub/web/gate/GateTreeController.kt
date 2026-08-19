package kr.co.securance.secuhub.web.gate

import kr.co.securance.secuhub.domain.repository.GateDetailRepository
import kr.co.securance.secuhub.domain.repository.GateGroupRepository
import kr.co.securance.secuhub.domain.repository.GateLocationRepository
import kr.co.securance.secuhub.domain.repository.NetStateRepository
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

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
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    private data class CachedSnapshot(val builtAt: Instant, val tree: List<GateTreeLocationNode>)

    // 짧은 TTL 공유 캐시(2026-08-20 Opus 전체 리뷰 지적) — /api/gate-tree는 gate-tree.js가 탭당
    // 5초 주기로 폴링하는데, 매 호출마다 위치/그룹/게이트/온라인상태 4개 테이블을 전량 조회한다.
    // 운영자가 여러 탭·여러 인원으로 동시 접속하면 5초 폴링 주기 안에서도 사실상 같은 스냅샷을
    // 반복 재계산하게 되어, 규모(1,000+ 디바이스)가 커질수록 이 엔드포인트가 가장 먼저 병목이 된다.
    // TTL을 폴링 주기의 절반(2초)으로 두어 화면 갱신 지연을 체감하지 않으면서 동시 요청을 한 번의
    // DB 조회로 흡수한다. LoginAttemptService와 동일하게 Clock을 주입받아 테스트에서 만료를
    // 결정론적으로 재현할 수 있게 한다.
    private val cacheTtl: Duration = Duration.ofSeconds(2)
    private val cache = AtomicReference<CachedSnapshot?>(null)

    fun buildTree(): List<GateTreeLocationNode> {
        cache.get()?.let { snapshot -> if (isFresh(snapshot)) return snapshot.tree }
        // 캐시 만료 시에만 동기화한다 — 캐시 적중 경로는 락 없이 빠르게 빠져나간다. 만료 직후
        // 동시에 도착한 요청들이 전부 DB를 재조회하는 스탬피드를 막기 위해, 재조립은 한 스레드만
        // 수행하고 나머지는 그 결과를 그대로 재사용한다.
        synchronized(this) {
            cache.get()?.let { snapshot -> if (isFresh(snapshot)) return snapshot.tree }
            val tree = buildTreeUncached()
            cache.set(CachedSnapshot(Instant.now(clock), tree))
            return tree
        }
    }

    /**
     * 캐시가 TTL 이내인지 판단한다. `elapsed`가 음수(시계 역행)인 경우도 명시적으로 만료 처리한다
     * (2026-08-20 Codex 적대적 리뷰 지적) — `Duration.between(builtAt, now) < cacheTtl`만으로는
     * NTP 보정/VM 시간 동기화 등으로 시스템 시계가 뒤로 이동했을 때 음수 Duration도 조건을
     * 만족해버려, 시계가 원래 시각을 따라잡을 때까지 캐시가 사실상 영구히 유지된다 — 그 사이
     * 운영자는 실제로는 바뀐 위치/게이트 구성이나 온라인 상태를 계속 못 보게 된다.
     */
    private fun isFresh(snapshot: CachedSnapshot): Boolean {
        val elapsed = Duration.between(snapshot.builtAt, Instant.now(clock))
        return !elapsed.isNegative && elapsed < cacheTtl
    }

    private fun buildTreeUncached(): List<GateTreeLocationNode> {
        // 트리 정렬 순서 — 위치 ID → 그룹 ID → 게이트(레인) ID(2026-08-19 사용자 요청, SR_Speed_Client
        // 트리뷰의 InsertNodeSorted와 동일하게 이름이 아닌 ID 기준으로 정렬한다).
        val locations = locationRepository.findAll(Sort.by("locId"))
        // GateGroupRepository.findAll()/GateDetailRepository.findAllForTree()는 JOIN FETCH로
        // location/group을 함께 읽어온다(둘 다 LAZY + open-in-view:false, 계획서 4.2절 대응).
        val groupsByLoc = groupRepository.findAll().groupBy { it.location.locId }
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
