package kr.co.securance.secuhub.protocol

import kr.co.securance.secuhub.common.gate.GateTypeCodes
import kr.co.securance.secuhub.protocol.SpeedGateProtocolConstants.HeaderOffset
import java.time.LocalDateTime

/**
 * Speed Gate(1)/Flap Gate(2)/Turn Gate(3)/Fast Gate(4)가 공유하는 프로토콜 코덱(계획서 3.4절).
 *
 * 1차 스캐폴드 때는 Turn/Fast Gate 규격 문서가 없어 "별도 구현 필요"로 남겨뒀으나(계획서 3.4절),
 * `FastGate Protocol Ver1_2020102601_01.md`(= 상위 호환 "SmartGate Protocol") 확보 후 대조한 결과
 * Header/Command/Tail 프레이밍과 `GATE_SETTING(0x4C)`/`GATE_STATUS(0x4D)`/`GATE_MOTOR(0x4B)` 객체는
 * 네 게이트 타입이 완전히 동일한 봉투를 쓴다는 것을 확인했다 — Turn Gate는 상태 데이터의 GATE TYPE
 * 필드 값(0x03)으로만 구분될 뿐 별도 객체/필드가 없고, Fast Gate도 이 코덱이 이미 다루는 범위
 * (decode/ack/상태요청/제어명령)에서는 추가 분기가 필요 없다(제어모드 필드에 Pause/Slide 값이
 * 늘어난 것뿐 — [SpeedGateControlCommand] 참고). 따라서 이 코덱 하나로 네 타입 모두 처리한다.
 *
 * Fast Gate 전용 확장인 `FAST_GATE_MOTOR(0x50)` Set/Request는 이 코덱이 다루는 공통 봉투 밖의
 * 별도 오브젝트라 [GateProtocolCodec] 인터페이스에는 편입하지 않고, [FastGateMotorCodec]에서
 * 독립적으로 제공한다(2026-08-13).
 */
class SpeedFlapGateProtocolCodec : GateProtocolCodec {

    override val supportedGateTypes: Set<Int> = setOf(
        GateTypeCodes.SPEED_GATE,
        GateTypeCodes.FLAP_GATE,
        GateTypeCodes.TURN_GATE,
        GateTypeCodes.FAST_GATE,
    )

    override val defaultAddress: ByteArray get() = SpeedGatePacketCodec.ZERO_ADDRESS

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

    override fun buildAck(objectCode: Byte, dateTime: LocalDateTime): ByteArray =
        SpeedGatePacketCodec.buildAck(objectCode, dateTime)

    override fun buildControlCommand(laneNo: Int, payload: SpeedGateControlPayload): ByteArray =
        SpeedGatePacketCodec.buildControlCommand(laneNo, payload)

    override fun newReassembler(): PacketReassembler = SpeedGatePacketReassembler()
}
