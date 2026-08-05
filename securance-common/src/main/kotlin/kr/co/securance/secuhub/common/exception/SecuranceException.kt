package kr.co.securance.secuhub.common.exception

/** secuhub(securance) 전 모듈이 공유하는 최상위 런타임 예외. */
open class SecuranceException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * 지원하지 않는 게이트 타입(`dtl_type`)에 대해 프로토콜 코덱을 찾지 못했을 때 던진다.
 *
 * 계획서 3.4절: Turn Gate/Fast Gate는 1차 스캐폴드에서 코덱이 등록되지 않으므로,
 * 해당 타입의 게이트가 연결을 시도하면 침묵 실패(무응답) 대신 이 예외로 명시적으로 거부한다.
 */
class UnsupportedGateTypeException(val dtlType: Int) :
    SecuranceException("지원하지 않는 게이트 타입입니다(dtl_type=$dtlType). GateProtocolCodecRegistry에 코덱이 등록되어 있지 않습니다.")

/**
 * 커넥션당 직렬 처리 체인(3.3절)이 백프레셔 한도를 초과해 작업을 거부했을 때 던진다.
 *
 * 레거시 C#의 `ChannelRejectedException`(침묵 성공 없이 명시적 실패를 반환하던 설계)에 대응한다.
 * 호출자는 이 예외를 통해 "전송 시도조차 되지 않음"과 "전송 후 실패"를 구분할 수 있어야 한다.
 */
class GateTaskRejectedException(val connectionKey: String) :
    SecuranceException("커넥션[$connectionKey]의 처리 대기열이 가득 차 작업이 거부되었습니다.")
