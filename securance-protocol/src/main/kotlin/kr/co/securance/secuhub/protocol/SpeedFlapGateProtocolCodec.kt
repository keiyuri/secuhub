package kr.co.securance.secuhub.protocol

import kr.co.securance.secuhub.common.gate.GateTypeCodes
import kr.co.securance.secuhub.protocol.SpeedGateProtocolConstants.HeaderOffset
import java.time.LocalDateTime

/**
 * Speed Gate(1)와 Flap Gate(2)가 공유하는 프로토콜 코덱(계획서 3.4절).
 *
 * `SpeedGate Protocol Ver1_20250813_01.md` 문서를 기준으로 구현했다.
 * Turn Gate/Fast Gate는 별도 규격이므로 이 코덱을 쓰지 않는다 — [GateProtocolCodecRegistry] 참고.
 */
class SpeedFlapGateProtocolCodec : GateProtocolCodec {

    override val supportedGateTypes: Set<Int> = setOf(GateTypeCodes.SPEED_GATE, GateTypeCodes.FLAP_GATE)

    override fun verifyChecksum(packet: ByteArray): Boolean = SpeedGatePacketCodec.verifyChecksum(packet)

    override fun decode(packet: ByteArray): GatePacket {
        require(packet.size >= SpeedGateProtocolConstants.HEADER_LENGTH) {
            "패킷이 헤더 길이(${SpeedGateProtocolConstants.HEADER_LENGTH}) 미만입니다: ${packet.size}"
        }
        fun u16(offset: Int) = ((packet[offset].toInt() and 0xFF) shl 8) or (packet[offset + 1].toInt() and 0xFF)

        return GatePacket(
            command1 = packet[HeaderOffset.COMMAND1],
            command2 = packet[HeaderOffset.COMMAND2],
            objectCode = packet[HeaderOffset.OBJECT_CODE],
            dataInfoLength = packet[HeaderOffset.DATA_INFO_LENGTH].toInt() and 0xFF,
            dataCount = u16(HeaderOffset.DATA_COUNT),
            dataLength = u16(HeaderOffset.DATA_LENGTH),
            raw = packet,
        )
    }

    override fun buildStatusRequest(address: ByteArray, dateTime: LocalDateTime): ByteArray =
        SpeedGatePacketCodec.buildStatusRequestWithTimeSync(address, dateTime)

    override fun newReassembler(): PacketReassembler = SpeedGatePacketReassembler()
}
