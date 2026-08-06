package kr.co.securance.secuhub.common.util

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 실행 환경(CI/로컬)마다 실제 네트워크 인터페이스 구성이 다르므로, 특정 IP 값을 단언하지 않고
 * "예외 없이 동작하며, 값이 있다면 IPv4 dotted-decimal 형식이다"만 검증한다.
 */
class ServerIpDetectorTest {

    private val ipv4Pattern = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

    @Test
    fun `예외 없이 동작하고 반환값이 있다면 IPv4 형식이다`() {
        val result = ServerIpDetector.detectServerIp()
        if (result != null) {
            assertTrue(ipv4Pattern.matches(result), "IPv4 형식이 아닙니다: $result")
        }
    }

    @Test
    fun `반복 호출해도 동일한 결과를 반환한다`() {
        val first = ServerIpDetector.detectServerIp()
        val second = ServerIpDetector.detectServerIp()
        assertTrue(first == second)
    }
}
