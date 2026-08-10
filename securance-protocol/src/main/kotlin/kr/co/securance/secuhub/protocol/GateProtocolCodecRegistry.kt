package kr.co.securance.secuhub.protocol

import kr.co.securance.secuhub.common.exception.UnsupportedGateTypeException

/**
 * 게이트 타입(`dtl_type`) → [GateProtocolCodec] 조회.
 *
 * securance-server가 이 레지스트리를 Spring Bean으로 구성(등록된 [GateProtocolCodec] 구현체
 * 목록을 주입)한다. 등록되지 않은 게이트 타입(현재는 Turn/Fast, 계획서 3.4/3.8절)에 대한 조회는
 * 침묵 실패 대신 [UnsupportedGateTypeException]을 던진다 — 연결을 명시적으로 거부하기 위함.
 */
class GateProtocolCodecRegistry(codecs: List<GateProtocolCodec>) {

    private val byGateType: Map<Int, GateProtocolCodec> = buildMap {
        for (codec in codecs) {
            for (gateType in codec.supportedGateTypes) {
                // toMap()은 같은 key가 두 번 나오면 나중 값으로 조용히 덮어쓴다 — 두 코덱이 같은
                // 게이트 타입을 등록하는 설정 실수를 숨기게 되므로, 여기서는 즉시 실패시킨다.
                val existing = put(gateType, codec)
                check(existing == null) {
                    "게이트 타입[$gateType]이 두 개의 코덱에 중복 등록되었습니다: " +
                        "${existing?.let { it::class.simpleName }}, ${codec::class.simpleName}"
                }
            }
        }
    }

    /** [dtlType]에 대응하는 코덱을 찾는다. 없으면 [UnsupportedGateTypeException]. */
    fun resolve(dtlType: Int): GateProtocolCodec =
        byGateType[dtlType] ?: throw UnsupportedGateTypeException(dtlType)

    /** [dtlType]을 현재 지원하는지 여부(연결 수락 전 사전 검사용). */
    fun supports(dtlType: Int): Boolean = byGateType.containsKey(dtlType)

    /**
     * 예외 대신 null을 돌려주는 조회.
     *
     * 커넥션이 아직 없는 장비로 보낼 명령을 미리 인코딩하는 경우처럼(QUEUED 접수 경로),
     * 지원 여부를 호출자가 직접 처리해야 하는 상황에서 쓴다.
     */
    fun forGateTypeOrNull(dtlType: Int): GateProtocolCodec? = byGateType[dtlType]
}
