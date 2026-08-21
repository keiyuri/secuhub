package kr.co.securance.secuhub.web.gate

import kr.co.securance.secuhub.domain.repository.GateLaneInfo
import kr.co.securance.secuhub.server.connection.GateConnectionRegistry
import kr.co.securance.secuhub.server.connection.GateConnectionState
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.ui.ExtendedModelMap
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [GateControlPageController]의 게이트 목록 정렬 검증(2026-08-21 사용자 요청 — "게이트 목록의
 * 표시 순서는 화면 어디서든 설치 위치 ID, 그룹 ID, 게이트 ID 순이어야 한다"). 이 화면은 DB가 아니라
 * 현재 접속 중인 커넥션에서 목록을 만들기 때문에, 접속 순서(IP/레인 번호)가 아니라 각 레인의
 * `tb_gate_dtl` 식별자(locId/grpId/dtlId)로 다시 정렬되는지가 핵심이다.
 *
 * [GateConnectionState]는 실제 소켓(reactor-netty `Connection`/`NettyOutbound`)을 들고 있는 클래스라
 * 직접 생성하지 않고 통째로 mock한다 — 이 화면 로직이 실제로 읽는 값(dtlIp/gateTypeCode/
 * laneSnapshot/laneInfoOf/hasAuthoritativeLaneInfo)만 스텁하면 충분하다.
 */
class GateControlPageControllerTest {

    private fun connectionState(
        dtlIp: String,
        lane: Int,
        locId: Long?,
        grpId: Long?,
        dtlId: Long?,
    ): GateConnectionState {
        val state = mock(GateConnectionState::class.java)
        `when`(state.dtlIp).thenReturn(dtlIp)
        `when`(state.gateTypeCode).thenReturn(1)
        `when`(state.laneSnapshot()).thenReturn(setOf(lane))
        `when`(state.hasAuthoritativeLaneInfo).thenReturn(true)
        if (locId != null && grpId != null) {
            `when`(state.laneInfoOf(lane)).thenReturn(
                GateLaneInfo(locId = locId, grpId = grpId, dtlId = dtlId, dtlLaneNo = lane, dtlType = 1, analysisYn = true),
            )
        }
        return state
    }

    @Test
    fun `게이트 제어 화면 목록은 위치ID-그룹ID-게이트ID 순으로 정렬된다`() {
        // IP/접속 순서는 일부러 뒤섞어 놓는다 — dtlIp/dtlLaneNo 순이 아니라 locId/grpId/dtlId
        // 순으로 정렬돼야 함을 검증하기 위함.
        val stateZ = connectionState(dtlIp = "192.168.0.99", lane = 1, locId = 2L, grpId = 20L, dtlId = 200L)
        val stateA = connectionState(dtlIp = "192.168.0.1", lane = 1, locId = 1L, grpId = 10L, dtlId = 101L)
        val stateB = connectionState(dtlIp = "192.168.0.2", lane = 1, locId = 1L, grpId = 10L, dtlId = 100L)
        val registry = mock(GateConnectionRegistry::class.java)
        `when`(registry.allConnections()).thenReturn(listOf(stateZ, stateA, stateB))

        val model = ExtendedModelMap()
        GateControlPageController(registry).controlPage(model)

        @Suppress("UNCHECKED_CAST")
        val gates = model.getAttribute("gates") as List<ConnectedGateView>
        assertEquals(listOf(100L, 101L, 200L), gates.map { it.dtlId })
    }

    @Test
    fun `tb_gate_dtl에 등록되지 않아 식별자를 모르는 레인은 목록 맨 뒤로 밀린다`() {
        val known = connectionState(dtlIp = "192.168.0.1", lane = 1, locId = 1L, grpId = 10L, dtlId = 100L)
        val unknown = connectionState(dtlIp = "192.168.0.0", lane = 1, locId = null, grpId = null, dtlId = null)
        val registry = mock(GateConnectionRegistry::class.java)
        `when`(registry.allConnections()).thenReturn(listOf(unknown, known))

        val model = ExtendedModelMap()
        GateControlPageController(registry).controlPage(model)

        @Suppress("UNCHECKED_CAST")
        val gates = model.getAttribute("gates") as List<ConnectedGateView>
        assertEquals(listOf("192.168.0.1", "192.168.0.0"), gates.map { it.dtlIp })
    }
}
