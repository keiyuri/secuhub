package kr.co.securance.secuhub.protocol

import kr.co.securance.secuhub.common.exception.UnsupportedGateTypeException
import kr.co.securance.secuhub.common.gate.GateTypeCodes
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GateProtocolCodecRegistryTest {

    private val registry = GateProtocolCodecRegistry(listOf(SpeedFlapGateProtocolCodec()))

    @Test
    fun `Speed Gate와 Flap Gate는 동일 코덱을 공유한다`() {
        val speedCodec = registry.resolve(GateTypeCodes.SPEED_GATE)
        val flapCodec = registry.resolve(GateTypeCodes.FLAP_GATE)

        assertTrue(speedCodec === flapCodec)
    }

    @Test
    fun `Turn Gate Fast Gate는 아직 지원하지 않는다`() {
        assertFalse(registry.supports(GateTypeCodes.TURN_GATE))
        assertFalse(registry.supports(GateTypeCodes.FAST_GATE))

        assertFailsWith<UnsupportedGateTypeException> { registry.resolve(GateTypeCodes.TURN_GATE) }
        assertFailsWith<UnsupportedGateTypeException> { registry.resolve(GateTypeCodes.FAST_GATE) }
    }
}
