package kr.co.securance.secuhub.common.util

/**
 * IPv4 주소 로그 마스킹 유틸.
 *
 * 레거시 `ClsCommon.RightIPMasking`/`LeftIPMasking`(SR_Speed_Server)에 대응한다. 로그에 IP 전체를
 * 그대로 남기지 않도록 오른쪽(뒤쪽 옥텟, 장치 식별 목적) 또는 왼쪽(앞쪽 옥텟, 대역 식별 목적)을
 * `*`로 가린다. dotted-decimal IPv4 형식이 아니거나 `hide` 값이 1~3 범위를 벗어나면 원본 문자열을
 * 그대로 반환한다 — 레거시 `Regex.Replace`가 매치 실패 시 원본을 그대로 돌려주던 관용적 동작과 동일하다.
 */
object IpMaskingUtil {

    // 레거시의 정규식 3종(hide=1/2/3)을 그대로 옮긴다. named group 대신 캡처 그룹 인덱스를 사용한다.
    private val RIGHT_MASK_REGEX_1 = Regex("""^((?:\d{1,3}\.){3})\d{1,3}$""")
    private val RIGHT_MASK_REGEX_2 = Regex("""^((?:\d{1,3}\.){2})\d{1,3}\.\d{1,3}$""")
    private val RIGHT_MASK_REGEX_3 = Regex("""^(\d{1,3}\.)\d{1,3}\.\d{1,3}\.\d{1,3}$""")

    private val LEFT_MASK_REGEX_1 = Regex("""^\d{1,3}\.((?:\d{1,3}\.){2}\d{1,3})$""")
    private val LEFT_MASK_REGEX_2 = Regex("""^(?:\d{1,3}\.){2}(\d{1,3}\.\d{1,3})$""")
    private val LEFT_MASK_REGEX_3 = Regex("""^(?:\d{1,3}\.){3}(\d{1,3})$""")

    /**
     * 오른쪽(뒤쪽) 옥텟을 마스킹한다.
     * hide=1: `192.168.0.*` / hide=2: `192.168.*.*` / hide=3: `192.*.*.*`
     */
    fun rightMask(ip: String?, hide: Int = 1): String? {
        if (ip.isNullOrEmpty()) return ip
        return when (hide) {
            1 -> RIGHT_MASK_REGEX_1.replace(ip) { "${it.groupValues[1]}*" }
            2 -> RIGHT_MASK_REGEX_2.replace(ip) { "${it.groupValues[1]}*.*" }
            3 -> RIGHT_MASK_REGEX_3.replace(ip) { "${it.groupValues[1]}*.*.*" }
            else -> ip
        }
    }

    /**
     * 왼쪽(앞쪽) 옥텟을 마스킹한다.
     * hide=1: `*.168.0.120` / hide=2: `*.*.0.120` / hide=3: `*.*.*.120`
     */
    fun leftMask(ip: String?, hide: Int = 1): String? {
        if (ip.isNullOrEmpty()) return ip
        return when (hide) {
            1 -> LEFT_MASK_REGEX_1.replace(ip) { "*.${it.groupValues[1]}" }
            2 -> LEFT_MASK_REGEX_2.replace(ip) { "*.*.${it.groupValues[1]}" }
            3 -> LEFT_MASK_REGEX_3.replace(ip) { "*.*.*.${it.groupValues[1]}" }
            else -> ip
        }
    }
}
