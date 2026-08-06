package kr.co.securance.secuhub.web.schedule

import kr.co.securance.secuhub.domain.entity.DataSend
import kr.co.securance.secuhub.domain.entity.GateDetail
import kr.co.securance.secuhub.domain.entity.GateGroup
import kr.co.securance.secuhub.domain.entity.GateLocation
import kr.co.securance.secuhub.domain.repository.DataSendRepository
import kr.co.securance.secuhub.domain.repository.GateDetailRepository
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.Optional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScheduleApplyServiceTest {

    private val location = mock(GateLocation::class.java)
    private val group = mock(GateGroup::class.java)

    private fun detail(dtlId: Long, dtlIp: String, laneNo: Int, useYn: Boolean = true) = GateDetail(
        dtlId = dtlId,
        location = location,
        group = group,
        dtlIp = dtlIp,
        dtlLaneNo = laneNo,
        dtlType = 1,
        useYn = useYn,
    )

    @Test
    fun `dtlId가 주어지면 그룹-위치보다 우선하고 useYn=false면 빈 목록을 반환한다`() {
        val detailRepository = mock(GateDetailRepository::class.java)
        val dataSendRepository = mock(DataSendRepository::class.java)
        val service = ScheduleApplyService(detailRepository, dataSendRepository)

        `when`(detailRepository.findById(1L)).thenReturn(Optional.of(detail(1L, "192.168.0.1", 1, useYn = false)))

        // dtlId가 지정되면 grpId/locId는 무시되어야 한다(레포지토리 조회가 아예 호출되지 않아야 함).
        val result = service.targets(locId = 99L, grpId = 88L, dtlId = 1L)

        assertEquals(emptyList(), result, "useYn=false인 게이트는 예약 대상에서 제외되어야 한다")
        verify(detailRepository, never()).findByGroup_GrpIdAndUseYnTrue(anyLong())
        verify(detailRepository, never()).findByLocation_LocIdAndUseYnTrue(anyLong())
    }

    @Test
    fun `dtlId가 없고 grpId가 있으면 그룹 단위로 조회한다`() {
        val detailRepository = mock(GateDetailRepository::class.java)
        val dataSendRepository = mock(DataSendRepository::class.java)
        val service = ScheduleApplyService(detailRepository, dataSendRepository)

        `when`(detailRepository.findByGroup_GrpIdAndUseYnTrue(1L)).thenReturn(listOf(detail(1L, "192.168.0.1", 1)))

        val result = service.targets(locId = 99L, grpId = 1L, dtlId = null)

        assertEquals(1, result.size)
        verify(detailRepository, never()).findByLocation_LocIdAndUseYnTrue(anyLong())
    }

    @Test
    fun `모두 null이면 빈 목록을 반환한다(위치 미선택)`() {
        val detailRepository = mock(GateDetailRepository::class.java)
        val dataSendRepository = mock(DataSendRepository::class.java)
        val service = ScheduleApplyService(detailRepository, dataSendRepository)

        assertEquals(emptyList(), service.targets(null, null, null))
    }

    @Test
    fun `applyMode는 대상이 없으면 DataSend를 저장하지 않고 0을 반환한다`() {
        val detailRepository = mock(GateDetailRepository::class.java)
        val dataSendRepository = mock(DataSendRepository::class.java)
        val service = ScheduleApplyService(detailRepository, dataSendRepository)

        val count = service.applyMode(ScheduleApplyForm(locId = null, grpId = null, dtlId = null), "tester")

        assertEquals(0, count)
        verify(dataSendRepository, never()).saveAll(anyList<DataSend>())
    }

    @Test
    fun `resetMode는 대상 게이트 각각에 NANA 명령을 큐에 적재한다`() {
        // 레거시 cbSchdRst 체크박스와 동일: "NA+NA"(변경 없음) 명령을 다시 보내 예약을 해제한다.
        val detailRepository = mock(GateDetailRepository::class.java)
        val dataSendRepository = mock(DataSendRepository::class.java)
        `when`(detailRepository.findByGroup_GrpIdAndUseYnTrue(1L)).thenReturn(
            listOf(detail(1L, "192.168.0.1", 1), detail(2L, "192.168.0.2", 2)),
        )
        val service = ScheduleApplyService(detailRepository, dataSendRepository)

        val count = service.resetMode(locId = null, grpId = 1L, dtlId = null, requestedBy = "tester")

        assertEquals(2, count)
        @Suppress("UNCHECKED_CAST")
        val captor = ArgumentCaptor.forClass(List::class.java) as ArgumentCaptor<List<DataSend>>
        verify(dataSendRepository).saveAll(captor.capture())
        assertTrue(captor.value.all { it.sndTypeCd == "MODE_NANA" })
    }
}
