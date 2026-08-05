package kr.co.securance.secuhub.server.tcp

import kr.co.securance.secuhub.protocol.GatePacket
import kr.co.securance.secuhub.server.connection.GateConnectionState

/**
 * 완성된 패킷 1개를 처리하는 확장 지점.
 *
 * 1차 스캐폴드는 [DefaultGatePacketHandler]만 제공한다 — 연결 등록/ACK/레인 추적/`tb_net_state`
 * 갱신까지만 구현하고, 객체 코드별 상세 파싱·저장(tb_data_rcv/tb_data_rcv_anal 등, 계획서 3.5절
 * "DB 쓰기 파이프라인"의 나머지 부분)은 후속 작업으로 남긴다(README 참고).
 */
fun interface GatePacketHandler {
    suspend fun handle(state: GateConnectionState, packet: GatePacket)
}
