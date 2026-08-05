package kr.co.securance.secuhub.domain.entity

import kr.co.securance.secuhub.common.gate.GateTypeCodes
import kotlin.test.Test
import kotlin.test.assertEquals

class GateGroupTest {

    private fun group(laneCount: Int) = GateGroup(
        location = GateLocation(locName = "테스트 위치"),
        grpName = "테스트 그룹",
        laneCount = laneCount,
        gateTypeCode = GateTypeCodes.SPEED_GATE,
    )

    @Test
    fun `물리적 게이트 유닛 수는 레인 수 + 1이다 (요청 예시 검증)`() {
        assertEquals(2, group(laneCount = 1).physicalGateCount)
        assertEquals(6, group(laneCount = 5).physicalGateCount)
        assertEquals(33, group(laneCount = 32).physicalGateCount)
    }
}
