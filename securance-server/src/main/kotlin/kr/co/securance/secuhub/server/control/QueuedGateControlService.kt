package kr.co.securance.secuhub.server.control

import kr.co.securance.secuhub.common.util.HexCodec
import kr.co.securance.secuhub.domain.entity.DataSend
import kr.co.securance.secuhub.domain.repository.DataSendRepository
import kr.co.securance.secuhub.protocol.GateProtocolCodecRegistry
import kr.co.securance.secuhub.protocol.SpeedGateProtocolConstants
import kr.co.securance.secuhub.server.connection.GateConnectionRegistryImpl
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.time.LocalDateTime

/**
 * QUEUED 방식 제어 명령 구현(계획서 5.5절) — `tb_data_snd`에 INSERT만 하고 실제 전송은
 * [GateControlDispatcher]/`SendControlJob`이 담당한다.
 *
 * DIRECT([DirectGateControlService])와의 차이는 **명령의 내구성**이다. 여기서는 명령이 DB에
 * 커밋된 뒤 응답하므로, 장비가 끊겨 있거나 애플리케이션이 재기동돼도 명령이 사라지지 않고
 * 재접속 후 이어서 전송된다. 대신 응답은 "접수 완료"이지 "장비 전송 완료"가 아니다.
 *
 * 커넥션이 없어도 [GateControlResult.QUEUED]로 접수한다 — 장비 재접속 시 전송되는 것이
 * QUEUED 모드의 존재 이유이므로, 여기서 NOT_CONNECTED로 거절하면 모드의 의미가 없어진다.
 */
@Service
@ConditionalOnProperty(
    prefix = "securance.control",
    name = ["dispatch-mode"],
    havingValue = "QUEUED",
    matchIfMissing = true,
)
class QueuedGateControlService(
    private val registry: GateConnectionRegistryImpl,
    private val dataSendRepository: DataSendRepository,
    private val codecRegistry: GateProtocolCodecRegistry,
) : GateControlService {

    private val logger = LoggerFactory.getLogger(QueuedGateControlService::class.java)

    override fun send(request: GateControlRequest): GateControlResult {
        val state = registry.findConnection(request.dtlIp)
        val laneInfo = state?.laneInfoOf(request.dtlLaneNo)

        // 커넥션이 아직 없으면 게이트 타입을 알 수 없다 — 기본 코덱(Speed/Flap)으로 인코딩한다.
        // 접속 이력이 없는 장비에 Turn/Fast 코덱이 필요해지면 tb_gate_dtl.dtl_type 조회로 보강한다.
        val codec = state?.codec ?: codecRegistry.forGateTypeOrNull(laneInfo?.dtlType ?: DEFAULT_GATE_TYPE)
        if (codec == null) {
            logger.error("게이트[{}]의 프로토콜 코덱을 찾지 못했습니다.", request.dtlIp)
            return GateControlResult.REJECTED
        }

        val packet = codec.buildControlCommand(request.dtlLaneNo, request.payload)
        val hex = HexCodec.toHex(packet)
        val headerEnd = SpeedGateProtocolConstants.HEADER_LENGTH
        val tailStart = packet.size - SpeedGateProtocolConstants.TAIL_LENGTH

        dataSendRepository.save(
            DataSend(
                sndDate = LocalDateTime.now().format(GateControlDateFormats.SEND_DATE),
                sndYn = DataSend.NO,
                chkYn = DataSend.NO,
                dtlIp = request.dtlIp,
                dtlLaneNo = request.dtlLaneNo,
                dtlType = laneInfo?.dtlType ?: state?.gateTypeCode ?: DEFAULT_GATE_TYPE,
                dtlId = laneInfo?.dtlId ?: 0,
                locId = laneInfo?.locId ?: 0,
                grpId = laneInfo?.grpId ?: 0,
                sndUser = request.requestedBy ?: "SYSTEM",
                // 컬럼 누락 수정(2026-09-08, 운영 DB 실측) — reg_user/snd_server_cd 참고는 DataSend.kt 필드 KDoc.
                regUser = request.requestedBy ?: "SYSTEM",
                sndServerCd = SND_SERVER_CD,
                sndTypeCd = request.command.legacyCode,
                sndDataTp = legacyDataTypeOf(request),
                sndRaw = hex,
                sndHeader = HexCodec.toHex(packet.copyOfRange(0, headerEnd)),
                sndData = HexCodec.toHex(packet.copyOfRange(headerEnd, tailStart)),
                sndTail = HexCodec.toHex(packet.copyOfRange(tailStart, packet.size)),
            ),
        )

        logger.info(
            "게이트[{}] 레인 {} 제어 명령 접수: {}(요청자={})",
            request.dtlIp, request.dtlLaneNo, request.command, request.requestedBy ?: "SYSTEM",
        )
        return GateControlResult.QUEUED
    }

    companion object {
        /** 게이트 타입을 알 수 없을 때의 기본값(Speed Gate). */
        private const val DEFAULT_GATE_TYPE = 1
    }
}
