package kr.co.securance.secuhub.web.common

import jakarta.servlet.http.HttpServletResponse
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.xssf.streaming.SXSSFWorkbook
import org.springframework.stereotype.Service
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * 레거시 `SR_C_Excel.DtToExcel`(각 화면 코드비하인드에서 DataTable을 엑셀로 직접 변환하던 방식)을
 * 대체하는 공용 컴포넌트(계획서 `SR_Speed_Client_전환_계획.md` 5절). 조회 화면(#8/#9 등)마다
 * 엑셀 변환 로직을 중복 구현하지 않도록, 헤더+행 데이터만 넘기면 응답 스트림에 바로 써준다.
 *
 * [SXSSFWorkbook]을 쓰는 이유: 조회 결과가 수천 건일 수 있는 화면(#9 ViewEvent 등)에서 일반
 * `XSSFWorkbook`은 전체 시트를 메모리에 올려 OOM 위험이 있다 — 스트리밍 방식으로 100행 단위만
 * 메모리에 유지한다.
 */
@Service
class ExcelExportService {

    fun export(
        response: HttpServletResponse,
        fileName: String,
        headers: List<String>,
        rows: List<List<Any?>>,
    ) {
        SXSSFWorkbook(100).use { workbook ->
            val sheet = workbook.createSheet("Sheet1")

            val headerRow = sheet.createRow(0)
            headers.forEachIndexed { idx, title -> headerRow.createCell(idx).setCellValue(title) }

            rows.forEachIndexed { rowIdx, row ->
                val sheetRow = sheet.createRow(rowIdx + 1)
                row.forEachIndexed { colIdx, value -> sheetRow.createCell(colIdx).setValue(value) }
            }

            response.contentType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            // 한글 파일명은 RFC 5987 encoding 없이 그대로 내려보내면 브라우저별로 깨질 수 있어 인코딩한다.
            val encodedName = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20")
            response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''$encodedName.xlsx")

            workbook.write(response.outputStream)
            workbook.dispose()
        }
    }

    private fun Cell.setValue(value: Any?) {
        when (value) {
            null -> setBlank()
            is Number -> setCellValue(value.toDouble())
            is Boolean -> setCellValue(value)
            else -> setCellValue(value.toString())
        }
    }
}
