package kr.co.securance.secuhub.domain.entity

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.io.Serializable

/** `tb_code`의 복합키 `(code_grp, code_cd)`. */
@Embeddable
data class CodeMasterId(
    @Column(name = "code_grp", length = 10)
    val codeGroup: String = "",

    @Column(name = "code_cd", length = 20)
    val codeValue: String = "",
) : Serializable

/**
 * `tb_code` — 확장 가능한 코드 마스터. 게이트 타입(`GATE_TYPE`) 등을 관리한다(계획서 4.3절).
 *
 * 주의: 이 테이블만 `use_yn`이 `tinyint(1)`(1/0)이라 다른 테이블처럼 [kr.co.securance.secuhub.domain.converter.YnConverter]를
 * 쓰지 않고 [Boolean]에 그대로 매핑한다.
 */
@Entity
@Table(name = "tb_code")
class CodeMaster(
    @EmbeddedId
    val id: CodeMasterId,

    @Column(name = "code_nm", nullable = false, length = 100)
    var codeName: String,

    @Column(name = "code_val", length = 50)
    var codeValue: String? = null,

    @Column(name = "disp_order", nullable = false)
    var displayOrder: Int = 0,

    @Column(name = "use_yn", nullable = false)
    var useYn: Boolean = true,
)
