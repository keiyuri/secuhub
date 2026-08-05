package kr.co.securance.secuhub.protocol

import kr.co.securance.secuhub.protocol.SpeedGateProtocolConstants.HeaderOffset
import java.time.LocalDateTime

/**
 * SpeedGate 프로토콜 패킷 인코딩/체크섬/BCD 날짜 유틸.
 *
 * 레거시 `ClsCommon.MakeReqStatusDataWithDateTime`/`MakeACKDataAddTime`/`CheckData`에 대응한다.
 * Speed Gate/Flap Gate가 공유하는 헤더 구조(계획서 3.4절)를 인코딩/검증한다.
 */
object SpeedGatePacketCodec {

    // ── 체크섬 (XOR + SUM) ───────────────────────────────────────────
    //
    // 프로토콜 문서: "STX부터 Data 마지막 바이트까지"를 대상으로 XOR 1바이트, SUM 1바이트를 계산하고,
    // Tail은 [XOR, SUM, 0x08(고정), ETX(고정)] 순서로 4바이트를 붙인다.

    /** [packetWithoutTail](Tail 4바이트를 제외한 패킷)에 대해 (XOR, SUM) 체크섬 쌍을 계산한다. */
    fun computeChecksum(packetWithoutTail: ByteArray): Pair<Byte, Byte> {
        var xor = 0
        var sum = 0
        for (b in packetWithoutTail) {
            val v = b.toInt() and 0xFF
            xor = xor xor v
            sum = (sum + v) and 0xFF
        }
        return xor.toByte() to sum.toByte()
    }

    /**
     * 완성된 패킷(Tail 포함)의 체크섬이 유효한지 검증한다.
     * 최소 길이([SpeedGateProtocolConstants.MIN_PACKET_LENGTH]) 미만이면 false.
     */
    fun verifyChecksum(packet: ByteArray): Boolean {
        if (packet.size < SpeedGateProtocolConstants.MIN_PACKET_LENGTH) return false
        val bodyEnd = packet.size - SpeedGateProtocolConstants.TAIL_LENGTH
        val (expectedXor, expectedSum) = computeChecksum(packet.copyOfRange(0, bodyEnd))
        return packet[bodyEnd] == expectedXor && packet[bodyEnd + 1] == expectedSum
    }

    /** Tail 4바이트(XOR, SUM, 0x08 고정, ETX 고정)를 생성한다. */
    fun buildTail(packetWithoutTail: ByteArray): ByteArray {
        val (xor, sum) = computeChecksum(packetWithoutTail)
        return byteArrayOf(xor, sum, SpeedGateProtocolConstants.PACKET_CHECKSUM_FIXED, SpeedGateProtocolConstants.ETX)
    }

    // ── BCD(Binary-Coded Decimal) 날짜/시간 인코딩 ───────────────────
    //
    // 프로토콜 문서 예시: 0x20,0x08,0x11,0x02,0x21,0x47,0x13 = 2020-08-11(월) 21:47:13
    // 즉 각 바이트의 상위 니블=십의 자리, 하위 니블=일의 자리인 BCD 인코딩이다.

    /** 0~99 범위의 십진수를 1바이트 BCD로 인코딩한다. */
    fun toBcd(decimal: Int): Byte {
        require(decimal in 0..99) { "BCD로 인코딩 가능한 범위(0~99)를 벗어났습니다: $decimal" }
        return (((decimal / 10) shl 4) or (decimal % 10)).toByte()
    }

    /** 1바이트 BCD 값을 0~99 범위의 십진수로 디코딩한다. */
    fun fromBcd(byte: Byte): Int {
        val v = byte.toInt() and 0xFF
        return (v ushr 4) * 10 + (v and 0x0F)
    }

    /** [LocalDateTime]의 요일을 프로토콜 요일 코드(Sunday=1..Saturday=7)로 변환한다. */
    fun toProtocolWeekday(dateTime: LocalDateTime): Byte {
        // java.time.DayOfWeek: MONDAY=1..SUNDAY=7 → 프로토콜: SUNDAY=1..SATURDAY=7
        val isoValue = dateTime.dayOfWeek.value // MON=1..SUN=7
        val protocolValue = if (isoValue == 7) 1 else isoValue + 1
        return protocolValue.toByte()
    }

    /**
     * [LocalDateTime]을 7바이트 BCD 타임스탬프(Year,Month,Day,Weekday,Hour,Minute,Second)로 인코딩한다.
     * Year는 연도의 마지막 두 자리만 사용한다(0x20 = 2020, 0x26 = 2026 ...).
     */
    fun encodeDateTime(dateTime: LocalDateTime): ByteArray = byteArrayOf(
        toBcd(dateTime.year % 100),
        toBcd(dateTime.monthValue),
        toBcd(dateTime.dayOfMonth),
        toProtocolWeekday(dateTime),
        toBcd(dateTime.hour),
        toBcd(dateTime.minute),
        toBcd(dateTime.second),
    )

    // ── Address(13 byte) 빌더 ────────────────────────────────────────
    //
    // Destination(8) = Host(1)=0x01 고정 + ComSlot(1) + Controller(1) + Module(1)=0x01 고정 + Device1..4(4, 비트마스크)
    // Source(5)      = Host/ComSlot/Controller/Module/Device 전부 0x01 고정(문서 규정)

