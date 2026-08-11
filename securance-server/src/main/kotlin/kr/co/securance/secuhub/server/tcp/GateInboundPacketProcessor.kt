package kr.co.securance.secuhub.server.tcp

import kr.co.securance.secuhub.common.exception.GateTaskRejectedException
import kr.co.securance.secuhub.server.connection.GateConnectionState
import kr.co.securance.secuhub.server.db.GatePacketPersister
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 인바운드 바이트 조각 → 패킷 재조립 → 체크섬 검증 → 액터 위임까지의 공통 처리.
 *
 * [GateTcpServer](SERVER 모드)와 `GateTcpClient`(CLIENT 모드) 둘 다 소켓을 여는 방향만 다를 뿐
 * 수신 이후 처리는 완전히 동일하므로(계획서 3.1절 — 연결 방향과 패킷 처리는 독립적인 축),
 * 원래 `GateTcpServer` 안에 있던 `onChunkReceived`를 이 클래스로 추출해 양쪽이 공유한다
 * (2026-08-11, CLIENT 모드 신규 구현과 함께 분리).
 */
@Component
class GateInboundPacketProcessor(
    private val packetHandler: GatePacketHandler,
    private val packetPersister: GatePacketPersister,
) {
    private val logger = LoggerFactory.getLogger(GateInboundPacketProcessor::class.java)

    /**
     * **패킷 단위 격리(적대적 리뷰에서 지적)**: 재조립/체크섬/디코딩 실패나 액터 대기열 포화
     * ([GateTaskRejectedException])는 여기서 잡아 해당 패킷(청크)만 폐기하고 다음 청크로 넘어간다 —
     * 이 예외들을 호출자에게 흘려보내면 인바운드 처리 전체가 에러로 끝나 **소켓이 통째로 닫히고**,
     * dispose 가드가 net_state를 오프라인으로 기록해 게이트가 재접속하며, 바쁜 게이트일수록 큐
     * 포화 → 접속 끊김 → 재접속 → 다시 포화가 반복되는 폭풍에 빠진다. 반면 소켓 자체의 오류(연결
     * 리셋 등)는 호출자의 구독 체인 밖에서 발생해 정상적으로 종료되므로 여기서 막을 필요가 없다.
     */
    fun onChunkReceived(state: GateConnectionState, chunk: ByteArray) {
        val packets = try {
            state.reassembler.append(chunk)
        } catch (ex: Exception) {
            logger.warn("커넥션[{}] 패킷 재조립 중 예외가 발생해 이 청크를 폐기합니다.", state.dtlIp, ex)
            return
        }
        for (raw in packets) {
            try {
                if (!state.codec.verifyChecksum(raw)) {
                    // 폐기하되 원본은 tb_data_rcv_fail(Dead-letter)에 남긴다 — 통신선 노이즈/펌웨어
                    // 이슈를 사후 추적하려면 로그만으로는 부족하다(레거시는 경고 로그만 남기고 버렸다).
                    logger.warn("커넥션[{}] 체크섬 불일치 패킷을 폐기합니다(len={}).", state.dtlIp, raw.size)
                    packetPersister.persistChecksumFailure(state.dtlIp, raw)
                    continue
                }
                val decoded = state.codec.decode(raw)
                state.actor.submit { packetHandler.handle(state, decoded) }
            } catch (ex: GateTaskRejectedException) {
                logger.warn("커넥션[{}] 액터 대기열 초과로 패킷을 드롭합니다 - 연결은 유지합니다.", state.dtlIp)
            } catch (ex: Exception) {
                logger.warn("커넥션[{}] 패킷 처리 중 예외가 발생해 이 패킷만 폐기합니다.", state.dtlIp, ex)
            }
        }
    }
}
