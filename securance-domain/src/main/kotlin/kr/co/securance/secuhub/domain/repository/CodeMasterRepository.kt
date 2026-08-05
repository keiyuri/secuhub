package kr.co.securance.secuhub.domain.repository

import kr.co.securance.secuhub.domain.entity.CodeMaster
import kr.co.securance.secuhub.domain.entity.CodeMasterId
import org.springframework.data.jpa.repository.JpaRepository

/**
 * `tb_code` 리포지토리. 게이트 타입(코드그룹 `GATE_TYPE`) 등 확장 가능한 코드값 조회에 쓰인다
 * (계획서 4.3절 — 신규 게이트 타입 추가는 이 테이블에 행을 추가하는 것만으로 가능).
 */
interface CodeMasterRepository : JpaRepository<CodeMaster, CodeMasterId> {
    fun findById_CodeGroupAndUseYnTrueOrderByDisplayOrder(codeGroup: String): List<CodeMaster>
}
