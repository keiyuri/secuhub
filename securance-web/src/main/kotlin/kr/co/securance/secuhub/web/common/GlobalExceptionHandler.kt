package kr.co.securance.secuhub.web.common

import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.servlet.resource.NoResourceFoundException

/**
 * 컨트롤러에서 잡히지 않은 예외를 위한 전역 로깅(2026-08-13 코드 리뷰 지적) — 이전까지
 * `@ControllerAdvice`가 [MultipartExceptionHandler] 하나(업로드 용량 초과 전용)뿐이라, 그 외
 * 미처리 예외(`NoSuchElementException`, `IllegalStateException` 등)는 어떤 컨트롤러가 던졌는지,
 * 어떤 요청이었는지를 일관되게 남기는 곳 없이 컨테이너 기본 로그(Tomcat 밸브 레벨, 앱 로거와
 * 다른 카테고리)로만 흘러갔다 — 로그를 앱 로거 기준으로만 보는 운영자는 놓치기 쉬웠다.
 *
 * 화면/상태 코드 처리는 바꾸지 않는다 — [templates/error.html]과 스프링 기본 에러 디스패치가
 * 이미 상태 코드별 안전한 안내(스택트레이스/메시지 노출 없음)를 하고 있으므로(error.html KDoc
 * 참고), 여기서는 `requestId`(RequestCorrelationFilter가 MDC에 심어둔 값, logging.pattern으로
 * 로그 라인에 자동 포함됨)를 포함해 앱 로거로 한 번 기록한 뒤 그대로 다시 던져 기존 에러
 * 디스패치 흐름(→ BasicErrorController → /error → error.html)을 그대로 타게 한다.
 *
 * [MultipartExceptionHandler]보다 늦게 평가되도록(더 구체적인 핸들러가 먼저 잡도록) 우선순위를
 * 최하위로 둔다 — `@ControllerAdvice`는 기본적으로 선언 순서가 불명확하므로 명시적으로 고정한다.
 */
@ControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
class GlobalExceptionHandler {
    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(request: HttpServletRequest, ex: Exception) {
        // MaxUploadSizeExceededException은 MultipartExceptionHandler가 더 구체적으로 처리하는
        // 케이스라 여기서는 에러 레벨로 남기지 않는다(사용자 입력 실수일 뿐 장애가 아님).
        // NoResourceFoundException은 브라우저가 자동 요청하는 favicon.ico 등 존재하지 않는 정적
        // 리소스 요청일 뿐 장애가 아닌데, 이를 ERROR로 남기면 스택트레이스만 쌓여 로그를 오염시킨다.
        if (ex is MaxUploadSizeExceededException || ex is NoResourceFoundException) throw ex

        log.error("미처리 예외 [{} {}] {}: {}", request.method, request.requestURI, ex.javaClass.simpleName, ex.message, ex)
        throw ex
    }
}
