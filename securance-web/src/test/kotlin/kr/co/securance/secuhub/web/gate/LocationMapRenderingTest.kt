package kr.co.securance.secuhub.web.gate

import kr.co.securance.secuhub.domain.entity.GateGroup
import kr.co.securance.secuhub.domain.entity.GateLocation
import kr.co.securance.secuhub.web.menu.MenuProvider
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

/**
 * `/gates/locations/{id}/map` 실제 Thymeleaf 렌더링 검증(2026-08-25 Opus 재검토 지적).
 *
 * 삼항식 파싱 오류 자체는 [02b2925]에서 고쳤지만, 그 수정은 `grp.grpX != null`만 가드로 삼아
 * `location.locMapWidth`/`locMapHeight`(둘 다 nullable `Int?`)가 null인 레거시 데이터에서는
 * 여전히 SpEL이 null 산술(`grp.grpX * 100.0 / location.locMapWidth`)로 500을 던졌다 — 실제
 * MockMvc로 템플릿을 렌더링해 이 경로를 재현·고정한다.
 *
 * 이어서 Codex adversarial 리뷰가 지적한 후속 결함(2026-08-25) — 500은 막았지만 크기 정보가
 * 없는 상태로 드래그를 계속 허용하면 저장 시 좌표가 (0,0)으로 조용히 덮어써진다 — 을 막기 위해
 * 크기 정보가 없을 때는 경고 배너를 노출하고 드래그 저장을 비활성화(JS mapSizeValid 가드)했다.
 * 드래그 활성화 여부는 클라이언트 JS라 MockMvc로 직접 검증할 수 없으므로, 서버가 렌더링하는
 * 경고 배너의 노출/비노출로 그 전제 조건을 고정한다.
 *
 * 버그 수정(Opus 리뷰 지적, 2026-09-02): 기존 두 테스트는 500이 안 나는지·경고 배너 유무만
 * 확인해서, 정작 사용자가 보고한 증상인 "`<img>`가 실제로 렌더링되는지"와 "좌표 계산이 다시
 * 리터럴로 퇴화해도(즉 삼항식이 문자열 그대로 출력돼도) 잡아내는지"를 검증하지 못했다.
 * `<img src="/loc-images/...">` 존재 여부와, 크기 정보가 있을 때 마커 좌표(`left:12.5%;
 * top:8.333...%`)가 실제로 환산돼 출력되는지를 추가로 고정한다.
 *
 * 버그 수정(2026-09-02, 재조사): DB의 loc_map은 채워져 있어도 실제 이미지 파일이 이 서버 디스크에
 * 없을 수 있다(공유 개발 DB + 서버별 로컬 디스크 조합 — GateLocationService.mapImageFileExists
 * KDoc 참고). 이전에는 이 경우도 그냥 `<img>`를 렌더링해, 로드에 실패한 이미지 자리 위로 그룹
 * 마커만 겹쳐 보이는(게이트 관리 위치 팝업에서 "지도" 클릭 시 "그룹 아이콘만 보이고 지도 이미지는
 * 안 보인다") 원인 불명의 화면이 됐다 — 아래 두 테스트는 `mapImageFileExists`를 명시적으로
 * true로 스텁해 "파일이 실제로 있는" 정상 경로를 계속 검증하고, 별도 테스트로 파일이 없을 때
 * `<img>` 대신 원인이 분명한 안내 배너만 렌더링됨을 고정한다.
 */
