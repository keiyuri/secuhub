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

    /** 커넥션 하나가 사용할 프레임 재조립기를 새로 만든다(커넥션당 상태 보유, 공유 금지). */
    fun newReassembler(): PacketReassembler
}
