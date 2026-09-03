package kr.co.securance.secuhub.web.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [IpDisplayUtil] 검증 — 대시보드/게이트 트리뷰/리포트 조회 화면에 IP를 표시할 때만 마스킹하고
 * (2026-09-03 사용자 확인), 게이트 상세 관리 폼 등 원본 IP가 필요한 곳에는 영향을 주지 않는지는
 * 이 유틸을 호출하지 않는 것으로 보장된다(각 템플릿의 `@ipDisplay.mask(...)` 호출 여부 참고).
 */
class IpDisplayUtilTest {

    private val util = IpDisplayUtil()

    @Test
    fun `대역(앞 옥텟)만 가리고 장비 식별용 뒤 옥텟은 남긴다`() {
        assertEquals("*.168.0.120", util.mask("192.168.0.120"))
    }

    @Test
    fun `null과 빈 문자열은 그대로 반환한다`() {
        assertNull(util.mask(null))
        assertEquals("", util.mask(""))
    }

    @Test
    fun `IPv4 형식이 아니면 원본을 그대로 반환한다`() {
        assertEquals("not-an-ip", util.mask("not-an-ip"))
    }
}
