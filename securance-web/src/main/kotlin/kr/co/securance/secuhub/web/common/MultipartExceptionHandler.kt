package kr.co.securance.secuhub.web.common

import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import java.net.URI

/**
 * `spring.servlet.multipart.max-file-size`(10MB, #5 SetupLocation 배치도 업로드용)를 넘는 파일을
 * 올리면 스프링이 [MaxUploadSizeExceededException]을 던지는데, 이걸 잡는 핸들러가 없으면 다른 업로드
 * 실패(잘못된 이미지 형식 등)와 달리 친절한 한글 flash 메시지 대신 스프링 기본 500 에러 페이지가
 * 노출된다. Referer로 되돌아가 다른 업로드 실패와 동일한 방식(error flash)으로 안내한다.
 */
@ControllerAdvice
class MultipartExceptionHandler {
    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun handleMaxUploadSize(
        request: HttpServletRequest,
        redirectAttributes: RedirectAttributes,
    ): String {
        redirectAttributes.addFlashAttribute("error", "파일 용량이 너무 큽니다(최대 10MB까지 업로드할 수 있습니다).")
        return "redirect:${safeRedirectPath(request.getHeader("Referer"))}"
    }

    /**
     * Opus 전체 리뷰 지적: Referer 헤더는 클라이언트가 완전히 제어할 수 있는 값이라, 이걸 그대로
     * `redirect:`에 넣으면 오픈 리다이렉트(공격자가 준비한 외부 사이트로 리다이렉트)가 된다.
     * Referer의 스킴/호스트는 버리고 같은 서버 내 경로(+쿼리)만 추려서 상대경로로 되돌린다 —
     * 파싱 실패나 절대경로가 아닌 경우(`//evil.com`처럼 스킴 없는 프로토콜 상대 URL 포함)는
     * 전부 기본값(`/dashboard`)으로 안전하게 대체한다.
     */
    private fun safeRedirectPath(referer: String?): String {
        if (referer.isNullOrBlank()) return "/dashboard"
        val path = runCatching { URI(referer).rawPath }.getOrNull()
        if (path.isNullOrBlank() || !path.startsWith("/")) return "/dashboard"
        val query = runCatching { URI(referer).rawQuery }.getOrNull()
        return if (query.isNullOrBlank()) path else "$path?$query"
    }
}
