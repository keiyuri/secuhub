package kr.co.securance.secuhub.common.util

/**
 * 바이트 배열 <-> 16진 문자열 변환 유틸.
 *
 * 레거시 SR_Speed_Server의 `ClsCommon.ConvertHexStringToByte`/`ConvertByteToHexString`에 대응한다.
 * 게이트와 주고받는 원시 패킷은 DB(tb_data_rcv 등)에 16진 문자열로 저장되므로,
 * 프로토콜 코덱(securance-protocol)뿐 아니라 도메인 계층(securance-domain)과
 * 웹 화면의 원시 패킷 표시(securance-web)에서도 공통으로 쓰인다.
 */
object HexCodec {

    private val HEX_CHARS = "0123456789ABCDEF".toCharArray()

    /** 바이트 배열을 대문자 16진 문자열로 변환한다. 구분자는 없다 (예: "02002A04..."). */
    fun toHex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            out[i * 2] = HEX_CHARS[v ushr 4]
            out[i * 2 + 1] = HEX_CHARS[v and 0x0F]
        }
        return String(out)
    }

    /**
     * 16진 문자열을 바이트 배열로 변환한다. `-` 등 구분자는 무시한다(레거시 데이터 호환).
     * 문자열 길이가 홀수이면 [IllegalArgumentException]을 던진다.
     */
    fun fromHex(hex: String): ByteArray {
        val cleaned = hex.replace("-", "").trim()
        require(cleaned.length % 2 == 0) { "16진 문자열의 길이는 짝수여야 합니다: $hex" }
        val out = ByteArray(cleaned.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(cleaned[i * 2], 16)
            val lo = Character.digit(cleaned[i * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "유효하지 않은 16진 문자열입니다: $hex" }
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}
