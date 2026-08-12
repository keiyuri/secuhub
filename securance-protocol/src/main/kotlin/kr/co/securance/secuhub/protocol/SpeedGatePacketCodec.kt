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
        // 적대적 리뷰 지적(2026-08-13): 호출자가 헤더 필드 폭을 넘는 값을 넘기면 이 함수가 조용히
        // 잘라서(overflow) 헤더와 실제 페이로드 길이가 어긋난 패킷을 만들어낼 수 있었다 — 수신측
        // 프레이밍 손실/오해석으로 이어지는 조용한 실패라 여기서 명시적으로 막는다.
        require(dataInfoLength in 0..0xFF) { "dataInfoLength는 0~255 범위여야 합니다(1바이트 필드): $dataInfoLength" }
        require(dataCount in 0..0xFFFF) { "dataCount는 0~65535 범위여야 합니다(2바이트 필드): $dataCount" }
        require(dataLength in 0..0xFFFF) { "dataLength는 0~65535 범위여야 합니다(2바이트 필드): $dataLength" }

        val totalLength = SpeedGateProtocolConstants.HEADER_LENGTH + payload.size + SpeedGateProtocolConstants.TAIL_LENGTH
        require(totalLength <= 0xFFFF) {
            "패킷 전체 길이가 Packet Length 필드 범위(0~65535)를 벗어납니다: $totalLength"
        }

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

    /**
     * 주소 필드를 0으로 채운 13바이트 — 레거시가 ACK/제어 패킷을 만들 때 사용하던 형태다.
     *
     * `ClsCommon.MakeACKDataAddTime`/`SR_C_DataHandler.BuildPacket`(Brian 보드)은 `new byte[38]`/
     * `new byte[27]`을 그대로 쓰고 Address 구간(6~18)에 아무 값도 채우지 않았다 — 즉 실제 장비는
     * 이 경로에서 주소 필드를 보지 않는다. 상태 요청([buildStatusRequestWithTimeSync])만
     * [buildAddress]로 만든 주소를 사용한다.
     */
    val ZERO_ADDRESS: ByteArray
        get() = ByteArray(SpeedGateProtocolConstants.ADDRESS_LENGTH)

    /**
     * 수신 패킷에 대한 ACK 회신 패킷을 만든다 — 레거시 `ClsCommon.MakeACKDataAddTime` 대응.
     *
     * `Command=SendAck(0x07)/Read(0x02)`, `DataInfoLen=7`, DataInfo에 현재 시각(BCD 7바이트)을
     * 실어 보내 장비 시간 동기화를 겸한다. 전체 길이는 Header(27)+DataInfo(7)+Tail(4) = 38바이트
     * ([SpeedGateProtocolConstants.ACK_PACKET_LENGTH]).
     *
     * 레거시 시그니처에는 `sAckType`(S/F/R) 파라미터가 있었으나 **프레임 어디에도 기록되지 않는
     * 죽은 인자**였다(호출부만 값을 넘기고 `MakeACKDataAddTime` 본문은 사용하지 않음). 잘못된
     * 계약을 그대로 옮기지 않기 위해 이 함수는 해당 인자를 받지 않는다 — 재전송 요청(RESEND)을
     * 프로토콜로 표현해야 한다면 별도 오브젝트 코드/패킷으로 설계해야 한다.
     *
     * @param objectCode 응답 대상 패킷의 Object Code(수신한 패킷의 것을 그대로 되돌려준다).
     */
    fun buildAck(objectCode: Byte, dateTime: LocalDateTime = LocalDateTime.now()): ByteArray =
        buildPacket(
            address = ZERO_ADDRESS,
            command1 = SpeedGateProtocolConstants.Command1.SEND_ACK,
            command2 = SpeedGateProtocolConstants.Command2.READ,
            objectCode = objectCode,
            dataInfoLength = 7,
            dataCount = 0,
            dataLength = 0,
            payload = encodeDateTime(dateTime),
        )

    /**
     * 제어 명령 패킷을 만든다 — 레거시 `SR_C_DataHandler.SetControlCmd`/`GenerateCmdBody` 대응.
     *
     * `Command=SendData(0x05)/Write(0x03)`, `ObjectCode=GATE_SETTING(0x4C)`,
     * `DataCount=1`, `DataLength=93(0x5D)`이며 본문은 93바이트 고정이다.
     *
     * 레거시의 Moon 보드(구형, `0x5B/0x53/0x6E` 헤더)는 이식 대상에서 제외했다 — 현행 장비는
     * 전부 Brian 보드이며, 클라이언트 코드도 `sDtlBoardType = "B"`로 하드코딩되어 있었다.
     *
     * @param laneNo 대상 레인 번호(1~32). 바이트 값 그대로 인코딩한다(레인 10 → 0x0A).
     * @param payload 명령/보안등급/스케줄 시간 데이터 묶음([SpeedGateControlPayload]).
     */
    fun buildControlCommand(
        laneNo: Int,
        payload: SpeedGateControlPayload,
    ): ByteArray {
        require(laneNo in 1..SpeedGateProtocolConstants.MAX_LANE_COUNT) { "laneNo 범위 오류: $laneNo" }

        val offsets = SpeedGateProtocolConstants.ControlBodyOffset
        val body = ByteArray(SpeedGateProtocolConstants.CONTROL_BODY_LENGTH)
        body[offsets.LANE_NUMBER] = laneNo.toByte()
        body[offsets.CONTROL_MODE] = payload.command.modeByte
        body[offsets.RESET_CODE] = payload.command.resetByte

        // 보안 등급/시간 데이터는 "지정하지 않으면 0"이 곧 "변경 없음"이다 — 레거시도 해당
        // 인자가 비어 있으면 배열 초기값(0)을 그대로 보냈다(GenerateCmdBody).
        payload.securityMode?.let { body[offsets.SECURITY_MODE] = it.value }
        payload.userTime?.let { it.copyInto(body, offsets.TIME_DATA_USER) }
        payload.securityTime?.let { it.copyInto(body, offsets.TIME_DATA_SECURITY) }

        return buildPacket(
            address = ZERO_ADDRESS,
            command1 = SpeedGateProtocolConstants.Command1.SEND_DATA,
            command2 = SpeedGateProtocolConstants.Command2.WRITE,
            objectCode = SpeedGateProtocolConstants.ObjectCode.GATE_SETTING,
            dataInfoLength = 0,
            dataCount = 1,
            dataLength = SpeedGateProtocolConstants.CONTROL_BODY_LENGTH,
            payload = body,
        )
    }

    /** 보안등급/시간 데이터가 없는 단순 제어 명령용 축약형. */
    fun buildControlCommand(laneNo: Int, command: SpeedGateControlCommand): ByteArray =
        buildControlCommand(laneNo, SpeedGateControlPayload(command))
}
