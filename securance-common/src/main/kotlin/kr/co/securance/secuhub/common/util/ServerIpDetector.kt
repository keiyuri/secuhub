package kr.co.securance.secuhub.common.util

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 게이트웨이 기반 서버 IP 자동탐지 유틸.
 *
 * 레거시 `ClsCommon.GetRealIpAddress`/`FindGetGatewayAddress`(SR_Speed_Server)에 대응한다. 레거시는
 * "활성 상태(Up)이고 루프백이 아니며 IPv4 게이트웨이를 가진 첫 NIC"의 게이트웨이 주소를 찾은 뒤,
 * 로컬 IP 목록 중 그 게이트웨이와 같은 서브넷(상위 N-1바이트 일치)에 속한 주소를 서버 IP로 채택했다
 * (VPN/가상 어댑터가 활성화된 환경에서 엉뚱한 NIC의 IP가 선택되는 것을 막기 위함).
 *
 * [한계] .NET `NetworkInterface.GetIPProperties().GatewayAddresses`는 OS 라우팅 테이블에서 인터페이스별
 * 기본 게이트웨이를 직접 조회할 수 있었지만, JVM 표준 API(`java.net.NetworkInterface`)는 게이트웨이
 * 주소 자체를 노출하지 않는다. 따라서 이 유틸은 "게이트웨이 존재 여부"를 직접 판별하는 대신, 레거시
 * 의도(가상 어댑터·루프백·비활성 NIC 배제, 실제 사용 중인 사설망 주소 우선)를 다음 휴리스틱으로
 * 최대한 재현한다:
 *   1. 활성 상태([NetworkInterface.isUp])이고, 루프백([NetworkInterface.isLoopback])·가상
 *      ([NetworkInterface.isVirtual], 예: 가상 스위치·터널 어댑터)이 아닌 인터페이스만 후보로 삼는다.
 *   2. 후보 인터페이스의 IPv4 주소 중 사이트-로컬(사설 대역, RFC 1918)인 첫 주소를 우선 채택한다 —
 *      게이트웨이가 있는 사내망 NIC은 통상 사설 대역을 쓰므로 레거시의 "게이트웨이가 있는 NIC 우선"
 *      의도에 가장 가깝다.
 *   3. 사이트-로컬 주소가 없으면(공인 IP 직결 등) 루프백이 아닌 첫 IPv4 주소로 폴백한다.
 * 정확한 라우팅 테이블 기반 게이트웨이 판별이 꼭 필요하다면 OS별 네이티브 명령(`route`/`ip route`)
 * 호출을 검토해야 한다 — 이 유틸의 범위를 벗어나므로 후속 작업으로 남긴다.
 *
 * [연동 범위] `ServerModeConfig`/`GateTcpServer`와의 통합(`host` 미설정 시 이 유틸로 자동탐지)은
 * 이번 작업 범위에 포함하지 않는다 — 유틸 함수와 단위 테스트만 제공한다(후속 작업).
 */
object ServerIpDetector {

    /**
     * 게이트웨이 기반 서버 IP를 탐지한다. NIC 열거 자체가 실패하거나(권한 문제 등) 후보가 없으면
     * null을 반환한다 — 호출부는 레거시처럼 "127.0.0.1 폴백" 등 안전한 기본값 처리를 직접 결정한다.
     */
    fun detectServerIp(): String? {
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }
            .getOrNull() ?: return null

        val activeInterfaces = interfaces.filter { ni ->
            runCatching { ni.isUp && !ni.isLoopback && !ni.isVirtual }.getOrDefault(false)
        }

        val ipv4Addresses = activeInterfaces.asSequence()
            .flatMap { ni -> runCatching { ni.inetAddresses.asSequence() }.getOrDefault(emptySequence()) }
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress }
            .toList()

        return ipv4Addresses.firstOrNull { it.isSiteLocalAddress }?.hostAddress
            ?: ipv4Addresses.firstOrNull()?.hostAddress
    }
}
