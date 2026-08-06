package kr.co.securance.secuhub.common.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IpMaskingUtilTest {

    @Test
    fun `오른쪽 마스킹 hide=1은 마지막 옥텟만 가린다`() {
        assertEquals("192.168.0.*", IpMaskingUtil.rightMask("192.168.0.120", hide = 1))
    }

    @Test
    fun `오른쪽 마스킹 hide=2는 마지막 두 옥텟을 가린다`() {
        assertEquals("192.168.*.*", IpMaskingUtil.rightMask("192.168.0.120", hide = 2))
    }

    @Test
    fun `오른쪽 마스킹 hide=3은 마지막 세 옥텟을 가린다`() {
        assertEquals("192.*.*.*", IpMaskingUtil.rightMask("192.168.0.120", hide = 3))
    }

    @Test
    fun `왼쪽 마스킹 hide=1은 첫 옥텟만 가린다`() {
        assertEquals("*.168.0.120", IpMaskingUtil.leftMask("192.168.0.120", hide = 1))
    }

    @Test
    fun `왼쪽 마스킹 hide=2는 앞쪽 두 옥텟을 가린다`() {
        assertEquals("*.*.0.120", IpMaskingUtil.leftMask("192.168.0.120", hide = 2))
    }

    @Test
    fun `왼쪽 마스킹 hide=3은 앞쪽 세 옥텟을 가린다`() {
        assertEquals("*.*.*.120", IpMaskingUtil.leftMask("192.168.0.120", hide = 3))
    }

    @Test
    fun `null과 빈 문자열은 그대로 반환한다`() {
        assertNull(IpMaskingUtil.rightMask(null))
        assertEquals("", IpMaskingUtil.rightMask(""))
        assertNull(IpMaskingUtil.leftMask(null))
        assertEquals("", IpMaskingUtil.leftMask(""))
    }

    @Test
    fun `IPv4 형식이 아니면 원본을 그대로 반환한다`() {
        assertEquals("not-an-ip", IpMaskingUtil.rightMask("not-an-ip", hide = 1))
        assertEquals("localhost", IpMaskingUtil.leftMask("localhost", hide = 1))
    }

    @Test
    fun `hide 값이 범위를 벗어나면 원본을 그대로 반환한다`() {
        assertEquals("192.168.0.120", IpMaskingUtil.rightMask("192.168.0.120", hide = 0))
        assertEquals("192.168.0.120", IpMaskingUtil.leftMask("192.168.0.120", hide = 4))
    }
}
