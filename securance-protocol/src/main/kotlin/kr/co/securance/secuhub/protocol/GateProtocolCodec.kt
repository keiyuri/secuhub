package kr.co.securance.secuhub.protocol

import java.time.LocalDateTime

/** 디코딩된 SpeedGate 계열 패킷의 헤더 정보 + 원본 바이트. */
data class GatePacket(
    val command1: Byte,
    val command2: Byte,
    val objectCode: Byte,
    val dataInfoLength: Int,
    val dataCount: Int,
    val dataLength: Int,
    val raw: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is GatePacket && raw.contentEquals(other.raw)

    override fun hashCode(): Int = raw.contentHashCode()
}

/** 커넥션별 TCP 스트림 프레임 재조립기 공통 계약. 커넥션당 하나의 인스턴스만 사용한다(상태 보유). */
interface PacketReassembler {
    /** 새로 수신한 바이트를 누적하고, 그 결과 완성된 패킷들을 반환한다. */
    fun append(chunk: ByteArray): List<ByteArray>
}

/**
 * 게이트 타입별로 교체 가능한 프로토콜 코덱 계약(계획서 3.4절).
 *
 * Speed Gate와 Flap Gate는 [SpeedFlapGateProtocolCodec] 구현 하나를 공유하고,
 * Turn Gate/Fast Gate는 별도 규격 문서 확보 후 각각의 구현체를 추가한다(1차 스캐폴드 미구현).
 */
interface GateProtocolCodec {

    /** 이 코덱이 처리할 수 있는 `tb_gate_dtl.dtl_type` 원시 코드값 집합. */
    val supportedGateTypes: Set<Int>

    /** 완성된 패킷의 체크섬이 유효한지 검증한다. */
    fun verifyChecksum(packet: ByteArray): Boolean

    /** 패킷 헤더를 파싱해 [GatePacket]으로 만든다. */
    fun decode(packet: ByteArray): GatePacket

    /** 상태 조회(+ 시간 동기화) 요청 패킷을 만든다. */
    fun buildStatusRequest(address: ByteArray, dateTime: LocalDateTime = LocalDateTime.now()): ByteArray

    /**
     * 주소 구성 없이 상태 조회 요청 패킷을 만드는 편의 메서드.
     *
     * [Codex 리뷰 수정] 이전에는 `comSlot=1, controller=1, deviceNumber=1`로 고정된 주소를 임의로
     * 지어 썼는데, 이는 기본 주소가 아닌 장치에 상태 조회가 도달하지 않을 수 있는 버그였다. 레거시
     * `ClsCommon.MakeReqStatusDataWithDateTime`/`MakeACKDataAddTime`을 다시 확인해 보면 애초에
     * 주소 바이트(offset 6~18)를 전혀 채우지 않고 0으로 남겨둔다 — 이 프로토콜은 장치당 TCP 1:1
     * 연결을 전제로 해 PC→Device 방향에서는 주소 필드 자체가 쓰이지 않는다. 이 코드베이스에도
     * `GateDetail`에 comSlot/controller/deviceNumber를 저장하는 컬럼이 없어(주소를 알 방법이
     * 없음) 레거시와 동일하게 13바이트 전부 0인 주소를 쓰는 것이 유일하게 근거 있는 기본값이다.
     * 다중 레인/컨트롤러 구성이 실제로 필요해지면 이 기본 구현 대신 [buildStatusRequest]를
     * 직접 호출해 실제 주소를 넘긴다.
     */
    fun buildStatusRequest(dateTime: LocalDateTime = LocalDateTime.now()): ByteArray =
        buildStatusRequest(ByteArray(SpeedGateProtocolConstants.ADDRESS_LENGTH), dateTime)

    /** 커넥션 하나가 사용할 프레임 재조립기를 새로 만든다(커넥션당 상태 보유, 공유 금지). */
    fun newReassembler(): PacketReassembler
}
