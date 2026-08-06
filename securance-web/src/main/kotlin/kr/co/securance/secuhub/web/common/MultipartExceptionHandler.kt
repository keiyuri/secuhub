package kr.co.securance.secuhub.web.common

import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.servlet.mvc.support.RedirectAttributes

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
        val referer = request.getHeader("Referer")
        return "redirect:${referer ?: "/dashboard"}"
    }
}
