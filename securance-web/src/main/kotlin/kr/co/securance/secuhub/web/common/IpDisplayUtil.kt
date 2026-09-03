package kr.co.securance.secuhub.web.common

import kr.co.securance.secuhub.common.util.IpMaskingUtil
import org.springframework.stereotype.Component

/**
 * Thymeleaf 템플릿에서 `${@ipDisplay.mask(...)}`로 호출하는 표시 전용 IP 마스킹 헬퍼.
 *
 * 대상은 대시보드/게이트 트리뷰/리포트 조회 화면처럼 "한눈에 보는" 읽기 전용 화면의 게이트 IP다.
 * 실제 IP 값(로그, 제어 요청 대상, 게이트 상세 관리 폼의 `dtlIp` 입력/수정 필드)은 마스킹하지
 * 않는다 — 운영 추적·장비 식별에 원본 IP가 필요하기 때문이다(2026-09-03 사용자 확인).
 * [IpMaskingUtil.leftMask]를 hide=1로 적용해 대역(앞 옥텟)만 가리고 장비 식별용 뒤 옥텟은 남긴다.
 */
@Component("ipDisplay")
class IpDisplayUtil {
    fun mask(ip: String?): String? = IpMaskingUtil.leftMask(ip, hide = 1)
}
