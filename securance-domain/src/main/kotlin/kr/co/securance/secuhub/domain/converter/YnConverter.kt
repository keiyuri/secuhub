package kr.co.securance.secuhub.domain.converter

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

/**
 * DB의 `char(1)` `'Y'/'N'` 컬럼을 Kotlin [Boolean]으로 매핑한다(계획서 4.1절).
 *
 * 주의: `tb_code.use_yn`만 `tinyint(1)`(1/0)이라 이 컨버터를 쓰지 않는다 — [CodeMaster] 참고.
 */
@Converter
class YnConverter : AttributeConverter<Boolean, String> {
    override fun convertToDatabaseColumn(attribute: Boolean?): String = if (attribute == true) "Y" else "N"
    override fun convertToEntityAttribute(dbData: String?): Boolean = dbData == "Y"
}
