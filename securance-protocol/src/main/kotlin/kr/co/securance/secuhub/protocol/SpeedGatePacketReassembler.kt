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
    // 누적 버퍼를 매 append마다 `buffer + chunk`로 통째로 재할당하던 이전 구현은, 한 패킷이 여러
    // 작은 조각(극단적으로는 1바이트씩)으로 나뉘어 도착하는 스트리밍 시나리오에서 매번 지금까지
    // 쌓인 전체 바이트를 복사해 사실상 O(n²) 비용을 냈다(2026-08-13 코드 리뷰). capacity를
    // 배수로 늘리는 성장형 배열(`data`/`size`, ArrayList와 동일한 상환 비용 전략)로 교체해
    // append당 상수 분할상환 비용으로 낮춘다 — 파싱 로직(STX 스캔/길이 검증/Tail 검증)은
    // `buffer[i]`/`buffer.size` 참조를 `data[i]`/`size`로 바꾼 것 외에 동일하다.
    private var data: ByteArray = ByteArray(0)
    private var size: Int = 0

    /**
     * 새로 수신한 바이트를 누적 버퍼에 더하고, 그 결과 완성된 패킷들을 순서대로 반환한다.
     * 미완성 바이트는 내부 버퍼에 남아 다음 [append] 호출 때 이어서 처리된다.
     */
    @Synchronized
    override fun append(chunk: ByteArray): List<ByteArray> {
        if (chunk.isEmpty() && size == 0) return emptyList()
        ensureCapacity(size + chunk.size)
        System.arraycopy(chunk, 0, data, size, chunk.size)
        size += chunk.size

        val packets = mutableListOf<ByteArray>()
        var cursor = 0

        while (true) {
            val stxIndex = findStx(cursor) ?: break

            // 길이 필드(2바이트)를 읽기에 데이터가 부족하면 다음 append를 기다린다.
            if (size - stxIndex < 3) {
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
            if (size - stxIndex < declaredLength) {
                cursor = stxIndex
                break
            }

            val tailXorPos = stxIndex + declaredLength - 2
            val tailChecksumPos = tailXorPos + 1
            val tailValid = data[tailXorPos] == SpeedGateProtocolConstants.PACKET_CHECKSUM_FIXED &&
                data[tailChecksumPos] == SpeedGateProtocolConstants.ETX
            if (!tailValid) {
                // Tail(0x08,0x03) 불일치 — STX 오탐. 1바이트만 버리고 재탐색.
                cursor = stxIndex + 1
                continue
            }

            packets += data.copyOfRange(stxIndex, stxIndex + declaredLength)
            cursor = stxIndex + declaredLength
        }

        compact(cursor)

        if (size > maxBufferSize) {
            // 하드 캡 초과(적대적 리뷰 지적): 예전에는 buffer 전체를 폐기했다 — 노이즈 바이트 하나가
            // 우연히 "유효 범위 내" 길이 필드로 읽혀 대기 상태에 들어가면, 그 뒤에 이미 도착해있던
            // 정상 패킷들까지 함께 날아갔다. 이제는 현재 버퍼의 첫 바이트(대기 중이던 STX 후보,
            // 또는 STX가 아예 없는 잡음의 시작)만 포기하고 그 뒤에서 다음 STX를 찾아 그 지점부터
            // 재동기화를 시도한다 — 다음 append() 호출 때 그 지점부터 다시 파싱되어 파묻혀 있던
            // 정상 패킷을 살릴 수 있다. 재동기화할 STX가 전혀 없으면(순수 잡음) 그때는 전량 폐기한다.
            val resyncIndex = findStx(1)
            compact(resyncIndex ?: size)
        }

        return packets
    }

    /** 현재 누적 버퍼에 남아있는(아직 완성되지 않은) 바이트 수. 테스트/모니터링용. */
    fun pendingByteCount(): Int = size

    private fun findStx(from: Int): Int? {
        for (i in from until size) {
            if (data[i] == SpeedGateProtocolConstants.STX) return i
        }
        return null
    }

    private fun readPacketLength(stxIndex: Int): Int {
        val hi = data[stxIndex + 1].toInt() and 0xFF
        val lo = data[stxIndex + 2].toInt() and 0xFF
        return (hi shl 8) or lo
    }

    /** [data]의 앞 [consumed]바이트를 버리고 나머지를 배열 앞으로 당겨온다(제자리 압축, 재할당 없음). */
    private fun compact(consumed: Int) {
        if (consumed <= 0) return
        val remaining = size - consumed
        if (remaining > 0) {
            System.arraycopy(data, consumed, data, 0, remaining)
        }
        size = remaining
    }

    /** [data]가 최소 [required]바이트를 담을 수 있도록 필요 시 배수(x2)로 키운다(ArrayList와 동일한 상환 비용 전략). */
    private fun ensureCapacity(required: Int) {
        if (data.size >= required) return
        var newCapacity = if (data.size == 0) INITIAL_CAPACITY else data.size * 2
        while (newCapacity < required) newCapacity *= 2
        data = data.copyOf(newCapacity)
    }

    companion object {
        private const val INITIAL_CAPACITY = 256
    }
}