@WebMvcTest(LocationMapController::class)
@Import(LocationMapController::class)
class LocationMapRenderingTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var menuProvider: MenuProvider

    @MockitoBean
    private lateinit var locationService: GateLocationService

    @MockitoBean
    private lateinit var groupService: GateGroupService

    @Test
    @WithMockUser
    fun `배치도 크기가 없어도 그룹 좌표가 있는 위치 배치도는 500 없이 렌더링된다`() {
        // loc_map은 있지만 loc_map_w/h가 채워지지 않은 레거시 이관 데이터 재현.
        val location = GateLocation(locId = 1L, locName = "테스트위치", locMap = "map.png", locMapWidth = null, locMapHeight = null)
        val group = GateGroup(grpId = 1L, location = location, grpName = "테스트그룹", grpX = 100, grpY = 50)

        `when`(menuProvider.menu()).thenReturn(emptyList())
        `when`(locationService.findByIdOrNull(1L)).thenReturn(location)
        `when`(locationService.mapImageFileExists("map.png")).thenReturn(true)
        `when`(groupService.findAllActiveByLocation(1L)).thenReturn(listOf(group))

        mockMvc.get("/gates/locations/1/map") { with(csrf()) }.andExpect {
            status { isOk() }
            content {
                string(
                    allOf(
                        containsString("이미지 크기 정보가 없는 레거시 배치도입니다"),
                        // 크기 정보가 없어도 이미지 자체는 계속 표시돼야 한다(사용자가 보고한 증상은
                        // "지도 이미지가 안 보인다"였다 — 경고 배너만 확인하면 <img>가 통째로 사라져도
                        // 이 테스트는 통과해버린다).
                        containsString("/loc-images/map.png"),
                    ),
                )
            }
        }
    }

    @Test
    @WithMockUser
    fun `배치도 크기와 그룹 좌표가 모두 있으면 500 없이 렌더링되고 좌표가 실제로 환산된다`() {
        val location = GateLocation(locId = 1L, locName = "테스트위치", locMap = "map.png", locMapWidth = 800, locMapHeight = 600)
        val group = GateGroup(grpId = 1L, location = location, grpName = "테스트그룹", grpX = 100, grpY = 50)

        `when`(menuProvider.menu()).thenReturn(emptyList())
        `when`(locationService.findByIdOrNull(1L)).thenReturn(location)
        `when`(locationService.mapImageFileExists("map.png")).thenReturn(true)
        `when`(groupService.findAllActiveByLocation(1L)).thenReturn(listOf(group))

        mockMvc.get("/gates/locations/1/map") { with(csrf()) }.andExpect {
            status { isOk() }
            content {
                string(
                    allOf(
                        not(containsString("이미지 크기 정보가 없는 레거시 배치도입니다")),
                        containsString("/loc-images/map.png"),
                        // grpX=100/locMapWidth=800 -> 12.5%, grpY=50/locMapHeight=600 -> 8.333...% —
                        // 삼항식이 다시 리터럴 토큰으로 퇴화해 파싱/평가가 실패하는 회귀를 좌표
                        // 출력값으로 직접 고정한다(경고 배너 부재만으로는 이 회귀를 잡지 못한다).
                        containsString("left:12.5%"),
                        containsString("top:8.333333333333334%"),
                    ),
                )
            }
        }
    }

    @Test
    @WithMockUser
    fun `배치도 이미지 파일이 서버 디스크에 없으면 img 대신 안내 배너만 렌더링된다`() {
        // DB(loc_map)는 정상이지만 실제 파일은 이 서버에 없는 상태(공유 개발 DB + 서버별 로컬
        // 디스크 조합에서 다른 환경이 업로드한 위치를 열람하는 시나리오) 재현.
        val location = GateLocation(locId = 1L, locName = "테스트위치", locMap = "missing.png", locMapWidth = 800, locMapHeight = 600)
        val group = GateGroup(grpId = 1L, location = location, grpName = "테스트그룹", grpX = 100, grpY = 50)

        `when`(menuProvider.menu()).thenReturn(emptyList())
        `when`(locationService.findByIdOrNull(1L)).thenReturn(location)
        `when`(locationService.mapImageFileExists("missing.png")).thenReturn(false)
        `when`(groupService.findAllActiveByLocation(1L)).thenReturn(listOf(group))

        mockMvc.get("/gates/locations/1/map") { with(csrf()) }.andExpect {
            status { isOk() }
            content {
                string(
                    allOf(
                        containsString("배치도 이미지 파일을 찾을 수 없습니다"),
                        // <img>도, 그 위에 겹쳐 보이던 그룹 마커도 나오면 안 된다 — 배경 이미지 없이
                        // 마커만 떠 있으면 이번에 조사한 것과 똑같이 원인 불명의 화면이 된다.
                        not(containsString("/loc-images/missing.png")),
                        not(containsString("group-marker")),
                    ),
                )
            }
        }
    }
}
