package kr.co.securance.secuhub.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpeedFlapGateProtocolCodecTest {

    private val codec = SpeedFlapGateProtocolCodec()

    @Test
    fun `주소 인자 없이 buildStatusRequest를 호출하면 레거시와 동일하게 주소 바이트 13개가 전부 0이다`() {
        // [Codex 리뷰 수정 회귀 테스트] 예전에는 이 기본 구현이 buildAddress(1,1,1)을 임의로 지어
        // 썼는데, 레거시 `ClsCommon.MakeReqStatusDataWithDateTime`은 애초에 주소 바이트(offset
        // 6~18)를 채우지 않고 0으로 남겨둔다 — 기본 주소가 아닌 장치에서 상태 조회가 무시될 수
        // 있던 버그를 레거시와 동일한 "주소 필드 미사용(전부 0)"으로 고쳐야 한다.
        val packet = codec.buildStatusRequest()

        val addressBytes = packet.copyOfRange(
            SpeedGateProtocolConstants.HeaderOffset.ADDRESS,
            SpeedGateProtocolConstants.HeaderOffset.ADDRESS + SpeedGateProtocolConstants.ADDRESS_LENGTH,
        )
        assertTrue(addressBytes.all { it == 0.toByte() })
        assertTrue(SpeedGatePacketCodec.verifyChecksum(packet))
    }

    @Test
    fun `주소를 명시하면 그대로 반영된다`() {
        val address = SpeedGatePacketCodec.buildAddress(comSlot = 2, controller = 3, deviceNumber = 4)
        val packet = codec.buildStatusRequest(address)

        val addressBytes = packet.copyOfRange(
            SpeedGateProtocolConstants.HeaderOffset.ADDRESS,
            SpeedGateProtocolConstants.HeaderOffset.ADDRESS + SpeedGateProtocolConstants.ADDRESS_LENGTH,
        )
        assertEquals(address.toList(), addressBytes.toList())
    }
}