    /**
     * 목적지 주소(Destination) 13바이트 중 앞 8바이트를 구성한다.
     * @param comSlot Com Slot 번호(0x01~, 통신 포트 번호)
     * @param controller Controller 번호(0x01~0x20, 최대 32)
     * @param deviceNumber Device 주소(1~32) — [SpeedGateProtocolConstants.MAX_LANE_COUNT]bit 비트마스크로 인코딩
     */
    fun buildAddress(comSlot: Int, controller: Int, deviceNumber: Int): ByteArray {
        require(comSlot in 1..255) { "comSlot 범위 오류: $comSlot" }
        require(controller in 1..32) { "controller 범위(0x01~0x20) 오류: $controller" }
        require(deviceNumber in 1..SpeedGateProtocolConstants.MAX_LANE_COUNT) { "deviceNumber 범위 오류: $deviceNumber" }

        val destination = byteArrayOf(
            0x01, // Host — 고정
            comSlot.toByte(),
            controller.toByte(),
            0x01, // Module — 고정
            *encodeDeviceBitmask(deviceNumber),
        )
        // Source — 문서 규정상 Host/ComSlot/Controller/Module/Device 전부 0x01 고정
        val source = byteArrayOf(0x01, 0x01, 0x01, 0x01, 0x01)
        return destination + source
    }

    /** Device 주소(1~32)를 32bit big-endian 비트마스크(4바이트)로 인코딩한다: `value = 1 shl (deviceNumber-1)`. */
    fun encodeDeviceBitmask(deviceNumber: Int): ByteArray {
        require(deviceNumber in 1..32) { "deviceNumber 범위 오류: $deviceNumber" }
        val value = 1L shl (deviceNumber - 1)
        return byteArrayOf(
            ((value ushr 24) and 0xFF).toByte(),
            ((value ushr 16) and 0xFF).toByte(),
            ((value ushr 8) and 0xFF).toByte(),
            (value and 0xFF).toByte(),
        )
    }

    // ── 패킷 조립 ─────────────────────────────────────────────────────

    /**
     * SpeedGate 패킷을 조립한다: Header(27) + payload(DataInfo+Data, 가변) + Tail(4).
     * 패킷 길이 필드(Header의 2바이트)와 체크섬은 이 함수가 자동 계산한다.
     */
    fun buildPacket(
        address: ByteArray,
        command1: Byte,
        command2: Byte,
        objectCode: Byte,
        dataInfoLength: Int,
        dataCount: Int,
        dataLength: Int,
        payload: ByteArray,
    ): ByteArray {
        require(address.size == SpeedGateProtocolConstants.ADDRESS_LENGTH) {
            "address는 ${SpeedGateProtocolConstants.ADDRESS_LENGTH}바이트여야 합니다: ${address.size}"
        }

        val totalLength = SpeedGateProtocolConstants.HEADER_LENGTH + payload.size + SpeedGateProtocolConstants.TAIL_LENGTH

        val header = ByteArray(SpeedGateProtocolConstants.HEADER_LENGTH)
        header[HeaderOffset.STX] = SpeedGateProtocolConstants.STX
        header[HeaderOffset.PACKET_LENGTH] = ((totalLength ushr 8) and 0xFF).toByte()
        header[HeaderOffset.PACKET_LENGTH + 1] = (totalLength and 0xFF).toByte()
        header[HeaderOffset.PROTOCOL_VERSION] = SpeedGateProtocolConstants.PROTOCOL_VERSION
        // Frame Option — 1차 스캐폴드는 IsAckReq(요청 시 응답 요구)만 세팅, 나머지는 문서 기본값(0) 사용.
        header[HeaderOffset.FRAME_OPTION] = 0x80.toByte()
        header[HeaderOffset.FRAME_OPTION + 1] = 0x40 // IsTimeSync 기본 on(문서 권장값)
        System.arraycopy(address, 0, header, HeaderOffset.ADDRESS, SpeedGateProtocolConstants.ADDRESS_LENGTH)
        header[HeaderOffset.COMMAND1] = command1
        header[HeaderOffset.COMMAND2] = command2
        header[HeaderOffset.OBJECT_CODE] = objectCode
        header[HeaderOffset.DATA_INFO_LENGTH] = dataInfoLength.toByte()
        header[HeaderOffset.DATA_COUNT] = ((dataCount ushr 8) and 0xFF).toByte()
        header[HeaderOffset.DATA_COUNT + 1] = (dataCount and 0xFF).toByte()
        header[HeaderOffset.DATA_LENGTH] = ((dataLength ushr 8) and 0xFF).toByte()
        header[HeaderOffset.DATA_LENGTH + 1] = (dataLength and 0xFF).toByte()

        val bodyWithoutTail = header + payload
        return bodyWithoutTail + buildTail(bodyWithoutTail)
    }

    /**
     * 상태 조회 + 시간 동기화 요청 패킷을 만든다(문서 "PC → Device : DataInfo(7) - Time Sync" 예시).
     * `Command=Request(0x06)/Read(0x02)`, `ObjectCode=GATE_STATUS(0x4D)`, `DataInfoLen=7`.
     */
    fun buildStatusRequestWithTimeSync(address: ByteArray, dateTime: LocalDateTime = LocalDateTime.now()): ByteArray =
        buildPacket(
            address = address,
            command1 = SpeedGateProtocolConstants.Command1.REQUEST_DATA,
            command2 = SpeedGateProtocolConstants.Command2.READ,
            objectCode = SpeedGateProtocolConstants.ObjectCode.GATE_STATUS,
            dataInfoLength = 7,
            dataCount = 0,
            dataLength = 0,
            payload = encodeDateTime(dateTime),
        )
}
