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

    private val byGateType: Map<Int, GateProtocolCodec> =
        codecs.flatMap { codec -> codec.supportedGateTypes.map { it to codec } }.toMap()

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
