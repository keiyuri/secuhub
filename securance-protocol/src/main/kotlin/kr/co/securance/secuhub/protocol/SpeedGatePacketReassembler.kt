package kr.co.securance.secuhub.protocol

/**
 * TCP 스트림에서 SpeedGate 패킷을 재조립하는 프레임 디코더.
 *
 * 레거시 SR_Speed_Server의 `ExtractPackets`(커넥션별 `RecvAccumulator` 누적 버퍼)에 대응한다.
 * "한 번의 read = 한 개의 패킷"을 가정하지 않고, STX 스캔 → 2바이트 길이 필드 → Tail(0x08,0x03)
 * 검증 → 불일치 시 1바이트씩 버리며 재동기화하는 방식으로 완성된 패킷만 추출한다.
 *
 * 순수 Kotlin 상태 머신이라 프레임워크 없이 단위테스트 가능하며, securance-server에서는
 * Reactor Netty의 `ByteToMessageDecoder`(또는 이에 준하는 핸들러) 안에서 커넥션별로
 * 인스턴스 하나씩 물려 사용한다(커넥션당 상태를 가지므로 공유 금지).
 */
class SpeedGatePacketReassembler(
    private val maxBufferSize: Int = SpeedGateProtocolConstants.MAX_REASSEMBLY_BUFFER_SIZE,
) : PacketReassembler {
    private var buffer: ByteArray = ByteArray(0)

    /**
     * 새로 수신한 바이트를 누적 버퍼에 더하고, 그 결과 완성된 패킷들을 순서대로 반환한다.
     * 미완성 바이트는 내부 버퍼에 남아 다음 [append] 호출 때 이어서 처리된다.
     */
    @Synchronized
    override fun append(chunk: ByteArray): List<ByteArray> {
        if (chunk.isEmpty() && buffer.isEmpty()) return emptyList()
        // 버퍼가 비어있을 때도 chunk를 복사 없이 그대로 참조하면 안 된다(적대적 리뷰 지적) — 지금의
        // 유일한 호출부(asByteArray())는 매번 새 배열이라 우연히 안전하지만, 호출자가 풀링된/재사용되는
        // 배열(예: Netty ByteBuf 기반 경로)을 넘기게 되면 계약 위반으로 조용히 데이터가 오염된다.
        buffer = if (buffer.isEmpty()) chunk.copyOf() else buffer + chunk

        val packets = mutableListOf<ByteArray>()
        var cursor = 0

        while (true) {
            val stxIndex = findStx(cursor) ?: break

            // 길이 필드(2바이트)를 읽기에 데이터가 부족하면 다음 append를 기다린다.
            if (buffer.size - stxIndex < 3) {
                cursor = stxIndex
                break
            }

            val declaredLength = readPacketLength(stxIndex)
            if (declaredLength < SpeedGateProtocolConstants.MIN_PACKET_LENGTH ||
                declaredLength > SpeedGateProtocolConstants.MAX_PACKET_LENGTH
            ) {
                // 손상된 길이 필드 — STX 오탐으로 간주하고 1바이트만 버린 뒤 재탐색(재동기화).
                cursor = stxIndex + 1
                continue
            }

            // 패킷 전체가 아직 도착하지 않았으면 다음 append를 기다린다.
            if (buffer.size - stxIndex < declaredLength) {
                cursor = stxIndex
                break
            }

            val tailXorPos = stxIndex + declaredLength - 2
            val tailChecksumPos = tailXorPos + 1
            val tailValid = buffer[tailXorPos] == SpeedGateProtocolConstants.PACKET_CHECKSUM_FIXED &&
                buffer[tailChecksumPos] == SpeedGateProtocolConstants.ETX
            if (!tailValid) {
                // Tail(0x08,0x03) 불일치 — STX 오탐. 1바이트만 버리고 재탐색.
                cursor = stxIndex + 1
                continue
            }

            packets += buffer.copyOfRange(stxIndex, stxIndex + declaredLength)
            cursor = stxIndex + declaredLength
        }

        buffer = if (cursor in 1..buffer.size) buffer.copyOfRange(cursor, buffer.size) else buffer

        if (buffer.size > maxBufferSize) {
            // 하드 캡 초과(적대적 리뷰 지적): 예전에는 buffer 전체를 폐기했다 — 노이즈 바이트 하나가
            // 우연히 "유효 범위 내" 길이 필드로 읽혀 대기 상태에 들어가면, 그 뒤에 이미 도착해있던
            // 정상 패킷들까지 함께 날아갔다. 이제는 현재 버퍼의 첫 바이트(대기 중이던 STX 후보,
            // 또는 STX가 아예 없는 잡음의 시작)만 포기하고 그 뒤에서 다음 STX를 찾아 그 지점부터
            // 재동기화를 시도한다 — 다음 append() 호출 때 그 지점부터 다시 파싱되어 파묻혀 있던
            // 정상 패킷을 살릴 수 있다. 재동기화할 STX가 전혀 없으면(순수 잡음) 그때는 전량 폐기한다.
            val resyncIndex = findStx(1)
            buffer = if (resyncIndex != null) buffer.copyOfRange(resyncIndex, buffer.size) else ByteArray(0)
        }

        return packets
    }

    /** 현재 누적 버퍼에 남아있는(아직 완성되지 않은) 바이트 수. 테스트/모니터링용. */
    fun pendingByteCount(): Int = buffer.size

    private fun findStx(from: Int): Int? {
        for (i in from until buffer.size) {
            if (buffer[i] == SpeedGateProtocolConstants.STX) return i
        }
        return null
    }

    private fun readPacketLength(stxIndex: Int): Int {
        val hi = buffer[stxIndex + 1].toInt() and 0xFF
        val lo = buffer[stxIndex + 2].toInt() and 0xFF
        return (hi shl 8) or lo
    }
}
