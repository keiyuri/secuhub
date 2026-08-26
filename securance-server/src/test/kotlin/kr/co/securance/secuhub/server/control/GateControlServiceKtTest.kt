package kr.co.securance.secuhub.server.control

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `tb_data_snd.snd_server`(`VARCHAR(20)`) 길이 강제 회귀 방지 테스트(2026-08-25 Codex 적대적
 * 리뷰 지적 — "IPv4 전용 배포 가정" 문서화만으로는 실제 환경에서 IPv6 주소가 반환될 때 INSERT가
 * 길이 초과로 실패하는 것을 막지 못한다는 지적). [sanitizeSndServer]는 [localServerId]에서 네트워크
 * 의존적인 탐지 로직을 뺀 순수 함수라 실제 NIC 구성과 무관하게 결정적으로 검증할 수 있다.
 */
class GateControlServiceKtTest {

    @Test
    fun `20자 이하 후보는 그대로 반환한다`() {
        assertEquals("192.168.0.10", sanitizeSndServer("192.168.0.10"))
    }

    @Test
    fun `20자 초과 후보(IPv6 등)는 20자로 잘라 컬럼 길이 초과를 막는다`() {
        val ipv6 = "fe80::1234:5678:9abc:def0%eth0" // 39자 이상 가능한 IPv6 예시
        val result = sanitizeSndServer(ipv6)

        assertTrue(result.length <= 20)
        assertEquals(ipv6.take(20), result)
    }

    @Test
    fun `정확히 20자인 후보는 그대로 반환한다(경계값)`() {
        val exact20 = "1234567890123456789A"
            .take(20)
        assertEquals(20, exact20.length)
        assertEquals(exact20, sanitizeSndServer(exact20))
    }
}
