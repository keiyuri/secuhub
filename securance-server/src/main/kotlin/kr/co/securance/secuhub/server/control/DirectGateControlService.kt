package kr.co.securance.secuhub.server.control

import kr.co.securance.secuhub.common.util.HexCodec
import kr.co.securance.secuhub.domain.entity.DataSend
import kr.co.securance.secuhub.domain.repository.DataSendRepository
import kr.co.securance.secuhub.protocol.SpeedGateProtocolConstants
import kr.co.securance.secuhub.server.connection.GateConnectionRegistryImpl
import kr.co.securance.secuhub.server.db.GateDbWriteQueue
import kr.co.securance.secuhub.server.db.GateDbWriteTask
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.time.LocalDateTime

/**
 * DIRECT 방식 제어 명령 구현 — 커넥션의 액터 체인에 즉시 태워 보낸다(계획서 5.5절).
 *
 * 레거시 `SR_F_GateControl`의 `SendData`(클라이언트가 장비로 직접 전송) 경로에 대응한다.
 * 소켓 write는 반드시 [GateConnectionRegistryImpl.sendToLane]을 통해 커넥션 액터 체인에서
 * 수행되므로, 수신 패킷 처리/ACK 회신과 전송 순서가 하나의 직렬 체인으로 통일된다.
 *
 * 이력은 `tb_data_snd`에 남기되 [GateDbWriteQueue]에 위임한다 — 제어 응답성이 DB 지연에
 * 묶이지 않도록 하기 위함이며, 저장 순서는 디바이스 IP 파티션 단위로 보장된다.
 *
 * **DIRECT의 한계**: 전송 등록 성공 여부만으로 `snd_yn`을 확정하므로 장비가 실제로 명령을
 * 수행했는지는 보장하지 않으며, 재전송 가드/ACK 대기도 없다. ACK 확인과 재전송이 필요하면
 * `securance.control.dispatch-mode=QUEUED`([QueuedGateControlService] + [GateControlDispatcher])를
 * 사용한다 — 운영 기본값도 QUEUED다. DIRECT는 DB를 거치지 않는 저지연 경로가 필요한
 * 현장 점검/테스트용으로 남긴다.
 */
@Service
@ConditionalOnProperty(
    prefix = "securance.control",
    name = ["dispatch-mode"],
    havingValue = "DIRECT",
)
class DirectGateControlService(
    private val registry: GateConnectionRegistryImpl,
    private val dbWriteQueue: GateDbWriteQueue,
    private val dataSendRepository: DataSendRepository,
) : GateControlService {

    private val logger = LoggerFactory.getLogger(DirectGateControlService::class.java)

    override fun send(request: GateControlRequest): GateControlResult {
        val state = registry.findConnection(request.dtlIp)
        if (state == null) {
            logger.warn("게이트[{}]가 접속되어 있지 않아 제어 명령을 보낼 수 없습니다: {}", request.dtlIp, request.command)
            return GateControlResult.NOT_CONNECTED
        }

        val packet = state.codec.buildControlCommand(request.dtlLaneNo, request.payload)
        val accepted = registry.sendToLane(request.dtlIp, request.dtlLaneNo, packet)

        recordHistory(request, packet, accepted)

        return if (accepted) {
            logger.info(
                "게이트[{}] 레인 {} 제어 명령 전송: {}(요청자={})",
                request.dtlIp, request.dtlLaneNo, request.command, request.requestedBy ?: "SYSTEM",
            )
            GateControlResult.SENT
        } else {
            logger.warn(
                "게이트[{}] 레인 {} 제어 명령 전송 거부: {}",
                request.dtlIp, request.dtlLaneNo, request.command,
            )
            GateControlResult.REJECTED
        }
    }

    /**
     * 전송 성공/거부 여부와 무관하게 이력을 남긴다 — 거부된 명령도 감사 대상이다.
     *
     * (2026-08-25 확인) `sndHeader`/`sndData`/`sndTail`/`sndDataTp`는 [QueuedGateControlService]는
     * 채우지만 이 클래스는 매핑이 빠져 있어 DIRECT 모드로 보낸 명령은 `tb_data_snd`의 해당
     * 컬럼이 항상 빈 문자열로 저장되고 있었다 — 두 구현이 동일한 패킷 분할 로직을 갖도록 맞췄다.
     * `sndServer`도 마찬가지로 [GateControlDispatcher]는 [DataSendRepository.claimForSend] 시점에
     * 채우지만 DIRECT는 그 경로를 타지 않아 항상 빈 문자열이었다 — 같은 인스턴스가 즉시 전송을
     * 수행하므로 [localServerId]를 그대로 채운다.
     */
    private fun recordHistory(request: GateControlRequest, packet: ByteArray, accepted: Boolean) {
        val sndDate = LocalDateTime.now().format(GateControlDateFormats.SEND_DATE)
        val hex = HexCodec.toHex(packet)
        val laneInfo = registry.findConnection(request.dtlIp)?.laneInfoOf(request.dtlLaneNo)
        val headerEnd = SpeedGateProtocolConstants.HEADER_LENGTH
        val tailStart = packet.size - SpeedGateProtocolConstants.TAIL_LENGTH

        dbWriteQueue.enqueue(
            GateDbWriteTask(
                partitionKey = request.dtlIp,
                operationName = "InsertSendControl(${request.dtlIp},${request.dtlLaneNo},${request.command})",
            ) {
                dataSendRepository.save(
                    DataSend(
                        sndDate = sndDate,
                        // DIRECT는 전송을 즉시 완료 처리한다(폴링 잡이 다시 집어가지 않도록).
                        sndYn = if (accepted) DataSend.YES else DataSend.NO,
                        chkYn = if (accepted) DataSend.YES else DataSend.NO,
                        dtlIp = request.dtlIp,
                        dtlLaneNo = request.dtlLaneNo,
                        dtlType = laneInfo?.dtlType ?: DEFAULT_GATE_TYPE,
                        dtlId = laneInfo?.dtlId ?: 0,
                        locId = laneInfo?.locId ?: 0,
                        grpId = laneInfo?.grpId ?: 0,
                        sndUser = request.requestedBy ?: "SYSTEM",
                        sndServer = localServerId,
                        sndTypeCd = request.command.legacyCode,
                        sndDataTp = legacyDataTypeOf(request),
                        sndRaw = hex,
                        sndHeader = HexCodec.toHex(packet.copyOfRange(0, headerEnd)),
                        sndData = HexCodec.toHex(packet.copyOfRange(headerEnd, tailStart)),
                        sndTail = HexCodec.toHex(packet.copyOfRange(tailStart, packet.size)),
                    ),
                )
                Unit
            },
        )
    }

}
