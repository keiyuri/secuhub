package kr.co.securance.secuhub.protocol

import kr.co.securance.secuhub.common.gate.GateTypeCodes
import kotlin.test.Test
import kotlin.test.assertTrue

class GateProtocolCodecRegistryTest {

    private val registry = GateProtocolCodecRegistry(listOf(SpeedFlapGateProtocolCodec()))

    @Test
    fun `Speed Flap Turn Fast Gate는 모두 동일 코덱을 공유한다`() {
        // FastGate Protocol Ver1_2020102601_01.md 확보 후 확인: 네 타입 모두 같은 Header/Command/Tail
        // 봉투를 쓰므로(SpeedFlapGateProtocolCodec KDoc 참고) 하나의 코덱 인스턴스로 처리한다.
        val speedCodec = registry.resolve(GateTypeCodes.SPEED_GATE)
        val flapCodec = registry.resolve(GateTypeCodes.FLAP_GATE)
        val turnCodec = registry.resolve(GateTypeCodes.TURN_GATE)
        val fastCodec = registry.resolve(GateTypeCodes.FAST_GATE)

        assertTrue(speedCodec === flapCodec)
        assertTrue(speedCodec === turnCodec)
        assertTrue(speedCodec === fastCodec)
    }
}
