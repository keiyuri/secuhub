package kr.co.securance.secuhub.web.common

import org.apache.poi.ss.usermodel.WorkbookFactory
import org.springframework.mock.web.MockHttpServletResponse
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [ExcelExportService] 검증 — 전체 프로젝트 재감사(2026-08-10)에서 지적된 CSV/Excel 수식
 * 인젝션 방어(사용자명·그룹명 등 자유 입력 텍스트가 `=`/`+`/`-`/`@`로 시작할 때 엑셀이 수식으로
 * 해석하는 문제)가 실제로 셀에 반영되는지 확인한다.
 */
class ExcelExportServiceTest {

    private fun exportAndReload(vararg values: Any?): List<String> {
        val service = ExcelExportService()
        val response = MockHttpServletResponse()

        service.export(response, "test", listOf("col"), values.map { listOf(it) })

        WorkbookFactory.create(ByteArrayInputStream(response.contentAsByteArray)).use { workbook ->
            val sheet = workbook.getSheetAt(0)
            return values.indices.map { idx -> sheet.getRow(idx + 1).getCell(0).stringCellValue }
        }
    }

    @Test
    fun `등호로 시작하는 문자열은 작은따옴표를 붙여 수식으로 해석되지 않게 한다`() {
        val result = exportAndReload("=SUM(A1:A2)")
        assertEquals("'=SUM(A1:A2)", result[0])
    }

    @Test
    fun `더하기 빼기 골뱅이 탭 캐리지리턴으로 시작해도 동일하게 이스케이프한다`() {
        val result = exportAndReload("+1234", "-1234", "@SUM(1)", "\ttab", "\rcr")

        assertEquals("'+1234", result[0])
        assertEquals("'-1234", result[1])
        assertEquals("'@SUM(1)", result[2])
        assertEquals("'\ttab", result[3])
        assertEquals("'\rcr", result[4])
    }

    @Test
    fun `수식 트리거 문자로 시작하지 않는 일반 문자열은 그대로 저장된다`() {
        val result = exportAndReload("홍길동", "A-1", "메일=first")
        assertEquals("홍길동", result[0])
        assertEquals("A-1", result[1])
        assertEquals("메일=first", result[2])
    }

    @Test
    fun `빈 문자열은 예외 없이 그대로 저장된다`() {
        val result = exportAndReload("")
        assertEquals("", result[0])
    }
}
