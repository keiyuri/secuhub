package kr.co.securance.secuhub.web.gate

import kr.co.securance.secuhub.domain.entity.GateDetail
import kr.co.securance.secuhub.domain.entity.GateGroup
import kr.co.securance.secuhub.domain.entity.GateLocation
import kr.co.securance.secuhub.domain.repository.DataSendRepository
import kr.co.securance.secuhub.domain.repository.GateDetailRepository
import kr.co.securance.secuhub.protocol.GateControlCommandBuilder
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.Optional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [GateControlService] 검증 — 전체 프로젝트 재감사(2026-08-10)에서 "물리 제어 경로에 테스트가
 * 전혀 없다"고 지적된 항목의 최소 커버리지. 실제 모터/모드 명령을 큐(`tb_data_snd`)에 적재하는
 * 유일한 진입점이라, dtlId 미존재 시 안전하게 빈 처리되는지와 typeCd/기본값 로직을 우선 검증한다.
 */
class GateControlServiceTest {

    private val location = mock(GateLocation::class.java)
    private val group = mock(GateGroup::class.java)

    private fun detail(dtlId: Long = 1L, dtlIp: String = "192.168.0.10", laneNo: Int = 3) = GateDetail(
        dtlId = dtlId,
        location = location,
        group = group,
        dtlIp = dtlIp,
        dtlLaneNo = laneNo,
        dtlType = 1,
        useYn = true,
    )

    @Test
    fun `존재하지 않는 dtlId는 큐에 아무것도 적재하지 않고 false를 반환한다`() {
        val detailRepository = mock(GateDetailRepository::class.java)
        val dataSendRepository = mock(DataSendRepository::class.java)
        val service = GateControlService(detailRepository, dataSendRepository)
        `when`(detailRepository.findById(99L)).thenReturn(Optional.empty())

        val resetResult = service.sendReset(99L, "admin")
        val modeResult = service.sendModeChange(99L, "CC", "LM", "admin")
        val motorResult = service.sendMotorSetup(
            99L, GateControlCommandBuilder.MotorParams(), GateControlCommandBuilder.MotorParams(), isInit = false, requestedBy = "admin",
        )

        assertFalse(resetResult)
        assertFalse(modeResult)
        assertFalse(motorResult)
        verify(dataSendRepository, never()).save(org.mockito.ArgumentMatchers.any())
    }

    @Test
    fun `sendReset은 GATE_RESET 타입으로 controlType AC 패킷을 적재한다`() {
        val detailRepository = mock(GateDetailRepository::class.java)
        val dataSendRepository = mock(DataSendRepository::class.java)
        val service = GateControlService(detailRepository, dataSendRepository)
        val d = detail()
        `when`(detailRepository.findById(1L)).thenReturn(Optional.of(d))

        assertTrue(service.sendReset(1L, "admin"))

        val captor = ArgumentCaptor.forClass(kr.co.securance.secuhub.domain.entity.DataSend::class.java)
        verify(dataSendRepository).save(captor.capture())
        assertEquals("GATE_RESET", captor.value.sndTypeCd)
        assertEquals(d.dtlIp, captor.value.dtlIp)
        assertEquals(d.dtlLaneNo, captor.value.dtlLaneNo)
        assertEquals("admin", captor.value.sndUser)

        // AC는 buildModeChangeCommand의 System Reset 분기(offset 20에 0x01)로 이어진다.
        val expected = GateControlCommandBuilder.buildModeChangeCommand(d.dtlLaneNo, "AC")
        assertEquals(kr.co.securance.secuhub.common.util.HexCodec.toHex(expected), captor.value.sndRaw)
    }

    @Test
    fun `sendModeChange는 빈 값이면 기본 모드(CC+LM)로 대체한다`() {
        val detailRepository = mock(GateDetailRepository::class.java)
        val dataSendRepository = mock(DataSendRepository::class.java)
        val service = GateControlService(detailRepository, dataSendRepository)
        val d = detail()
        `when`(detailRepository.findById(1L)).thenReturn(Optional.of(d))

        service.sendModeChange(1L, userMode = "", secuMode = "", requestedBy = "admin")

        val captor = ArgumentCaptor.forClass(kr.co.securance.secuhub.domain.entity.DataSend::class.java)
        verify(dataSendRepository).save(captor.capture())
        assertEquals("MODE_CCLM", captor.value.sndTypeCd)
    }

    @Test
    fun `sendModeChange는 대소문자를 정규화해 저장한다`() {
        val detailRepository = mock(GateDetailRepository::class.java)
        val dataSendRepository = mock(DataSendRepository::class.java)
        val service = GateControlService(detailRepository, dataSendRepository)
        val d = detail()
        `when`(detailRepository.findById(1L)).thenReturn(Optional.of(d))

        service.sendModeChange(1L, userMode = "cf", secuMode = "mm", requestedBy = "admin")

        val captor = ArgumentCaptor.forClass(kr.co.securance.secuhub.domain.entity.DataSend::class.java)
        verify(dataSendRepository).save(captor.capture())
        assertEquals("MODE_CFMM", captor.value.sndTypeCd)
    }

    @Test
    fun `sendMotorSetup은 isInit 여부에 따라 typeCd를 MOTOR_INIT MOTOR_CHANGE로 구분한다`() {
        val detailRepository = mock(GateDetailRepository::class.java)
        val dataSendRepository = mock(DataSendRepository::class.java)
        val service = GateControlService(detailRepository, dataSendRepository)
        val d = detail()
        `when`(detailRepository.findById(1L)).thenReturn(Optional.of(d))
        val params = GateControlCommandBuilder.MotorParams(initSpeed = 10, initCount = 20)

        service.sendMotorSetup(1L, params, params, isInit = true, requestedBy = "admin")
        service.sendMotorSetup(1L, params, params, isInit = false, requestedBy = "admin")

        val captor = ArgumentCaptor.forClass(kr.co.securance.secuhub.domain.entity.DataSend::class.java)
        verify(dataSendRepository, org.mockito.Mockito.times(2)).save(captor.capture())
        assertEquals(listOf("MOTOR_INIT", "MOTOR_CHANGE"), captor.allValues.map { it.sndTypeCd })
    }
}
